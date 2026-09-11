package com.fieldtap.core.session

import com.fieldtap.format.CellInfoCsv
import com.fieldtap.format.CellInfoRow
import com.fieldtap.format.CellRow
import com.fieldtap.format.CellsCsv
import com.fieldtap.format.EventRow
import com.fieldtap.format.EventsCsv
import com.fieldtap.format.KpiCsv
import com.fieldtap.format.KpiRow
import com.fieldtap.format.SessionFile
import com.fieldtap.format.SessionJson
import com.fieldtap.format.SessionMeta
import com.fieldtap.format.TrackCsv
import com.fieldtap.format.TrackRow
import com.fieldtap.format.TrafficCsv
import com.fieldtap.format.TrafficRow
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * All file IO of one session. Not thread-safe: every call happens on the session's single
 * dispatcher ([SessionDispatchers.newSessionDispatcher]), which is the only thread that touches the
 * session directory while it records.
 *
 * Contract of [FileSessionFiles]:
 * - [create] makes the six CSVs with their header lines (via the :format encoders) and the first
 *   session.json. It fails if any of the seven files already exists: a directory is never reused. A
 *   create that fails part way removes the files it made itself.
 * - `append*` buffer an encoded record for the file's append-only stream; nothing is ever rewritten.
 *   cells.csv has no stream: it is only ever replaced whole by [writeSnapshot].
 * - [flush] hands buffered bytes to the OS (every second); [sync] flushes and calls
 *   `FileDescriptor.sync()` on each append-only CSV (every 5 s).
 * - A CSV only ever grows by whole records: when a write fails part way, the file is cut back to the
 *   end of its last record that was written in full, and the unwritten records stay buffered.
 * - [writeSnapshot] rewrites cells.csv and session.json atomically ([AtomicFiles.write]); session.json
 *   last.
 * - [writeHeartbeat] replaces the heartbeat file atomically, creating its directory. It also works after
 *   [close]: the recorder uses that to leave its stop time behind when the final session.json could not
 *   be written, so launch recovery closes the session at the right time.
 * - [close] flushes, syncs and closes every stream, repairs any CSV whose write failed, and deletes the
 *   heartbeat. Close is idempotent. `append*`, [flush] and [sync] after close throw IllegalStateException.
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
    private var appenders: Map<SessionFile, CsvAppender>? = null
    private var createCalled = false
    private var closed = false

    override fun create(meta: SessionMeta) {
        check(!createCalled) { "create() may be called only once" }
        check(!closed) { "The session files are closed" }
        createCalled = true
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create the session directory ${directory.name}")
        }
        for (file in SessionFile.entries) {
            val target = File(directory, file.fileName)
            if (target.exists()) {
                throw FileAlreadyExistsException(target, reason = "a session directory is never reused")
            }
        }
        val made = ArrayList<File>()
        val opened = LinkedHashMap<SessionFile, CsvAppender>()
        var complete = false
        try {
            for (file in APPEND_ONLY) {
                val target = File(directory, file.fileName)
                createExclusively(target)
                made += target
                val appender = CsvAppender(target)
                opened[file] = appender
                appender.append(csvHeaderLine(file))
            }
            val cells = File(directory, SessionFile.CELLS.fileName)
            createExclusively(cells)
            made += cells
            FileOutputStream(cells).use { out ->
                out.write(CellsCsv.headerLine.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            for (appender in opened.values) appender.sync()
            val sessionJson = File(directory, SessionFile.SESSION_JSON.fileName)
            made += sessionJson
            AtomicFiles.write(sessionJson, SessionJson.encode(meta).toByteArray(Charsets.UTF_8))
            appenders = opened
            complete = true
        } finally {
            if (!complete) {
                for (appender in opened.values) closeQuietly(appender)
                for (file in made) file.delete()
            }
        }
    }

    override fun appendKpi(row: KpiRow) {
        appender(SessionFile.KPI).append(KpiCsv.encode(row))
    }

    override fun appendCellInfo(row: CellInfoRow) {
        appender(SessionFile.CELLINFO).append(CellInfoCsv.encode(row))
    }

    override fun appendTrack(row: TrackRow) {
        appender(SessionFile.TRACK).append(TrackCsv.encode(row))
    }

    override fun appendEvent(row: EventRow) {
        appender(SessionFile.EVENTS).append(EventsCsv.encode(row))
    }

    override fun appendTraffic(row: TrafficRow) {
        appender(SessionFile.TRAFFIC).append(TrafficCsv.encode(row))
    }

    override fun writeSnapshot(meta: SessionMeta, cells: List<CellRow>) {
        val text = StringBuilder(CellsCsv.headerLine)
        for (cell in cells) text.append(CellsCsv.encode(cell))
        AtomicFiles.write(File(directory, SessionFile.CELLS.fileName), text.toString().toByteArray(Charsets.UTF_8))
        AtomicFiles.write(
            File(directory, SessionFile.SESSION_JSON.fileName),
            SessionJson.encode(meta).toByteArray(Charsets.UTF_8),
        )
    }

    override fun flush() {
        forEachAppender { it.flush() }
    }

    override fun sync() {
        forEachAppender { it.sync() }
    }

    override fun writeHeartbeat(record: HeartbeatRecord) {
        val parent = heartbeatFile.absoluteFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Cannot create the heartbeat directory ${parent.name}")
        }
        AtomicFiles.write(heartbeatFile, record.encode().toByteArray(Charsets.US_ASCII))
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: IOException? = null
        try {
            for ((file, appender) in appenders.orEmpty()) {
                try {
                    appender.sync()
                } catch (e: IOException) {
                    failure = failure.plus(e)
                }
                try {
                    appender.close()
                } catch (e: IOException) {
                    failure = failure.plus(e)
                }
                if (appender.failed) {
                    try {
                        CsvRepair.repairWithHeader(appender.file, csvHeaderLine(file))
                    } catch (e: IOException) {
                        failure = failure.plus(e)
                    }
                }
            }
        } finally {
            heartbeatFile.delete()
            File(heartbeatFile.path + AtomicFiles.TMP_SUFFIX).delete()
        }
        failure?.let { throw it }
    }

    private fun appender(file: SessionFile): CsvAppender {
        check(!closed) { "The session files are closed" }
        val open = checkNotNull(appenders) { "create() has not completed" }
        return open.getValue(file)
    }

    /** Runs [action] on every stream even when one fails, then throws the first failure. */
    private inline fun forEachAppender(action: (CsvAppender) -> Unit) {
        check(!closed) { "The session files are closed" }
        val open = checkNotNull(appenders) { "create() has not completed" }
        var failure: IOException? = null
        for (appender in open.values) {
            try {
                action(appender)
            } catch (e: IOException) {
                failure = failure.plus(e)
            }
        }
        failure?.let { throw it }
    }

    private companion object {
        /** The CSVs that grow by appending. cells.csv is replaced whole instead. */
        val APPEND_ONLY: List<SessionFile> =
            listOf(SessionFile.KPI, SessionFile.TRACK, SessionFile.EVENTS, SessionFile.TRAFFIC, SessionFile.CELLINFO)

        fun createExclusively(target: File) {
            if (!target.createNewFile()) {
                throw FileAlreadyExistsException(target, reason = "a session directory is never reused")
            }
        }

        fun closeQuietly(closeable: Closeable) {
            try {
                closeable.close()
            } catch (e: IOException) {
                // Already failing: the first exception is the one that is reported.
            }
        }

        fun IOException?.plus(next: IOException): IOException {
            if (this == null) return next
            addSuppressed(next)
            return this
        }
    }
}

/**
 * One append-only CSV. Records are buffered whole and written with one `write` call, so the bytes that
 * reach the file always end at a record boundary: after a failed write the file is truncated back to the
 * last record written in full, and the buffer is kept for a later attempt.
 */
private class CsvAppender(val file: File) : Closeable {
    private val out = FileOutputStream(file, true)
    private val buffer = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
    private var committedBytes: Long = file.length()

    /** True once a write to this file failed; [FileSessionFiles.close] then repairs the file. */
    var failed: Boolean = false
        private set

    fun append(record: String) {
        val bytes = record.toByteArray(Charsets.UTF_8)
        buffer.write(bytes, 0, bytes.size)
        if (buffer.size() >= EAGER_FLUSH_BYTES) flush()
    }

    fun flush() {
        val size = buffer.size()
        if (size == 0) return
        try {
            buffer.writeTo(out)
        } catch (e: IOException) {
            failed = true
            rollBack()
            throw e
        }
        committedBytes += size
        buffer.reset()
    }

    fun sync() {
        flush()
        out.fd.sync()
    }

    override fun close() {
        try {
            flush()
        } finally {
            out.close()
        }
    }

    private fun rollBack() {
        try {
            out.channel.truncate(committedBytes)
        } catch (e: IOException) {
            // Left for CsvRepair when the files are closed, or for launch recovery.
        }
    }

    private companion object {
        const val INITIAL_BUFFER_BYTES: Int = 16 * 1024
        const val EAGER_FLUSH_BYTES: Int = 256 * 1024
    }
}

/** The header record of a CSV [file], ending in CR LF. */
internal fun csvHeaderLine(file: SessionFile): String = when (file) {
    SessionFile.KPI -> KpiCsv.headerLine
    SessionFile.TRACK -> TrackCsv.headerLine
    SessionFile.EVENTS -> EventsCsv.headerLine
    SessionFile.TRAFFIC -> TrafficCsv.headerLine
    SessionFile.CELLS -> CellsCsv.headerLine
    SessionFile.CELLINFO -> CellInfoCsv.headerLine
    SessionFile.SESSION_JSON -> throw IllegalArgumentException("session.json is not a CSV file")
}

/** Owner: workstream `session-core`. */
object AtomicFiles {
    /** The suffix of the temporary file next to the target. */
    const val TMP_SUFFIX: String = ".tmp"

    /**
     * Writes [bytes] to `<target>.tmp` in the same directory, syncs it, then renames it over [target]
     * (`Files.move` with ATOMIC_MOVE and REPLACE_EXISTING). A crash leaves the old file or the new one,
     * never a torn one; a stale `.tmp` is overwritten next time and never exported. On a file system
     * that refuses ATOMIC_MOVE the rename falls back to REPLACE_EXISTING. On failure the temporary file
     * is removed, [target] is unchanged and the IOException propagates.
     */
    fun write(target: File, bytes: ByteArray) {
        val directory = target.absoluteFile.parentFile
            ?: throw IOException("${target.name} has no parent directory")
        val tmp = File(directory, target.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            if (tmp.isFile) tmp.delete()
            throw e
        }
    }
}

/** Owner: workstream `session-core`. */
object CsvRepair {
    private const val CR: Byte = 0x0D
    private const val LF: Byte = 0x0A
    private const val CHUNK_BYTES: Int = 64 * 1024

    /**
     * Cuts [file] back to the end of its last complete record (its last CR LF), syncs it and returns
     * the number of bytes removed. A file without any CR LF is cut to zero length and its header
     * rewritten by the caller. A CR at the very end without its LF is not a line end. Throws
     * FileNotFoundException when [file] does not exist; it is never created.
     */
    fun truncateToLastLineEnd(file: File): Long {
        if (!file.isFile) throw FileNotFoundException("${file.name} does not exist")
        return RandomAccessFile(file, "rw").use { raf ->
            val length = raf.length()
            val keep = lastLineEnd(raf, length)
            if (keep < length) {
                raf.channel.truncate(keep)
                raf.fd.sync()
            }
            length - keep
        }
    }

    /**
     * [truncateToLastLineEnd], then [headerLine] written back into a file left empty; a missing file is
     * created holding only [headerLine]. Returns the bytes removed.
     */
    internal fun repairWithHeader(file: File, headerLine: String): Long {
        if (!file.exists()) {
            writeHeader(file, headerLine)
            return 0
        }
        val removed = truncateToLastLineEnd(file)
        if (file.length() == 0L) writeHeader(file, headerLine)
        return removed
    }

    private fun writeHeader(file: File, headerLine: String) {
        FileOutputStream(file, true).use { out ->
            out.write(headerLine.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /** The offset just after the last CR LF in the first [length] bytes, or 0. */
    private fun lastLineEnd(raf: RandomAccessFile, length: Long): Long {
        val buffer = ByteArray(CHUNK_BYTES)
        var end = length
        // The first byte of the chunk after the current one, or -1 at the end of the file.
        var following = -1
        while (end > 0) {
            val start = maxOf(0L, end - CHUNK_BYTES)
            val count = (end - start).toInt()
            raf.seek(start)
            raf.readFully(buffer, 0, count)
            for (i in count - 1 downTo 0) {
                val next = if (i + 1 < count) buffer[i + 1].toInt() else following
                if (buffer[i] == CR && next == LF.toInt()) return start + i + 2
            }
            following = buffer[0].toInt()
            end = start
        }
        return 0
    }
}
