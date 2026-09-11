package com.fieldtap.core.session

import java.io.File

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
 * 15 `package_state_change`, 16 `package_updated`; any other value `unknown`.
 *
 * Owner: workstream `session-core`.
 */
object ExitReasons {
    /** `summary.stopped_by` while the session is open (`stopped_utc` null). */
    const val RECORDING: String = "recording"

    const val USER: String = "user"
    const val UNKNOWN: String = "unknown"

    fun token(reason: Int): String = TODO("session-core")
}

/**
 * The heartbeat: proof of life every 5 s while recording, in `<stateDir>/<dirName>.heartbeat`
 * (outside the session directory, which holds only the seven files). One line:
 * `v1 <wallMs> <elapsedMs> <pid>\n`. Written by overwrite; a torn or unreadable file decodes to null.
 *
 * Owner: workstream `session-core`.
 */
data class HeartbeatRecord(val wallMs: Long, val elapsedMs: Long, val pid: Int) {
    fun encode(): String = TODO("session-core")

    companion object {
        fun decode(text: String): HeartbeatRecord? = TODO("session-core")
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
 * Which periodic writes are due. [due] returns each action whose period has elapsed since it was
 * last returned (all are first due one period after [startElapsedMs]) and records it as done.
 *
 * Owner: workstream `session-core`.
 */
class WriteSchedule(private val policy: WritePolicy, private val startElapsedMs: Long) {
    fun due(nowElapsedMs: Long): Set<WriteAction> = TODO("session-core")
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
 * - [mustStop]: `usedBytes >= capBytes || freeBytes < minFreeBytes / 4`.
 *
 * Owner: workstream `session-core`.
 */
data class StorageStatus(val usedBytes: Long, val freeBytes: Long, val policy: StoragePolicy) {
    val canStart: Boolean get() = TODO("session-core")

    val mustStop: Boolean get() = TODO("session-core")
}

/** Owner: workstream `session-core`. */
object StorageUsage {
    /** Total bytes of regular files under [root], recursively; 0 when it does not exist. */
    fun usedBytes(root: File): Long = TODO("session-core")

    /** `File.usableSpace` of [root] (or its nearest existing parent). */
    fun freeBytes(root: File): Long = TODO("session-core")
}
