package com.fieldtap.core.session

import com.fieldtap.format.Schema
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * `ApplicationExitInfo` as plain values, read by com.fieldtap.platform.exit.ExitReasonReader
 * (`ActivityManager.getHistoricalProcessExitReasons(packageName, 0, 16)`).
 */
data class ExitRecord(
    val pid: Int,
    /** `getTimestamp()`, Unix ms. */
    val timestampWallMs: Long,
    /** `getReason()`, an `ApplicationExitInfo.REASON_*` value. */
    val reason: Int,
    val description: String?,
)

/**
 * `summary.stopped_by` and `session_interrupted` `cause` tokens.
 *
 * [token] maps `ApplicationExitInfo.REASON_*` to its name, lower case, without `REASON_`:
 * 0 `unknown`, 1 `exit_self`, 2 `signaled`, 3 `low_memory`, 4 `crash`, 5 `crash_native`, 6 `anr`,
 * 7 `initialization_failure`, 8 `permission_change`, 9 `excessive_resource_usage`,
 * 10 `user_requested`, 11 `user_stopped`, 12 `dependency_died`, 13 `other`, 14 `freezer`,
 * 15 `package_state_change`, 16 `package_updated`; any other value `unknown`. The values were checked
 * against `android.app.ApplicationExitInfo` in the android-37.0 platform.
 *
 * Owner: workstream `session-core`.
 */
object ExitReasons {
    /** `summary.stopped_by` while the session is open (`stopped_utc` null). */
    const val RECORDING: String = "recording"

    const val USER: String = "user"
    const val UNKNOWN: String = "unknown"

    /**
     * `REASON_CRASH`. The recorder also writes it when a programming error inside the process stops a
     * session, so that the session is closed consistently instead of being left open.
     */
    const val CRASH: String = "crash"

    private val REASON_TOKENS: List<String> = listOf(
        "unknown", "exit_self", "signaled", "low_memory", "crash", "crash_native", "anr",
        "initialization_failure", "permission_change", "excessive_resource_usage", "user_requested",
        "user_stopped", "dependency_died", "other", "freezer", "package_state_change", "package_updated",
    )

    private val TOKEN: Regex = Regex(Schema.TOKEN_PATTERN)

    fun token(reason: Int): String = REASON_TOKENS.getOrNull(reason) ?: UNKNOWN

    /** True when [value] matches `^[a-z0-9_]+$`, as `summary.stopped_by` and every `cause` must. */
    fun isToken(value: String): Boolean = TOKEN.matches(value)

    /** [value] when it is a token, else [UNKNOWN]; a malformed token must never reach a file. */
    internal fun tokenOrUnknown(value: String): String = if (isToken(value)) value else UNKNOWN
}

/**
 * The heartbeat: proof of life every 5 s while recording, in `<stateDir>/<dirName>.heartbeat`
 * (outside the session directory, which holds only the seven files). One line:
 * `v1 <wallMs> <elapsedMs> <pid>\n`. [FileSessionFiles] replaces it atomically; a torn or unreadable
 * file (no final LF, a missing or extra field, a sign, an overflow) decodes to null.
 *
 * Owner: workstream `session-core`.
 */
data class HeartbeatRecord(val wallMs: Long, val elapsedMs: Long, val pid: Int) {
    fun encode(): String = "$VERSION $wallMs $elapsedMs $pid\n"

    companion object {
        private const val VERSION: String = "v1"

        private val LINE: Regex = Regex("v1 (0|[1-9][0-9]{0,18}) (0|[1-9][0-9]{0,18}) (0|[1-9][0-9]{0,9})\n")

        fun decode(text: String): HeartbeatRecord? {
            val match = LINE.matchEntire(text) ?: return null
            val wallMs = match.groupValues[1].toLongOrNull() ?: return null
            val elapsedMs = match.groupValues[2].toLongOrNull() ?: return null
            val pid = match.groupValues[3].toIntOrNull() ?: return null
            return HeartbeatRecord(wallMs, elapsedMs, pid)
        }
    }
}

/**
 * How often the recorder touches the disk, on the elapsed clock.
 * CSVs flush every second and sync every 5 s; session.json and cells.csv are rewritten atomically every
 * 60 s and at stop; the heartbeat every 5 s; storage is measured every 60 s.
 */
data class WritePolicy(
    val flushEveryMs: Long = 1_000,
    val syncEveryMs: Long = 5_000,
    val snapshotEveryMs: Long = 60_000,
    val heartbeatEveryMs: Long = 5_000,
    val storageCheckEveryMs: Long = 60_000,
)

enum class WriteAction { FLUSH, SYNC, SNAPSHOT, HEARTBEAT, STORAGE_CHECK }

/**
 * Which periodic writes are due. [due] returns each action whose next slot has been reached and records
 * it as done; the result iterates in [WriteAction] order.
 *
 * Slots are at a fixed rate on the grid `startElapsedMs + k * period` (k >= 1), so all actions are first
 * due one period after [startElapsedMs]. A late tick does not shift later slots, which would make a
 * flush skip a tick whenever the 1 s ticker runs a few milliseconds early relative to the previous one;
 * slots missed during a stall are skipped rather than returned in a burst.
 *
 * Owner: workstream `session-core`.
 */
class WriteSchedule(private val policy: WritePolicy, private val startElapsedMs: Long) {
    private val nextDueMs = LongArray(WriteAction.entries.size)

    init {
        for (action in WriteAction.entries) {
            val period = periodMs(action)
            require(period > 0) { "The $action period must be positive, was $period ms" }
            nextDueMs[action.ordinal] = startElapsedMs + period
        }
    }

    fun due(nowElapsedMs: Long): Set<WriteAction> {
        val due = LinkedHashSet<WriteAction>()
        for (action in WriteAction.entries) {
            if (nowElapsedMs < nextDueMs[action.ordinal]) continue
            due += action
            val period = periodMs(action)
            val slotsPassed = (nowElapsedMs - startElapsedMs) / period
            nextDueMs[action.ordinal] = startElapsedMs + (slotsPassed + 1) * period
        }
        return due
    }

    private fun periodMs(action: WriteAction): Long = when (action) {
        WriteAction.FLUSH -> policy.flushEveryMs
        WriteAction.SYNC -> policy.syncEveryMs
        WriteAction.SNAPSHOT -> policy.snapshotEveryMs
        WriteAction.HEARTBEAT -> policy.heartbeatEveryMs
        WriteAction.STORAGE_CHECK -> policy.storageCheckEveryMs
    }
}

/**
 * The storage cap: sessions stop being created, old ones are never deleted automatically.
 * Decimal units, as in the plan ("2 GB").
 */
data class StoragePolicy(
    val capBytes: Long = 2_000_000_000,
    val minFreeBytes: Long = 200_000_000,
)

/**
 * - [canStart]: `usedBytes < capBytes && freeBytes >= minFreeBytes`.
 * - [mustStop]: `usedBytes >= capBytes || freeBytes < minFreeBytes / 4` (50 MB with the defaults).
 *
 * Owner: workstream `session-core`.
 */
data class StorageStatus(val usedBytes: Long, val freeBytes: Long, val policy: StoragePolicy) {
    val canStart: Boolean get() = usedBytes < policy.capBytes && freeBytes >= policy.minFreeBytes

    val mustStop: Boolean get() = usedBytes >= policy.capBytes || freeBytes < policy.minFreeBytes / 4
}

/** Owner: workstream `session-core`. */
object StorageUsage {
    /**
     * Total bytes of regular files under [root], recursively; 0 when it does not exist. Symbolic links
     * are not followed. Entries that vanish or cannot be read while the tree is walked are skipped.
     */
    fun usedBytes(root: File): Long {
        if (!root.exists()) return 0
        var total = 0L
        try {
            Files.walkFileTree(
                root.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
        } catch (e: IOException) {
            // The root itself could not be walked: the bytes counted so far are the best answer.
        }
        return total
    }

    /** `File.usableSpace` of [root] (or its nearest existing parent); 0 when no parent exists. */
    fun freeBytes(root: File): Long {
        var candidate: File? = root.absoluteFile
        while (candidate != null && !candidate.exists()) candidate = candidate.parentFile
        return candidate?.usableSpace ?: 0L
    }

    /** [usedBytes] and [freeBytes] of the sessions [root] under [policy]. Blocking: call it off the main thread. */
    fun status(root: File, policy: StoragePolicy = StoragePolicy()): StorageStatus =
        StorageStatus(usedBytes(root), freeBytes(root), policy)
}
