package com.fieldtap.core.privacy

import com.fieldtap.core.export.Sha256
import com.fieldtap.core.input.FixSample
import com.fieldtap.format.Coordinates
import com.fieldtap.format.EventKind
import com.fieldtap.format.EventRat
import com.fieldtap.format.EventRow
import com.fieldtap.format.Ranges
import com.fieldtap.format.Severity
import java.math.BigDecimal
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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
 *   an uncertain fix near the edge counts as inside. A negative accuracy counts as 0; an accuracy
 *   that is not a number counts as the full [MAX_ACCURACY_MARGIN_M], erring towards privacy.
 * - [validate]: problems as short sentences; empty when valid. Radius in [MIN_RADIUS_M, MAX_RADIUS_M],
 *   coordinates in range, not 0,0, label not blank.
 *
 * Owner: workstream `location-privacy-core`.
 */
object PrivacyZones {
    const val MIN_RADIUS_M: Double = 50.0
    const val MAX_RADIUS_M: Double = 5_000.0
    const val MAX_ACCURACY_MARGIN_M: Double = 50.0

    /** The sphere radius of [distanceM]. */
    const val EARTH_RADIUS_M: Double = 6_371_000.0

    /** Great-circle distance in metres; NaN when any input is NaN. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val halfDeltaPhi = Math.toRadians(lat2 - lat1) / 2
        val halfDeltaLambda = Math.toRadians(lon2 - lon1) / 2
        val sinPhi = sin(halfDeltaPhi)
        val sinLambda = sin(halfDeltaLambda)
        val a = sinPhi * sinPhi + cos(phi1) * cos(phi2) * sinLambda * sinLambda
        return 2 * EARTH_RADIUS_M * asin(sqrt(min(1.0, a)))
    }

    /** True when [fix] is inside at least one of [zones], accuracy margin included. */
    fun contains(zones: List<PrivacyZone>, fix: FixSample): Boolean {
        if (zones.isEmpty()) return false
        val margin = accuracyMargin(fix.accuracyM)
        return zones.any { zone -> distanceM(zone.lat, zone.lon, fix.lat, fix.lon) <= zone.radiusM + margin }
    }

    /** What is wrong with [zone], one short sentence each, in field order; empty when it is valid. */
    fun validate(zone: PrivacyZone): List<String> = buildList {
        if (zone.label.isBlank()) add("Give the zone a name.")
        val latValid = zone.lat in Ranges.LAT
        val lonValid = zone.lon in Ranges.LON
        if (!latValid) add("Latitude must be between -90 and 90.")
        if (!lonValid) add("Longitude must be between -180 and 180.")
        if (latValid && lonValid && Coordinates.isNullIsland(zone.lat, zone.lon)) {
            add("0, 0 is not a real position; place the zone where it belongs.")
        }
        if (zone.radiusM.isNaN() || zone.radiusM < MIN_RADIUS_M || zone.radiusM > MAX_RADIUS_M) {
            add("Radius must be between ${metres(MIN_RADIUS_M)} m and ${metres(MAX_RADIUS_M)} m.")
        }
    }

    private fun accuracyMargin(accuracyM: Double?): Double = when {
        accuracyM == null -> 0.0
        accuracyM.isNaN() -> MAX_ACCURACY_MARGIN_M
        accuracyM <= 0.0 -> 0.0
        else -> min(accuracyM, MAX_ACCURACY_MARGIN_M)
    }

    private fun metres(value: Double): String = BigDecimal(value).stripTrailingZeros().toPlainString()
}

/**
 * The pause state machine for privacy zones.
 *
 * - [onFix] with a fix inside any zone while not paused: pause, count a pause, return a
 *   `privacy_zone` event: rat `-`, severity `info`, title [PAUSED_TITLE], no detail, time the fix's
 *   observed wall time.
 * - [onFix] with a fix outside every zone while paused: resume, return title [RESUMED_TITLE].
 * - Otherwise null. Neither title nor detail ever names or locates the zone, and the event carries no
 *   pci, arfcn or cause.
 * - Callers pass only fixes the [com.fieldtap.core.location.FixSelector] accepted. The zones are copied
 *   at construction.
 *
 * Owner: workstream `location-privacy-core`.
 */
class PrivacyZoneGate(zones: List<PrivacyZone>) {
    private val zones: List<PrivacyZone> = zones.toList()
    private var isPaused = false
    private var pauseCount = 0

    val paused: Boolean get() = isPaused

    val pauses: Int get() = pauseCount

    fun onFix(fix: FixSample): EventRow? {
        val inside = PrivacyZones.contains(zones, fix)
        return when {
            inside && !isPaused -> {
                isPaused = true
                pauseCount++
                event(fix, PAUSED_TITLE)
            }
            !inside && isPaused -> {
                isPaused = false
                event(fix, RESUMED_TITLE)
            }
            else -> null
        }
    }

    private fun event(fix: FixSample, title: String): EventRow = EventRow(
        timeUtcMs = fix.observedWallMs,
        rat = EventRat.NONE,
        kind = EventKind.PRIVACY_ZONE,
        severity = Severity.INFO,
        title = title,
    )

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
    val sha256: String by lazy(LazyThreadSafetyMode.PUBLICATION) { Sha256.hex(text.toByteArray(Charsets.UTF_8)) }
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
 * [CURRENT] follows android/ARCHITECTURE.md section 0, decision 2: what is recorded, that it stays on
 * the phone until shared as a zip, that no phone or SIM identifiers are read, and that consent can be
 * withdrawn in Settings, which stops new sessions.
 *
 * Owner: workstream `location-privacy-core`.
 */
object Consent {
    // Draft wording, version 2026-09-10-draft. It has NOT been legally reviewed. Any change to the
    // text, even one character, needs a new version: the hash in every stored ConsentRecord and every
    // session's privacy.consent_sha256 is over these exact bytes. Keep this note out of the UI.
    val CURRENT: ConsentText = ConsentText(
        version = "2026-09-10-draft",
        text = listOf(
            "5gto6G FieldTap records only while a session you started is running.",
            "A session records the cells your phone reports (identity, signal strength and quality, band " +
                "and channel), service and mobile data state, your GPS track, the results of any ping and " +
                "download tests you turn on, and the markers and notes you add. It also records your " +
                "phone's make, model and software version, the network and SIM operator names and codes, " +
                "and a random ID created for this installation.",
            "Android gives cell information only to apps allowed precise location, so the app asks for " +
                "it. Inside a privacy zone you set, nothing is recorded except that logging paused and resumed.",
            "Recordings stay on this phone. A session leaves it only when you share it as a zip file, and " +
                "you choose how precise the locations in that copy are. Ping and download tests, which are " +
                "off unless you turn them on, contact the servers named in Settings.",
            "The app never reads phone or SIM identifiers: no IMEI, IMSI, ICCID, phone number, Android ID " +
                "or advertising ID. It never records Wi-Fi names or MAC addresses.",
            "You can withdraw consent at any time in Settings. New sessions then cannot start, and sessions " +
                "already on this phone stay until you delete them.",
        ).joinToString("\n\n"),
    )

    /** True when [record] matches [CURRENT] by version and hash. */
    fun isCurrent(record: ConsentRecord?): Boolean =
        record != null && record.version == CURRENT.version && record.sha256 == CURRENT.sha256

    /** A record of agreeing to [CURRENT] at [grantedUtcMs] (Unix milliseconds). */
    fun record(grantedUtcMs: Long): ConsentRecord =
        ConsentRecord(version = CURRENT.version, sha256 = CURRENT.sha256, grantedUtcMs = grantedUtcMs)
}
