package com.fieldtap.format

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

/**
 * The session directory contract shared with the Python report.
 * See docs/APP-PLAN.md, "The session format".
 */
object SessionFormat {
    const val ID: String = "fieldtap-session/1"

    // Never Instant.toString(): its trailing "Z" is rejected by Python before 3.11, and a
    // time with no offset crashes the report.
    private val utcFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx").withZone(ZoneOffset.UTC)

    /** A `*_utc` value, exactly `yyyy-MM-ddTHH:mm:ss.SSS+00:00`. */
    fun utc(instant: Instant): String = utcFormatter.format(instant)
}

/** The `capabilities` object in session.json. The app never sees layer 3. */
@Serializable
data class Capabilities(val layer3: Boolean)
