package com.fieldtap.format

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

/**
 * The session directory contract shared with the Python report: `fieldtap-session/1`.
 * Authority, in order: `schema/columns.json`, `docs/SESSION-FORMAT.md`, the golden session in
 * `tests/fixtures/android_session/`. Where this module and those disagree, this module is wrong.
 *
 * Owner: workstream `format`.
 */
object SessionFormat {
    const val ID: String = "fieldtap-session/1"

    // Never Instant.toString(): its trailing "Z" is rejected by Python before 3.11, and a
    // time with no offset crashes the report.
    private val utcFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx").withZone(ZoneOffset.UTC)

    /** A `*_utc` value, exactly `yyyy-MM-ddTHH:mm:ss.SSS+00:00`. */
    fun utc(instant: Instant): String = utcFormatter.format(instant)

    /** A `*_utc` value for Unix milliseconds. */
    fun utc(epochMillis: Long): String = utcFormatter.format(Instant.ofEpochMilli(epochMillis))

    /**
     * `time_epoch`: Unix seconds with exactly three decimals, built from integer milliseconds as
     * Python's `"%d.%03d" % divmod(ms, 1000)`, never through a double. `1789050600400` ->
     * `1789050600.400`.
     */
    fun timeEpoch(epochMillis: Long): String = TODO("format")

    /**
     * Reads a `*_utc` value back to Unix milliseconds: the exact contract form, and, for tolerance,
     * a trailing `Z`. Null for anything else. Used when decoding session.json.
     */
    fun parseUtc(text: String): Long? = TODO("format")
}

/** The `capabilities` object in session.json. The app never sees layer 3. */
@Serializable
data class Capabilities(val layer3: Boolean)
