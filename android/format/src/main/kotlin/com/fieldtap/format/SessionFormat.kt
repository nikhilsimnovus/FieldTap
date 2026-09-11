package com.fieldtap.format

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * The session directory contract shared with the Python report: `fieldtap-session/1`.
 * Authority, in order: `schema/columns.json`, `docs/SESSION-FORMAT.md`, the golden session in
 * `tests/fixtures/android_session/`. Where this module and those disagree, this module is wrong.
 *
 * Owner: workstream `format`.
 */
object SessionFormat {
    /** `format` in session.json and in `schema/columns.json`. */
    const val ID: String = "fieldtap-session/1"

    /** `timestamps.utc_kotlin_pattern` in columns.json; guarded by SchemaDriftTest. */
    internal const val UTC_KOTLIN_PATTERN: String = "yyyy-MM-dd'T'HH:mm:ss.SSSxxx"

    private const val MILLIS_PER_SECOND: Long = 1_000L
    private const val NANOS_PER_MILLI: Int = 1_000_000

    // Never Instant.toString(): its trailing "Z" is rejected by Python before 3.11, and a
    // time with no offset crashes the report. Locale.ROOT keeps the text independent of the
    // phone's locale; for an Instant the formatter uses the ISO calendar and ASCII digits.
    private val utcFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern(UTC_KOTLIN_PATTERN, Locale.ROOT).withZone(ZoneOffset.UTC)

    // The contract form, and the same with a trailing Z for tolerance. Explicit [0-9] classes, so
    // only ASCII digits match.
    private val utcText: Regex =
        Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})\\.([0-9]{3})(\\+00:00|Z)")

    /** A `*_utc` value, exactly `yyyy-MM-ddTHH:mm:ss.SSS+00:00`. */
    fun utc(instant: Instant): String = utcFormatter.format(instant)

    /** A `*_utc` value for Unix milliseconds. */
    fun utc(epochMillis: Long): String = utcFormatter.format(Instant.ofEpochMilli(epochMillis))

    /**
     * `time_epoch`: Unix seconds with exactly three decimals, built from integer milliseconds as
     * Python's `"%d.%03d" % divmod(ms, 1000)`, never through a double. `1789050600400` ->
     * `1789050600.400`. Like `divmod`, the division floors, so `-1` gives `-1.999`; the contract's
     * range (2000 to 2100) never has a negative value.
     */
    fun timeEpoch(epochMillis: Long): String {
        val seconds = epochMillis.floorDiv(MILLIS_PER_SECOND)
        val millis = epochMillis.mod(MILLIS_PER_SECOND)
        return seconds.toString() + "." + millis.toString().padStart(3, '0')
    }

    /**
     * Reads a `*_utc` value back to Unix milliseconds: the exact contract form, and, for tolerance,
     * a trailing `Z`. Null for anything else. Used when decoding session.json.
     *
     * "Anything else" includes a missing or shorter fraction, another offset, surrounding white
     * space, non-ASCII digits and impossible dates such as 2026-02-30 or hour 24.
     */
    fun parseUtc(text: String): Long? {
        val groups = utcText.matchEntire(text)?.groupValues ?: return null
        return try {
            LocalDateTime.of(
                groups[1].toInt(),
                groups[2].toInt(),
                groups[3].toInt(),
                groups[4].toInt(),
                groups[5].toInt(),
                groups[6].toInt(),
                groups[7].toInt() * NANOS_PER_MILLI,
            ).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (e: DateTimeException) {
            null
        }
    }
}

/** The `capabilities` object in session.json. The app never sees layer 3. */
@Serializable
data class Capabilities(val layer3: Boolean)
