package com.fieldtap.core.export

import com.fieldtap.format.LocationPrecision
import com.fieldtap.format.SessionFile
import com.fieldtap.format.SessionMeta
import java.io.File

/**
 * Location precision for the export copy. The local session stays full precision; only the copy
 * changes (docs/SESSION-FORMAT.md, "Location precision").
 *
 * - [reduceCsvText] for kpi.csv, cellinfo.csv and track.csv, given the file's text:
 *   `FULL` returns it unchanged; `APPROX_110M` rewrites every non-blank `lat` and `lon` to
 *   `Coordinates.approx110m` written with 7 decimals and leaves every other field's text exactly as
 *   it was; `NONE` blanks `lat` and `lon` in kpi.csv and cellinfo.csv and returns null for track.csv
 *   (the file is left out). Other files are returned unchanged. A torn last line is dropped.
 * - [reduceMeta]: sets `privacy.location_precision`; at `NONE` removes `track` from `files`.
 *
 * Tests: golden files at each precision pass the rules `fieldtap validate` applies (no position more
 * precise than 3 decimals at approx_110m, none at none, no track.csv at none); unrelated bytes equal.
 *
 * Owner: workstream `location-privacy-core`.
 */
object PrecisionReducer {
    fun reduceCsvText(file: SessionFile, text: String, precision: LocationPrecision): String? =
        TODO("location-privacy-core")

    fun reduceMeta(meta: SessionMeta, precision: LocationPrecision): SessionMeta = TODO("location-privacy-core")
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

class ExportException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { SESSION_OPEN, MISSING_SESSION_JSON, TOO_LARGE, IO }
}

/**
 * Builds the export zip (docs/SESSION-FORMAT.md, "The upload bundle").
 *
 * - Refuses a session whose session.json is missing ([ExportException.Reason.MISSING_SESSION_JSON]) or
 *   still open, `stopped_utc` null ([ExportException.Reason.SESSION_OPEN]).
 * - Entries: only the seven names, flat, each at most once, in [SessionFile.BUNDLE] order, files that
 *   are absent skipped (track.csv at `NONE`); never summary.json, report.html, index.html, heartbeat
 *   or temporary files. `ZipOutputStream` with `DEFLATED`, no directory entries, no zip64.
 * - The copy has [PrecisionReducer] applied, and session.json re-encoded by `SessionJson`.
 * - Fails with [ExportException.Reason.TOO_LARGE] above `Schema.MAX_UNCOMPRESSED_BYTES` of entry bytes
 *   or `Schema.MAX_COMPRESSED_BYTES` of zip, deleting the partial zip.
 * - Writes to a temporary name and renames, so a crash never leaves a half zip under the final name.
 *   An existing zip of the same name is replaced.
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
    fun export(sessionDir: File, precision: LocationPrecision, outDir: File): ExportResult =
        TODO("location-privacy-core")
}

/** SHA-256 as 64 lower-case hex digits. Owner: workstream `location-privacy-core`. */
object Sha256 {
    fun hex(bytes: ByteArray): String = TODO("location-privacy-core")

    fun hex(file: File): String = TODO("location-privacy-core")
}
