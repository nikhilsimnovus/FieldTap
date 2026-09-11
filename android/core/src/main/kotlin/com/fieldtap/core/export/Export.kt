package com.fieldtap.core.export

import com.fieldtap.format.Coordinates
import com.fieldtap.format.LocationPrecision
import com.fieldtap.format.SessionFile
import com.fieldtap.format.SessionJson
import com.fieldtap.format.SessionJsonException
import com.fieldtap.format.SessionMeta
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * Every API used here exists on Android API 31 (the app's minSdk): java.nio.file is API 26, and
 * nothing added in Java 9 or later (InputStream.transferTo, readAllBytes,
 * ByteArrayOutputStream.toString(Charset), HexFormat) is called.
 */

/**
 * Location precision for the export copy. The local session stays full precision; only the copy
 * changes (docs/SESSION-FORMAT.md, "Location precision").
 *
 * - [reduceCsvText] for kpi.csv, cellinfo.csv and track.csv, given the file's text:
 *   `FULL` returns it unchanged; `APPROX_110M` rewrites every non-blank `lat` and `lon` to
 *   `Coordinates.approx110m` written with 7 decimals and leaves every other field's text exactly as
 *   it was; `NONE` blanks `lat` and `lon` in kpi.csv and cellinfo.csv and returns null for track.csv
 *   (the file is left out). Other files are returned unchanged. When a file is rewritten, a torn
 *   last line (bytes after the last line ending) is dropped.
 * - The columns are found by name in the file's own header. Records end at a LF outside quotes, and
 *   each keeps its own line ending, so the output has the input's bytes everywhere but lat and lon.
 * - Rewriting never lets a precise position through: a data row whose field count differs from the
 *   header's is dropped; a position that is not a finite number is blanked (both lat and lon) in
 *   kpi.csv and cellinfo.csv, and its row is dropped from track.csv, where every row needs a position.
 * - [reduceMeta]: sets `privacy.location_precision`; at `NONE` removes `track` from `files`.
 *
 * Tests: golden files at each precision pass the rules `fieldtap validate` applies (no position more
 * precise than 3 decimals at approx_110m, none at none, no track.csv at none); unrelated bytes equal.
 *
 * Owner: workstream `location-privacy-core`.
 */
object PrecisionReducer {
    /** The files that carry `lat` and `lon`. */
    private val POSITIONED: Set<SessionFile> = setOf(SessionFile.KPI, SessionFile.CELLINFO, SessionFile.TRACK)

    fun reduceCsvText(file: SessionFile, text: String, precision: LocationPrecision): String? {
        if (omits(file, precision)) return null
        if (!rewrites(file, precision)) return text
        val input = text.toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream(input.size)
        reduceCsv(file, precision, ByteArrayInputStream(input), output, maxRecordBytes = Int.MAX_VALUE)
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    fun reduceMeta(meta: SessionMeta, precision: LocationPrecision): SessionMeta = meta.copy(
        files = if (precision == LocationPrecision.NONE) meta.files.filter { it != SessionFile.TRACK } else meta.files,
        privacy = meta.privacy.copy(locationPrecision = precision),
    )

    /** True when [file] is left out of a copy at [precision]: track.csv at `none`. */
    fun omits(file: SessionFile, precision: LocationPrecision): Boolean =
        file == SessionFile.TRACK && precision == LocationPrecision.NONE

    /** True when the bytes of [file] change in a copy at [precision]. */
    fun rewrites(file: SessionFile, precision: LocationPrecision): Boolean =
        precision != LocationPrecision.FULL && file in POSITIONED

    /**
     * The streaming form of [reduceCsvText] for a file that [rewrites] and does not [omit][omits]:
     * reads [input] to its end and writes the copy to [output], holding one record at a time.
     *
     * @throws IOException from either stream, or for a record longer than [maxRecordBytes].
     */
    internal fun reduceCsv(
        file: SessionFile,
        precision: LocationPrecision,
        input: InputStream,
        output: OutputStream,
        maxRecordBytes: Int = MAX_RECORD_BYTES,
    ) {
        require(rewrites(file, precision) && !omits(file, precision)) { "$file is not rewritten at $precision" }
        val rewriter = PositionRewriter(file, precision)
        val chunk = ByteArray(BUFFER_BYTES)
        var record = ByteArray(INITIAL_RECORD_BYTES)
        var length = 0
        var inQuotes = false
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            for (i in 0 until read) {
                val b = chunk[i]
                if (length == record.size) {
                    if (record.size >= maxRecordBytes) throw IOException("a record is longer than $maxRecordBytes bytes")
                    record = record.copyOf(minOf(record.size.toLong() * 2, maxRecordBytes.toLong()).toInt())
                }
                record[length++] = b
                if (b == QUOTE) {
                    inQuotes = !inQuotes
                } else if (b == LF && !inQuotes) {
                    rewriter.write(record, length, output)
                    length = 0
                }
            }
        }
        // Whatever follows the last line ending is a torn write and is dropped.
    }

    /** `upload_bundle`-sized files hold short records; this bounds memory on a corrupt file. */
    private const val MAX_RECORD_BYTES: Int = 1_048_576
    private const val INITIAL_RECORD_BYTES: Int = 1_024
    private const val BUFFER_BYTES: Int = 64 * 1_024

    private const val QUOTE: Byte = '"'.code.toByte()
    private const val COMMA: Byte = ','.code.toByte()
    private const val CR: Byte = '\r'.code.toByte()
    private const val LF: Byte = '\n'.code.toByte()

    /** Rewrites the position fields of one CSV file, record by record. Not thread-safe. */
    private class PositionRewriter(private val file: SessionFile, private val precision: LocationPrecision) {
        private var headerFields = -1
        private var positionIndices = IntArray(0)
        private var fieldStarts = IntArray(64)
        private var fieldEnds = IntArray(64)

        /** [record] holds one record of [length] bytes ending in LF; writes its copy, or nothing. */
        fun write(record: ByteArray, length: Int, output: OutputStream) {
            var contentEnd = length - 1
            if (contentEnd > 0 && record[contentEnd - 1] == CR) contentEnd--
            val count = splitFields(record, contentEnd)

            if (headerFields < 0) {
                headerFields = count
                val names = List(count) { unquote(record, fieldStarts[it], fieldEnds[it], Charsets.UTF_8) }
                positionIndices = listOf(names.indexOf("lat"), names.indexOf("lon")).filter { it >= 0 }.sorted().toIntArray()
                output.write(record, 0, length)
                return
            }
            if (positionIndices.isEmpty()) {
                output.write(record, 0, length)
                return
            }
            // A row that does not line up with the header may hold a position in another column.
            if (count != headerFields) return

            val replacements = replacementsFor(record) ?: return
            var cursor = 0
            for ((k, index) in positionIndices.withIndex()) {
                output.write(record, cursor, fieldStarts[index] - cursor)
                output.write(replacements[k])
                cursor = fieldEnds[index]
            }
            output.write(record, cursor, length - cursor)
        }

        /** The new bytes of each position field, or null when the row must be dropped. */
        private fun replacementsFor(record: ByteArray): List<ByteArray>? {
            if (precision == LocationPrecision.NONE) return positionIndices.map { EMPTY }
            val rounded = positionIndices.map { index -> roundedText(record, fieldStarts[index], fieldEnds[index]) }
            if (rounded.all { it != null }) return rounded.map { it!!.toByteArray(Charsets.US_ASCII) }
            return if (file == SessionFile.TRACK) null else positionIndices.map { EMPTY }
        }

        /** "" for a blank field, the approx_110m text for a finite number, null for anything else. */
        private fun roundedText(record: ByteArray, start: Int, end: Int): String? {
            val text = unquote(record, start, end, Charsets.ISO_8859_1)
            if (text.isEmpty()) return ""
            val value = text.toDoubleOrNull() ?: return null
            if (!value.isFinite()) return null
            return Coordinates.format(Coordinates.approx110m(value))
        }

        /** Fills [fieldStarts] and [fieldEnds] for the fields of `record[0, end)`; returns their count. */
        private fun splitFields(record: ByteArray, end: Int): Int {
            var count = 0
            var start = 0
            var inQuotes = false
            for (i in 0 until end) {
                val b = record[i]
                if (b == QUOTE) {
                    inQuotes = !inQuotes
                } else if (b == COMMA && !inQuotes) {
                    addField(count++, start, i)
                    start = i + 1
                }
            }
            addField(count++, start, end)
            return count
        }

        private fun addField(index: Int, start: Int, end: Int) {
            if (index == fieldStarts.size) {
                fieldStarts = fieldStarts.copyOf(index * 2)
                fieldEnds = fieldEnds.copyOf(index * 2)
            }
            fieldStarts[index] = start
            fieldEnds[index] = end
        }

        private fun unquote(record: ByteArray, start: Int, end: Int, charset: java.nio.charset.Charset): String {
            val quoted = end - start >= 2 && record[start] == QUOTE && record[end - 1] == QUOTE
            if (!quoted) return String(record, start, end - start, charset)
            return String(record, start + 1, end - start - 2, charset).replace("\"\"", "\"")
        }

        private companion object {
            val EMPTY = ByteArray(0)
        }
    }
}

/** A built export bundle. */
data class ExportResult(
    /** `<session directory name>.zip` inside the requested output directory. */
    val zip: File,
    /** SHA-256 of the zip file, 64 lower-case hex digits. */
    val sha256: String,
    val bytes: Long,
    val precision: LocationPrecision,
    /** Entry names in zip order. */
    val entries: List<String>,
)

/**
 * Why an export was refused or failed. [message] is a plain sentence for logs; screens choose their
 * wording from [reason]. [Reason.MISSING_SESSION_JSON] also covers a session.json that cannot be read
 * as one.
 */
class ExportException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { SESSION_OPEN, MISSING_SESSION_JSON, TOO_LARGE, IO }
}

/**
 * Builds the export zip (docs/SESSION-FORMAT.md, "The upload bundle").
 *
 * - Refuses a session whose session.json is missing or unreadable
 *   ([ExportException.Reason.MISSING_SESSION_JSON]) or still open, `stopped_utc` null
 *   ([ExportException.Reason.SESSION_OPEN]).
 * - Entries: only the seven names, flat, each at most once, in [SessionFile.BUNDLE] order, files that
 *   are absent skipped (track.csv at `NONE`); never summary.json, report.html, index.html, heartbeat
 *   or temporary files. Symbolic links count as absent. `ZipOutputStream` with `DEFLATED`, no
 *   directory entries, no zip64.
 * - The copy has [PrecisionReducer] applied, and session.json re-encoded by `SessionJson`, with
 *   `files` naming exactly the CSV files in the zip.
 * - Fails with [ExportException.Reason.TOO_LARGE] above `Schema.MAX_UNCOMPRESSED_BYTES` of entry bytes
 *   or `Schema.MAX_COMPRESSED_BYTES` of zip (both limits inclusive), deleting the partial zip.
 * - Writes to a uniquely named temporary file in [export]'s `outDir`, syncs it and renames it, so a
 *   crash never leaves a half zip under the final name. An existing zip of the same name is replaced.
 * - Every entry carries the session's `stopped_utc` as its time, so exporting the same session at the
 *   same precision again gives the same bytes and the same SHA-256.
 * - Blocking IO: call it off the main thread. Other [ExportException.Reason.IO] failures wrap the
 *   [IOException].
 *
 * Tests: build from the golden session, reopen with `java.util.zip.ZipFile`, check names, order,
 * methods, no directories, SHA-256 of the file equals [ExportResult.sha256]; each precision; the
 * size limits with a tiny injected limit.
 *
 * Owner: workstream `location-privacy-core`.
 */
class SessionExporter(
    private val maxUncompressedBytes: Long = com.fieldtap.format.Schema.MAX_UNCOMPRESSED_BYTES,
    private val maxCompressedBytes: Long = com.fieldtap.format.Schema.MAX_COMPRESSED_BYTES,
) {
    init {
        require(maxUncompressedBytes > 0) { "maxUncompressedBytes must be positive: $maxUncompressedBytes" }
        require(maxCompressedBytes > 0) { "maxCompressedBytes must be positive: $maxCompressedBytes" }
    }

    /**
     * Builds `<outDir>/<sessionDir name>.zip`, creating [outDir] when needed.
     *
     * @throws ExportException for every refusal and failure; nothing is left under the final name then.
     * @throws IllegalArgumentException when [outDir] is [sessionDir] itself.
     */
    fun export(sessionDir: File, precision: LocationPrecision, outDir: File): ExportResult {
        val meta = readClosedMeta(sessionDir)
        val included = SessionFile.CSV.filter { file ->
            !PrecisionReducer.omits(file, precision) && isPlainFile(File(sessionDir, file.fileName))
        }
        val exportMeta = PrecisionReducer.reduceMeta(meta, precision).copy(files = included)
        val sessionJson = SessionJson.encode(exportMeta).toByteArray(Charsets.UTF_8)

        val part = try {
            prepareOutDir(outDir, sessionDir)
            File.createTempFile(sessionDir.name + ".", PART_SUFFIX, outDir)
        } catch (e: IOException) {
            throw ExportException(ExportException.Reason.IO, "Could not prepare ${outDir.path}: ${e.message}", e)
        }
        val target = File(outDir, sessionDir.name + ZIP_SUFFIX)
        try {
            val built = writeZip(
                part = part,
                sessionDir = sessionDir,
                included = included,
                precision = precision,
                sessionJson = sessionJson,
                entryTimeMs = checkNotNull(meta.stoppedUtcMs),
            )
            moveReplacing(part, target)
            return ExportResult(
                zip = target,
                sha256 = built.sha256,
                bytes = built.bytes,
                precision = precision,
                entries = built.entries,
            )
        } catch (e: IOException) {
            throw ExportException(ExportException.Reason.IO, "Could not write ${target.name}: ${e.message}", e)
        } finally {
            // Gone after a successful rename; otherwise the partial zip must not stay behind.
            part.delete()
        }
    }

    private fun readClosedMeta(sessionDir: File): SessionMeta {
        val json = File(sessionDir, SessionFile.SESSION_JSON.fileName)
        if (!isPlainFile(json)) {
            throw ExportException(ExportException.Reason.MISSING_SESSION_JSON, "${sessionDir.name} has no session.json")
        }
        if (json.length() > MAX_SESSION_JSON_BYTES) {
            throw ExportException(
                ExportException.Reason.MISSING_SESSION_JSON,
                "session.json of ${sessionDir.name} is larger than $MAX_SESSION_JSON_BYTES bytes",
            )
        }
        val text = try {
            json.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            throw ExportException(ExportException.Reason.IO, "Could not read session.json of ${sessionDir.name}: ${e.message}", e)
        }
        val meta = try {
            SessionJson.decode(text)
        } catch (e: SessionJsonException) {
            throw unreadable(sessionDir, e)
        } catch (e: RuntimeException) {
            // A decoder bug must not crash the share flow; the session is reported unreadable instead.
            throw unreadable(sessionDir, e)
        }
        if (meta.stoppedUtcMs == null) {
            throw ExportException(
                ExportException.Reason.SESSION_OPEN,
                "${sessionDir.name} is still recording; stop it before exporting",
            )
        }
        return meta
    }

    private fun unreadable(sessionDir: File, cause: Exception): ExportException = ExportException(
        ExportException.Reason.MISSING_SESSION_JSON,
        "session.json of ${sessionDir.name} cannot be read: ${cause.message}",
        cause,
    )

    private fun prepareOutDir(outDir: File, sessionDir: File) {
        if (!outDir.isDirectory && !outDir.mkdirs() && !outDir.isDirectory) {
            throw IOException("cannot create the directory")
        }
        require(outDir.canonicalFile != sessionDir.canonicalFile) {
            "The export directory must not be the session directory: ${sessionDir.path}"
        }
    }

    private class Built(val sha256: String, val bytes: Long, val entries: List<String>)

    private fun writeZip(
        part: File,
        sessionDir: File,
        included: List<SessionFile>,
        precision: LocationPrecision,
        sessionJson: ByteArray,
        entryTimeMs: Long,
    ): Built {
        val digest = MessageDigest.getInstance("SHA-256")
        val entries = ArrayList<String>(SessionFile.BUNDLE.size)
        val fileOut = FileOutputStream(part)
        val counted = CountingOutputStream(DigestOutputStream(fileOut, digest))
        fileOut.use { _ ->
            ZipOutputStream(BufferedOutputStream(counted, BUFFER_BYTES)).use { zip ->
                zip.setMethod(ZipOutputStream.DEFLATED)
                zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                val entryBytes = EntryBytes(zip)
                for (file in SessionFile.BUNDLE) {
                    if (file != SessionFile.SESSION_JSON && file !in included) continue
                    val entry = ZipEntry(file.fileName)
                    entry.method = ZipEntry.DEFLATED
                    entry.time = entryTimeMs
                    zip.putNextEntry(entry)
                    when {
                        file == SessionFile.SESSION_JSON -> entryBytes.write(sessionJson)
                        PrecisionReducer.rewrites(file, precision) -> FileInputStream(File(sessionDir, file.fileName)).use { input ->
                            PrecisionReducer.reduceCsv(file, precision, input, entryBytes)
                        }
                        else -> FileInputStream(File(sessionDir, file.fileName)).use { input ->
                            input.copyTo(entryBytes, BUFFER_BYTES)
                        }
                    }
                    zip.closeEntry()
                    entries += file.fileName
                    checkCompressed(counted.count)
                }
                zip.finish()
                zip.flush()
                checkCompressed(counted.count)
                fileOut.fd.sync()
            }
        }
        return Built(sha256 = Sha256.toHex(digest.digest()), bytes = counted.count, entries = entries)
    }

    private fun checkCompressed(bytes: Long) {
        if (bytes > maxCompressedBytes) {
            throw ExportException(
                ExportException.Reason.TOO_LARGE,
                "The zip is larger than $maxCompressedBytes bytes",
            )
        }
    }

    /** Counts the uncompressed bytes of all entries and stops at the limit. Never closes the zip. */
    private inner class EntryBytes(private val zip: OutputStream) : OutputStream() {
        private var total = 0L

        override fun write(b: Int) {
            add(1)
            zip.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            add(len.toLong())
            zip.write(b, off, len)
        }

        override fun close() = Unit

        private fun add(bytes: Long) {
            total += bytes
            if (total > maxUncompressedBytes) {
                throw ExportException(
                    ExportException.Reason.TOO_LARGE,
                    "The session files hold more than $maxUncompressedBytes bytes",
                )
            }
        }
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isPlainFile(file: File): Boolean = try {
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (e: InvalidPathException) {
        false
    }

    private companion object {
        const val ZIP_SUFFIX = ".zip"
        const val PART_SUFFIX = ".zip.part"
        const val BUFFER_BYTES = 64 * 1_024

        /** `session_json.max_bytes` in schema/columns.json (16 MiB). */
        const val MAX_SESSION_JSON_BYTES = 16L * 1_024 * 1_024
    }
}

/** SHA-256 as 64 lower-case hex digits. Owner: workstream `location-privacy-core`. */
object Sha256 {
    fun hex(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Streams [file]; blocking IO. @throws IOException when it cannot be read. */
    fun hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1_024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
    }

    internal fun toHex(digest: ByteArray): String {
        val chars = CharArray(digest.size * 2)
        for ((i, byte) in digest.withIndex()) {
            val v = byte.toInt() and 0xFF
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(chars)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
