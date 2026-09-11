package com.fieldtap.core.session

import com.fieldtap.format.CellInfoRow
import com.fieldtap.format.CellRow
import com.fieldtap.format.EventRow
import com.fieldtap.format.KpiRow
import com.fieldtap.format.SessionMeta
import com.fieldtap.format.TrackRow
import com.fieldtap.format.TrafficRow
import java.io.Closeable
import java.io.File

/**
 * All file IO of one session. Not thread-safe: every call happens on the session's single
 * dispatcher ([SessionDispatchers.newSessionDispatcher]), which is the only thread that touches the
 * session directory while it records.
 *
 * Contract of [FileSessionFiles]:
 * - [create] makes the six CSVs with their header lines (via the :format encoders) and the first
 *   session.json. It fails if any of them already exists: a directory is never reused.
 * - `append*` buffer an encoded record in the file's append-only stream; nothing is ever rewritten.
 * - [flush] hands buffered bytes to the OS (every second); [sync] calls `FileDescriptor.sync()` on each
 *   CSV (every 5 s).
 * - [writeSnapshot] rewrites cells.csv and session.json atomically ([AtomicFiles.write]); session.json
 *   last.
 * - [writeHeartbeat] overwrites the heartbeat file; [close] flushes, syncs and closes every stream and
 *   deletes the heartbeat. Close is idempotent.
 * - An IOException propagates to the recorder, which stops the session with the cause it can.
 *
 * Owner: workstream `session-core`.
 */
interface SessionFiles : Closeable {
    val directory: File

    fun create(meta: SessionMeta)

    fun appendKpi(row: KpiRow)

    fun appendCellInfo(row: CellInfoRow)

    fun appendTrack(row: TrackRow)

    fun appendEvent(row: EventRow)

    fun appendTraffic(row: TrafficRow)

    fun writeSnapshot(meta: SessionMeta, cells: List<CellRow>)

    fun flush()

    fun sync()

    fun writeHeartbeat(record: HeartbeatRecord)
}

/** The production [SessionFiles]. Owner: workstream `session-core`. */
class FileSessionFiles(
    override val directory: File,
    private val heartbeatFile: File,
) : SessionFiles {
    override fun create(meta: SessionMeta): Unit = TODO("session-core")

    override fun appendKpi(row: KpiRow): Unit = TODO("session-core")

    override fun appendCellInfo(row: CellInfoRow): Unit = TODO("session-core")

    override fun appendTrack(row: TrackRow): Unit = TODO("session-core")

    override fun appendEvent(row: EventRow): Unit = TODO("session-core")

    override fun appendTraffic(row: TrafficRow): Unit = TODO("session-core")

    override fun writeSnapshot(meta: SessionMeta, cells: List<CellRow>): Unit = TODO("session-core")

    override fun flush(): Unit = TODO("session-core")

    override fun sync(): Unit = TODO("session-core")

    override fun writeHeartbeat(record: HeartbeatRecord): Unit = TODO("session-core")

    override fun close(): Unit = TODO("session-core")
}

/** Owner: workstream `session-core`. */
object AtomicFiles {
    /**
     * Writes [bytes] to `<target>.tmp` in the same directory, syncs it, then renames it over [target]
     * (`Files.move` with ATOMIC_MOVE and REPLACE_EXISTING). A crash leaves the old file or the new one,
     * never a torn one; a stale `.tmp` is overwritten next time and never exported.
     */
    fun write(target: File, bytes: ByteArray): Unit = TODO("session-core")
}

/** Owner: workstream `session-core`. */
object CsvRepair {
    /**
     * Cuts [file] back to the end of its last complete record (its last CR LF), syncs it and returns
     * the number of bytes removed. A file without any CR LF is cut to zero length and its header
     * rewritten by the caller.
     */
    fun truncateToLastLineEnd(file: File): Long = TODO("session-core")
}
