package com.fieldtap.core.privacy

import com.fieldtap.core.input.FixSample
import com.fieldtap.format.EventRow

/**
 * A place inside which nothing is written. [label] is for the Settings screen only and never
 * appears in a session file, an event, a log line or an export.
 *
 * Owner: workstream `location-privacy-core`.
 */
data class PrivacyZone(
    /** Random UUID. */
    val id: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
)

/**
 * Zone geometry and validation.
 *
 * - [distanceM]: haversine on a 6 371 000 m sphere.
 * - [contains]: a fix is inside a zone when `distance <= radiusM + min(accuracyM ?: 0, MAX_ACCURACY_MARGIN_M)`:
 *   an uncertain fix near the edge counts as inside.
 * - [validate]: problems as short sentences; empty when valid. Radius in [MIN_RADIUS_M, MAX_RADIUS_M],
 *   coordinates in range, not 0,0, label not blank.
 *
 * Owner: workstream `location-privacy-core`.
 */
object PrivacyZones {
    const val MIN_RADIUS_M: Double = 50.0
    const val MAX_RADIUS_M: Double = 5_000.0
    const val MAX_ACCURACY_MARGIN_M: Double = 50.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double = TODO("location-privacy-core")

    fun contains(zones: List<PrivacyZone>, fix: FixSample): Boolean = TODO("location-privacy-core")

    fun validate(zone: PrivacyZone): List<String> = TODO("location-privacy-core")
}

/**
 * The pause state machine for privacy zones.
 *
 * - [onFix] with a fix inside any zone while not paused: pause, count a pause, return a
 *   `privacy_zone` event: rat `-`, severity `info`, title [PAUSED_TITLE], no detail, time the fix's
 *   observed wall time.
 * - [onFix] with a fix outside every zone while paused: resume, return title [RESUMED_TITLE].
 * - Otherwise null. Neither title nor detail ever names or locates the zone.
 *
 * Owner: workstream `location-privacy-core`.
 */
class PrivacyZoneGate(private val zones: List<PrivacyZone>) {
    val paused: Boolean get() = TODO("location-privacy-core")

    val pauses: Int get() = TODO("location-privacy-core")

    fun onFix(fix: FixSample): EventRow? = TODO("location-privacy-core")

    companion object {
        const val PAUSED_TITLE: String = "Logging paused in a privacy zone"
        const val RESUMED_TITLE: String = "Logging resumed"
    }
}

/**
 * A versioned consent text. The SHA-256 is over the exact UTF-8 bytes of [text]; changing one
 * character is a new version.
 */
data class ConsentText(val version: String, val text: String) {
    /** 64 lower-case hex digits. */
    val sha256: String get() = TODO("location-privacy-core")
}

/** What the user agreed to, stored in settings and copied into `privacy.consent_*` of every session. */
data class ConsentRecord(
    val version: String,
    val sha256: String,
    val grantedUtcMs: Long,
)

/**
 * The logging consent shown full-screen before any location prompt. Upload consent does not exist
 * yet (no upload in this version).
 *
 * [CURRENT] is a draft pending product and legal review; see android/ARCHITECTURE.md open questions.
 *
 * Owner: workstream `location-privacy-core`.
 */
object Consent {
    val CURRENT: ConsentText = ConsentText(
        version = "2026-09-10-draft",
        text = "5gto6G FieldTap records measurements only while a session you started is running.\n\n" +
            "Android gives apps no cell information without precise location, so the app asks for it. " +
            "During a session it writes to this phone: the cells your phone reports (identity, signal " +
            "strength, band, channel), service and data state, GPS positions, test results, and the " +
            "markers and notes you add.\n\n" +
            "It never collects IMEI, IMSI, ICCID, phone number, Android ID, advertising ID, Wi-Fi names " +
            "or MAC addresses. Nothing is written inside a privacy zone you set.\n\n" +
            "Sessions stay on this phone until you share or delete them. There is no account and no upload.",
    )

    /** True when [record] matches [CURRENT] by version and hash. */
    fun isCurrent(record: ConsentRecord?): Boolean = TODO("location-privacy-core")
}
