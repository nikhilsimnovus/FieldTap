package com.fieldtap.core.session

import com.fieldtap.core.time.Clock
import com.fieldtap.format.SessionMeta
import java.io.File

/**
 * Where sessions live. [root] is `<getExternalFilesDir(null)>/sessions` (pullable with adb from
 * `/sdcard/Android/data/<applicationId>/files/sessions`); [stateDir] is `<filesDir>/session-state` and
 * holds heartbeats, never session files.
 */
data class SessionPaths(val root: File, val stateDir: File) {
    fun directory(dirName: String): File = File(root, dirName)

    fun heartbeat(dirName: String): File = File(stateDir, "$dirName.heartbeat")
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
 *
 * - [list]: every directory under root whose name matches `SessionDirName.PATTERN`, newest first by
 *   name; others (and dot-directories) ignored. A listing is returned even when session.json is
 *   unreadable, so a damaged session is visible and deletable.
 * - [allocate]: reads the wall clock, makes `SessionDirName.of(now, name)` with `mkdirs`; when that
 *   directory already exists, waits until the next wall-clock second and tries again (never reuses,
 *   never adds a suffix, because the name must match `started_utc` and `name`).
 * - [delete]: recursive; refuses (returns false) for [activeDirName]; also deletes its heartbeat.
 * - [openSessions]: listings whose session.json decodes with `stoppedUtcMs == null`.
 *
 * Tests: temp directories with a ManualClock; collision within a second; unreadable session.json.
 *
 * Owner: workstream `session-core`.
 */
class SessionStore(private val paths: SessionPaths, private val clock: Clock) {
    fun list(): List<SessionListing> = TODO("session-core")

    fun read(dirName: String): SessionListing? = TODO("session-core")

    suspend fun allocate(name: String): AllocatedSession = TODO("session-core")

    fun delete(dirName: String, activeDirName: String?): Boolean = TODO("session-core")

    fun openSessions(): List<SessionListing> = TODO("session-core")
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
    data class CloseInterrupted(
        override val dirName: String,
        val stoppedUtcMs: Long,
        val stoppedBy: String,
        val exitDescription: String?,
    ) : RecoveryAction

    /** It belongs to a recorder running in this process; leave it. */
    data class LeaveRunning(override val dirName: String) : RecoveryAction
}

/**
 * The crash-recovery decision, pure.
 *
 * For each open session other than [activeDirName] (which gets [RecoveryAction.LeaveRunning]):
 * - `stoppedUtcMs`: the heartbeat's wall time; without a heartbeat, `lastWriteWallMs`; never before
 *   `started_utc`.
 * - `stoppedBy`: the exit record whose pid equals the heartbeat's pid; else the earliest record with a
 *   timestamp at or after `stoppedUtcMs - 10 000`; mapped by [ExitReasons.token]; none: `unknown`.
 *   Each exit record explains at most one session.
 *
 * Tests: pid match; timestamp fallback; no heartbeat; reboot (no records -> unknown); active session.
 *
 * Owner: workstream `session-core`.
 */
object RecoveryPlanner {
    fun plan(open: List<OpenSession>, exits: List<ExitRecord>, activeDirName: String?): List<RecoveryAction> =
        TODO("session-core")
}

/**
 * Applies a recovery decision to the files.
 *
 * [findOpen] gathers [OpenSession]s from the store and heartbeat files. [close], in this order:
 * 1. [CsvRepair.truncateToLastLineEnd] on every CSV (a torn last row crashes the report);
 * 2. append a `session_interrupted` event at `stoppedUtcMs` with cause `stoppedBy`
 *    ([SessionEvents.sessionInterrupted]);
 * 3. rewrite session.json atomically from the last snapshot with `stopped_utc` and `summary.stopped_by`
 *    set (summary and collection stay as the last 60 s snapshot wrote them; cells.csv is kept as is);
 * 4. delete the heartbeat.
 * Closing twice is harmless: a session already closed is skipped.
 *
 * Owner: workstream `session-core`.
 */
class SessionRecovery(private val store: SessionStore, private val paths: SessionPaths) {
    fun findOpen(): List<OpenSession> = TODO("session-core")

    fun close(action: RecoveryAction.CloseInterrupted): SessionOutcome = TODO("session-core")
}
