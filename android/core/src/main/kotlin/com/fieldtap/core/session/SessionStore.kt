package com.fieldtap.core.session

import com.fieldtap.core.time.Clock
import com.fieldtap.format.CellsCsv
import com.fieldtap.format.Csv
import com.fieldtap.format.EventKind
import com.fieldtap.format.EventsCsv
import com.fieldtap.format.Schema
import com.fieldtap.format.SessionDirName
import com.fieldtap.format.SessionFile
import com.fieldtap.format.SessionFormat
import com.fieldtap.format.SessionJson
import com.fieldtap.format.SessionJsonException
import com.fieldtap.format.SessionMeta
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.delay

/**
 * Where sessions live. [root] is `<getExternalFilesDir(null)>/sessions` (pullable with adb from
 * `/sdcard/Android/data/<applicationId>/files/sessions`); [stateDir] is `<filesDir>/session-state` and
 * holds heartbeats, never session files.
 */
data class SessionPaths(val root: File, val stateDir: File) {
    fun directory(dirName: String): File = File(root, dirName)

    fun heartbeat(dirName: String): File = File(stateDir, dirName + HEARTBEAT_SUFFIX)

    companion object {
        /** Heartbeat files are `<dirName>.heartbeat`. */
        const val HEARTBEAT_SUFFIX: String = ".heartbeat"
    }
}

/** One session directory as the sessions list sees it. */
data class SessionListing(
    val dirName: String,
    val directory: File,
    /** Null when session.json is missing or does not decode; then [error] says why. */
    val meta: SessionMeta?,
    val error: String?,
    val sizeBytes: Long,
)

/** A freshly allocated, empty session directory. */
data class AllocatedSession(
    val dirName: String,
    val directory: File,
    /** Wall clock at allocation: `started_utc`, and the time in the directory name. */
    val startedUtcMs: Long,
)

/**
 * The session directories. No database: the list is the directories and their session.json files.
 * Blocking file IO: call it off the main thread.
 *
 * - [list]: every directory under root whose name matches `SessionDirName.PATTERN`, newest first by
 *   name; others (and dot-directories) ignored. A listing is returned even when session.json is
 *   unreadable, so a damaged session is visible and deletable.
 * - [read]: one listing, or null when [read]'s name is not a session directory name or the directory
 *   does not exist. A session.json above 16 MiB is reported as an error, never read.
 * - [allocate]: reads the wall clock, makes `SessionDirName.of(now, name)` with `mkdir` (the sessions
 *   root with `mkdirs`); when that directory already exists, waits until the next wall-clock second and
 *   tries again (never reuses, never adds a suffix, because the name must match `started_utc` and
 *   `name`). Throws IOException when the directory cannot be created.
 * - [delete]: recursive, never following symbolic links; refuses (returns false) for [activeDirName] and
 *   for names that are not session directory names; also deletes its heartbeat. True when the directory
 *   is gone afterwards.
 * - [openSessions]: listings whose session.json decodes with `stoppedUtcMs == null`.
 *
 * Owner: workstream `session-core`.
 */
class SessionStore(private val paths: SessionPaths, private val clock: Clock) {
    fun list(): List<SessionListing> {
        val children = paths.root.listFiles() ?: return emptyList()
        return children
            .filter { it.isDirectory && isSessionDirName(it.name) }
            .map { it.name }
            .sortedDescending()
            .mapNotNull { read(it) }
    }

    fun read(dirName: String): SessionListing? {
        if (!isSessionDirName(dirName)) return null
        val directory = paths.directory(dirName)
        if (!directory.isDirectory) return null
        val result = readMeta(directory)
        return SessionListing(dirName, directory, result.meta, result.error, StorageUsage.usedBytes(directory))
    }

    suspend fun allocate(name: String): AllocatedSession {
        while (true) {
            val root = paths.root
            if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
                throw IOException("Cannot create the sessions directory")
            }
            val now = clock.wallMillis()
            val dirName = SessionDirName.of(now, name)
            val directory = paths.directory(dirName)
            if (directory.mkdir()) return AllocatedSession(dirName, directory, now)
            if (!directory.exists()) throw IOException("Cannot create the session directory $dirName")
            delay(MILLIS_PER_SECOND - Math.floorMod(now, MILLIS_PER_SECOND))
        }
    }

    fun delete(dirName: String, activeDirName: String?): Boolean {
        if (dirName == activeDirName) return false
        if (!isSessionDirName(dirName)) return false
        deleteHeartbeat(paths, dirName)
        val directory = paths.directory(dirName)
        if (!directory.exists()) return false
        deleteTree(directory)
        return !directory.exists()
    }

    fun openSessions(): List<SessionListing> =
        list().filter { listing -> listing.meta?.let { it.stoppedUtcMs == null } ?: false }

    private fun readMeta(directory: File): MetaRead {
        val file = File(directory, SessionFile.SESSION_JSON.fileName)
        if (!file.isFile) return MetaRead(null, "session.json is missing")
        if (file.length() > MAX_SESSION_JSON_BYTES) return MetaRead(null, "session.json is larger than 16 MiB")
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return MetaRead(null, "session.json could not be read")
        }
        return try {
            MetaRead(SessionJson.decode(text), null)
        } catch (e: SessionJsonException) {
            MetaRead(null, "session.json is not valid: ${e.message ?: "unknown problem"}".take(MAX_ERROR_CHARS))
        } catch (e: RuntimeException) {
            // SessionJson promises SessionJsonException, but bytes from a damaged file must never take the
            // whole sessions list down with them: the session stays listed, and deletable, with its problem.
            MetaRead(null, "session.json could not be decoded")
        }
    }

    private data class MetaRead(val meta: SessionMeta?, val error: String?)

    private companion object {
        const val MILLIS_PER_SECOND: Long = 1_000
        const val MAX_SESSION_JSON_BYTES: Long = 16L * 1024 * 1024
        const val MAX_ERROR_CHARS: Int = 200
    }
}

/** An open session found at launch. */
data class OpenSession(
    val listing: SessionListing,
    val heartbeat: HeartbeatRecord?,
    /** Newest `lastModified` among the session's files, Unix ms. */
    val lastWriteWallMs: Long,
)

sealed interface RecoveryAction {
    val dirName: String

    /** Close it: stopped at [stoppedUtcMs] by [stoppedBy]. */
    sealed interface Close : RecoveryAction {
        val stoppedUtcMs: Long
        val stoppedBy: String
    }

    /** Android stopped the process that recorded it: closed with a `session_interrupted` event. */
    data class CloseInterrupted(
        override val dirName: String,
        override val stoppedUtcMs: Long,
        override val stoppedBy: String,
        val exitDescription: String?,
    ) : Close

    /**
     * The app stopped it (its heartbeat carries the stop token) but could not write its final session.json, for
     * example because storage was full: closed as that stop, with no `session_interrupted` event, because Android
     * stopped nothing.
     */
    data class CloseStopped(
        override val dirName: String,
        override val stoppedUtcMs: Long,
        override val stoppedBy: String,
    ) : Close

    /** It belongs to a recorder running in this process; leave it. */
    data class LeaveRunning(override val dirName: String) : RecoveryAction
}

/**
 * The crash-recovery decision, pure.
 *
 * For each open session other than [activeDirName] (which gets [RecoveryAction.LeaveRunning]):
 * - `stoppedUtcMs` ([stopTime]): the heartbeat's wall time; without a heartbeat, `lastWriteWallMs`;
 *   never before `started_utc`.
 * - A heartbeat that carries a stop token ([HeartbeatRecord.stoppedBy]) gives [RecoveryAction.CloseStopped] with
 *   that token: the app stopped the session and knew why. It takes no exit record, so the death of a later process
 *   with the same pid can never be written as the reason.
 * - Otherwise `stoppedBy`: the exit record whose pid equals the heartbeat's pid; else the earliest record with a
 *   timestamp at or after `stoppedUtcMs - 10 000`; mapped by [ExitReasons.token]; none: `unknown`.
 *   Each exit record explains at most one session.
 * - A pid match must also be at or after `stoppedUtcMs - 10 000`, and the earliest such record wins:
 *   Android reuses pids, and a process that wrote a heartbeat cannot have died long before it. Pid
 *   matches are assigned first, newest stop first; the time fallback then goes oldest stop first.
 * - Actions come back in the order of [open].
 *
 * Owner: workstream `session-core`.
 */
object RecoveryPlanner {
    /** How far an exit record's timestamp may lie before the session's stop time. */
    const val EXIT_MATCH_SLACK_MS: Long = 10_000

    fun plan(open: List<OpenSession>, exits: List<ExitRecord>, activeDirName: String?): List<RecoveryAction> {
        val closing = open.filter { it.listing.dirName != activeDirName && it.heartbeat?.stoppedBy == null }
        val stopTimes = HashMap<String, Long>()
        for (session in closing) stopTimes[session.listing.dirName] = stopTime(session)
        val used = BooleanArray(exits.size)
        val explained = HashMap<String, ExitRecord>()

        for (session in closing.sortedByDescending { stopTimes.getValue(it.listing.dirName) }) {
            val pid = session.heartbeat?.pid ?: continue
            val dirName = session.listing.dirName
            val index = earliestUnused(exits, used, stopTimes.getValue(dirName) - EXIT_MATCH_SLACK_MS) { it.pid == pid }
            if (index >= 0) {
                used[index] = true
                explained[dirName] = exits[index]
            }
        }
        for (session in closing.sortedBy { stopTimes.getValue(it.listing.dirName) }) {
            val dirName = session.listing.dirName
            if (dirName in explained) continue
            val index = earliestUnused(exits, used, stopTimes.getValue(dirName) - EXIT_MATCH_SLACK_MS) { true }
            if (index >= 0) {
                used[index] = true
                explained[dirName] = exits[index]
            }
        }

        return open.map<OpenSession, RecoveryAction> { session ->
            val dirName = session.listing.dirName
            val stoppedBy = session.heartbeat?.stoppedBy
            when {
                dirName == activeDirName -> RecoveryAction.LeaveRunning(dirName)
                stoppedBy != null -> RecoveryAction.CloseStopped(dirName, stopTime(session), stoppedBy)
                else -> {
                    val record = explained[dirName]
                    RecoveryAction.CloseInterrupted(
                        dirName = dirName,
                        stoppedUtcMs = stopTimes.getValue(dirName),
                        stoppedBy = record?.let { ExitReasons.token(it.reason) } ?: ExitReasons.UNKNOWN,
                        exitDescription = record?.description,
                    )
                }
            }
        }
    }

    /** The heartbeat's wall time, else the newest file time; never before `started_utc`. */
    fun stopTime(session: OpenSession): Long {
        val observed = session.heartbeat?.wallMs ?: session.lastWriteWallMs
        val started = session.listing.meta?.startedUtcMs ?: return observed
        return maxOf(observed, started)
    }

    private inline fun earliestUnused(
        exits: List<ExitRecord>,
        used: BooleanArray,
        notBeforeMs: Long,
        matches: (ExitRecord) -> Boolean,
    ): Int {
        var best = -1
        for (i in exits.indices) {
            val record = exits[i]
            if (used[i] || record.timestampWallMs < notBeforeMs || !matches(record)) continue
            if (best < 0 || record.timestampWallMs < exits[best].timestampWallMs) best = i
        }
        return best
    }
}

/**
 * Applies a recovery decision to the files. Blocking file IO: call it off the main thread.
 *
 * [findOpen] gathers [OpenSession]s from the store and heartbeat files. It also deletes heartbeat files
 * whose session directory is gone or already closed (left by a process that died between writing its
 * final session.json and deleting its heartbeat); a heartbeat of a session whose session.json cannot be
 * read is kept.
 *
 * [close], in this order:
 * 1. [CsvRepair.truncateToLastLineEnd] on every CSV (a torn last row crashes the report); a CSV left
 *    empty, or missing, gets its header line back;
 * 2. remove stale `.tmp` files and rebuild `summary.plmns`, cells.csv and `collection` from the repaired CSV files
 *    ([SessionRebuild]), so they agree with the rows the session holds and not with a snapshot up to 60 s older
 *    (empty when the process died in its first minute). When the rows cannot be rebuilt, whether a file cannot be
 *    read or the phone lacks the memory, the last snapshot's values stay. `privacy.zone_pauses` becomes the larger of
 *    the snapshot's count and the `Logging paused in a privacy zone` events;
 * 3. the stop time is `stoppedUtcMs`, but never before `started_utc` nor before the newest time a row carries (a wall
 *    clock set back mid-session); for [RecoveryAction.CloseInterrupted] only, append a `session_interrupted` event at
 *    that time with cause `stoppedBy` ([SessionEvents.sessionInterrupted]);
 * 4. write cells.csv, then session.json, atomically, with `stopped_utc` and `summary.stopped_by` set;
 * 5. delete the heartbeat.
 * Closing twice is harmless: a session already closed is skipped. When an earlier attempt died after
 * step 2, the `session_interrupted` event already at the end of events.csv is reused, time and cause, so
 * the event is never written twice and `stopped_utc` always equals its time. Throws IOException when the
 * session does not exist, its session.json cannot be read, or a file cannot be written; the session then
 * stays open for the next attempt.
 *
 * [closeStoppedSessions] closes the sessions [RecoveryAction.CloseStopped] applies to without waiting for the next
 * launch: their heartbeat says the app stopped them, so nothing depends on how the process ends.
 *
 * Owner: workstream `session-core`.
 */
class SessionRecovery(private val store: SessionStore, private val paths: SessionPaths) {
    fun findOpen(): List<OpenSession> {
        val open = store.openSessions()
        removeStaleHeartbeats(open.mapTo(HashSet()) { it.dirName })
        return open.map { listing ->
            OpenSession(listing, readHeartbeat(listing.dirName), lastWriteWallMs(listing.directory))
        }
    }

    /**
     * Closes every open session other than [activeDirName] whose heartbeat carries a stop token: the app stopped it
     * but could not write its final session.json. Only heartbeat files are read unless one qualifies, so this is
     * cheap enough to run whenever sessions are listed. A session that still cannot be written stays open for the
     * next call. Returns the sessions closed.
     */
    fun closeStoppedSessions(activeDirName: String?): List<SessionOutcome> {
        val beats = paths.stateDir.listFiles() ?: return emptyList()
        val outcomes = ArrayList<SessionOutcome>()
        for (file in beats) {
            if (!file.name.endsWith(SessionPaths.HEARTBEAT_SUFFIX)) continue
            val dirName = file.name.removeSuffix(SessionPaths.HEARTBEAT_SUFFIX)
            if (dirName == activeDirName || !isSessionDirName(dirName)) continue
            val heartbeat = readHeartbeat(dirName) ?: continue
            val stoppedBy = heartbeat.stoppedBy ?: continue
            val listing = store.read(dirName) ?: continue
            val meta = listing.meta ?: continue
            if (meta.stoppedUtcMs != null) {
                deleteHeartbeat(paths, dirName)
                continue
            }
            val session = OpenSession(listing, heartbeat, lastWriteWallMs(listing.directory))
            try {
                outcomes += close(RecoveryAction.CloseStopped(dirName, RecoveryPlanner.stopTime(session), stoppedBy))
            } catch (e: IOException) {
                // Still not writable, for example storage is still full: it stays open for the next call.
            }
        }
        return outcomes
    }

    fun close(action: RecoveryAction.Close): SessionOutcome {
        val listing = store.read(action.dirName) ?: throw IOException("Session ${action.dirName} does not exist")
        val meta = listing.meta
            ?: throw IOException("Session ${action.dirName} cannot be closed: ${listing.error}")
        val alreadyStopped = meta.stoppedUtcMs
        if (alreadyStopped != null) {
            deleteHeartbeat(paths, action.dirName)
            return SessionOutcome(
                dirName = action.dirName,
                startedUtcMs = meta.startedUtcMs,
                stoppedUtcMs = alreadyStopped,
                stoppedBy = meta.summary.stoppedBy,
                interrupted = meta.summary.stoppedBy !in APP_STOP_TOKENS,
                freshSamples = meta.collection.freshSamples,
                name = meta.name,
            )
        }
        val directory = listing.directory

        for (file in SessionFile.CSV) {
            CsvRepair.repairWithHeader(File(directory, file.fileName), csvHeaderLine(file))
        }

        for (file in listOf(SessionFile.SESSION_JSON, SessionFile.CELLS)) {
            File(directory, file.fileName + AtomicFiles.TMP_SUFFIX).delete()
        }
        val derived = rebuild(directory, meta.startedUtcMs)
        // A wall clock set back during the session leaves rows later than the heartbeat: the session ends after its newest row.
        val newestRowUtcMs = derived?.newestRowUtcMs ?: Long.MIN_VALUE

        val stoppedUtcMs: Long
        val stoppedBy: String
        when (action) {
            is RecoveryAction.CloseInterrupted -> {
                val events = File(directory, SessionFile.EVENTS.fileName)
                val earlier = lastInterruptedEvent(events)
                if (earlier != null) {
                    stoppedUtcMs = earlier.timeUtcMs
                    stoppedBy = earlier.cause
                } else {
                    stoppedUtcMs = maxOf(action.stoppedUtcMs, meta.startedUtcMs, newestRowUtcMs)
                    stoppedBy = ExitReasons.tokenOrUnknown(action.stoppedBy)
                    val row = SessionEvents.sessionInterrupted(stoppedUtcMs, stoppedBy, action.exitDescription)
                    appendAndSync(events, EventsCsv.encode(row))
                }
            }

            is RecoveryAction.CloseStopped -> {
                stoppedUtcMs = maxOf(action.stoppedUtcMs, meta.startedUtcMs, newestRowUtcMs)
                stoppedBy = ExitReasons.tokenOrUnknown(action.stoppedBy)
            }
        }

        if (derived != null) {
            val cells = StringBuilder(CellsCsv.headerLine)
            for (row in derived.cells) cells.append(CellsCsv.encode(row))
            AtomicFiles.write(File(directory, SessionFile.CELLS.fileName), cells.toString().toByteArray(Charsets.UTF_8))
        }
        val closed = meta.copy(
            stoppedUtcMs = stoppedUtcMs,
            summary = meta.summary.copy(stoppedBy = stoppedBy, plmns = derived?.plmns ?: meta.summary.plmns),
            collection = derived?.collection ?: meta.collection,
            // A pause logged after the last snapshot is in events.csv; the snapshot may count one the events do not show.
            privacy = meta.privacy.copy(zonePauses = maxOf(meta.privacy.zonePauses, derived?.zonePauseEvents ?: 0)),
        )
        AtomicFiles.write(
            File(directory, SessionFile.SESSION_JSON.fileName),
            SessionJson.encode(closed).toByteArray(Charsets.UTF_8),
        )

        deleteHeartbeat(paths, action.dirName)
        return SessionOutcome(
            dirName = action.dirName,
            startedUtcMs = meta.startedUtcMs,
            stoppedUtcMs = stoppedUtcMs,
            stoppedBy = stoppedBy,
            interrupted = action is RecoveryAction.CloseInterrupted,
            freshSamples = closed.collection.freshSamples,
            name = meta.name,
        )
    }

    /**
     * [SessionRebuild.derive], or null when the rows cannot be rebuilt, whatever the reason: unreadable files, rows this
     * class cannot make sense of, or a device without the memory for it. The last snapshot's values are better than a
     * session that is never closed.
     */
    private fun rebuild(directory: File, startedUtcMs: Long): SessionRebuild.Derived? =
        try {
            SessionRebuild.derive(directory, startedUtcMs)
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: StackOverflowError) {
            null
        }

    private fun readHeartbeat(dirName: String): HeartbeatRecord? {
        val file = paths.heartbeat(dirName)
        if (!file.isFile || file.length() > MAX_HEARTBEAT_BYTES) return null
        return try {
            HeartbeatRecord.decode(file.readText(Charsets.US_ASCII))
        } catch (e: IOException) {
            null
        }
    }

    private fun lastWriteWallMs(directory: File): Long =
        SessionFile.entries.maxOf { File(directory, it.fileName).lastModified() }

    private fun removeStaleHeartbeats(openDirNames: Set<String>) {
        val files = paths.stateDir.listFiles() ?: return
        for (file in files) {
            val name = file.name
            val dirName = when {
                name.endsWith(HEARTBEAT_TMP_SUFFIX) -> name.removeSuffix(HEARTBEAT_TMP_SUFFIX)
                name.endsWith(SessionPaths.HEARTBEAT_SUFFIX) -> name.removeSuffix(SessionPaths.HEARTBEAT_SUFFIX)
                else -> continue
            }
            if (dirName in openDirNames) continue
            val listing = store.read(dirName)
            val closedOrGone = listing == null || listing.meta?.stoppedUtcMs != null
            if (closedOrGone) file.delete()
        }
    }

    /** The `session_interrupted` event that ends events.csv, if one does. */
    private fun lastInterruptedEvent(events: File): InterruptedEvent? {
        val record = lastRecord(events) ?: return null
        val fields = try {
            Csv.parseRecord(record)
        } catch (e: RuntimeException) {
            // Bytes that do not parse as a record are not an event this class wrote.
            return null
        }
        if (fields.size != Schema.EVENTS_HEADER.size) return null
        if (fields[KIND_INDEX] != EventKind.SESSION_INTERRUPTED.wire) return null
        val time = SessionFormat.parseUtc(fields[TIME_INDEX]) ?: return null
        return InterruptedEvent(time, ExitReasons.tokenOrUnknown(fields[CAUSE_INDEX]))
    }

    private fun lastRecord(file: File): String? {
        if (!file.isFile) return null
        return RandomAccessFile(file, "r").use { raf -> lastRecordOf(raf) }
    }

    /** The last CR LF terminated record of [raf], without its line ending, if it fits in the tail read. */
    private fun lastRecordOf(raf: RandomAccessFile): String? {
        val length = raf.length()
        if (length < 2) return null
        val count = minOf(length, TAIL_BYTES.toLong()).toInt()
        val bytes = ByteArray(count)
        raf.seek(length - count)
        raf.readFully(bytes)
        if (bytes[count - 2] != CR || bytes[count - 1] != LF) return null
        val end = count - 2
        var start = -1
        for (i in end - 2 downTo 0) {
            if (bytes[i] == CR && bytes[i + 1] == LF) {
                start = i + 2
                break
            }
        }
        if (start < 0) {
            if (count.toLong() < length) return null
            start = 0
        }
        return String(bytes, start, end - start, Charsets.UTF_8)
    }

    private fun appendAndSync(file: File, text: String) {
        FileOutputStream(file, true).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    private data class InterruptedEvent(val timeUtcMs: Long, val cause: String)

    private companion object {
        const val MAX_HEARTBEAT_BYTES: Long = 1_024
        const val TAIL_BYTES: Int = 64 * 1024
        const val CR: Byte = 0x0D
        const val LF: Byte = 0x0A
        val HEARTBEAT_TMP_SUFFIX: String = SessionPaths.HEARTBEAT_SUFFIX + AtomicFiles.TMP_SUFFIX
        val TIME_INDEX: Int = Schema.EVENTS_HEADER.indexOf("time_utc")
        val KIND_INDEX: Int = Schema.EVENTS_HEADER.indexOf("kind")
        val CAUSE_INDEX: Int = Schema.EVENTS_HEADER.indexOf("cause")
        val APP_STOP_TOKENS: Set<String> = StopCause.entries.mapTo(HashSet()) { it.token }
    }
}

/** True for a name `SessionStore` treats as a session directory. */
internal fun isSessionDirName(name: String): Boolean =
    !name.startsWith(".") && SessionDirName.PATTERN.matches(name)

/** Deletes a session's heartbeat and its temporary file, if present. */
internal fun deleteHeartbeat(paths: SessionPaths, dirName: String) {
    val heartbeat = paths.heartbeat(dirName)
    heartbeat.delete()
    File(heartbeat.path + AtomicFiles.TMP_SUFFIX).delete()
}

/** Deletes [root] and everything below it without following symbolic links; failures are left in place. */
private fun deleteTree(root: File) {
    try {
        Files.walkFileTree(
            root.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    deleteQuietly(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    deleteQuietly(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    deleteQuietly(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    } catch (e: IOException) {
        // The caller checks whether the directory is gone.
    }
}

private fun deleteQuietly(path: Path) {
    try {
        Files.deleteIfExists(path)
    } catch (e: IOException) {
        // Left in place; the caller checks whether the directory is gone.
    }
}
