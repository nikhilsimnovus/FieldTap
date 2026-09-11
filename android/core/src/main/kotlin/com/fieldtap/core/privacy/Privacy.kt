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

/** Where one fix lies with respect to a session's privacy zones, from [PrivacyZones.placement]. */
enum class ZonePlacement {
    /** Inside a zone: at most its radius plus up to [PrivacyZones.MAX_ACCURACY_MARGIN_M] of the fix's accuracy from its centre. */
    INSIDE,

    /**
     * Not inside by that rule, yet the fix's own accuracy circle reaches into a zone, so the phone may be inside
     * it. A coarse network or indoor fix taken inside a zone looks like this: it neither pauses nor resumes, and it
     * places nothing.
     */
    AMBIGUOUS,

    /** Outside every zone, but within [PrivacyZones.NEAR_ZONE_M] of a zone's edge: the next fix may find the phone inside. */
    NEAR,

    /** Outside every zone and not near one. The only placement when there are no zones. */
    CLEAR,
}

/**
 * Zone geometry and validation.
 *
 * - [distanceM]: haversine on a 6 371 000 m sphere.
 * - [placement]: [ZonePlacement.INSIDE] when `distance <= radiusM + min(accuracyM ?: 0, MAX_ACCURACY_MARGIN_M)` for
 *   some zone: an uncertain fix near the edge counts as inside, but a fix far away with a huge accuracy does not
 *   pause logging. Else [ZonePlacement.AMBIGUOUS] when `distance - accuracyM <= radiusM` for some zone (the whole
 *   accuracy, uncapped): the fix cannot show that the phone is outside. Else [ZonePlacement.NEAR] within
 *   [NEAR_ZONE_M] of some zone's edge, else [ZonePlacement.CLEAR]. A negative accuracy counts as 0; an accuracy
 *   that is not a number counts as the full [MAX_ACCURACY_MARGIN_M] for INSIDE and as unbounded for AMBIGUOUS,
 *   erring towards privacy.
 * - [contains]: the placement is INSIDE.
 * - [validate]: problems as short sentences; empty when valid. Radius in [MIN_RADIUS_M, MAX_RADIUS_M],
 *   coordinates in range, not 0,0, label not blank.
 *
 * Owner: workstream `location-privacy-core`.
 */
object PrivacyZones {
    const val MIN_RADIUS_M: Double = 50.0
    const val MAX_RADIUS_M: Double = 5_000.0
    const val MAX_ACCURACY_MARGIN_M: Double = 50.0

    /** How far beyond a zone's edge a fix counts as [ZonePlacement.NEAR]: a few minutes' walk, well under a minute's drive. */
    const val NEAR_ZONE_M: Double = 250.0

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
    fun contains(zones: List<PrivacyZone>, fix: FixSample): Boolean = placement(zones, fix) == ZonePlacement.INSIDE

    /** Where [fix] lies with respect to [zones]; INSIDE wins over AMBIGUOUS, which wins over NEAR. */
    fun placement(zones: List<PrivacyZone>, fix: FixSample): ZonePlacement {
        if (zones.isEmpty()) return ZonePlacement.CLEAR
        val margin = accuracyMargin(fix.accuracyM)
        val reach = accuracyReach(fix.accuracyM)
        var placement = ZonePlacement.CLEAR
        for (zone in zones) {
            val distance = distanceM(zone.lat, zone.lon, fix.lat, fix.lon)
            if (distance <= zone.radiusM + margin) return ZonePlacement.INSIDE
            if (distance.isNaN() || distance - reach <= zone.radiusM) {
                placement = ZonePlacement.AMBIGUOUS
            } else if (placement == ZonePlacement.CLEAR && distance <= zone.radiusM + NEAR_ZONE_M) {
                placement = ZonePlacement.NEAR
            }
        }
        return placement
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

    private fun accuracyReach(accuracyM: Double?): Double = when {
        accuracyM == null -> 0.0
        accuracyM.isNaN() -> Double.POSITIVE_INFINITY
        accuracyM <= 0.0 -> 0.0
        else -> accuracyM
    }

    private fun metres(value: Double): String = BigDecimal(value).stripTrailingZeros().toPlainString()
}

/**
 * The pause state machine for privacy zones, and the hold that keeps writes back until a fix shows where they
 * were made.
 *
 * Paused (nothing but `privacy_zone` events is written):
 * - [onFix] with a fix [ZonePlacement.INSIDE] a zone while not paused: pause, count a pause, return a
 *   `privacy_zone` event: rat `-`, severity `info`, title [PAUSED_TITLE], no detail, time the fix's
 *   observed wall time.
 * - [onFix] with a fix outside every zone ([ZonePlacement.NEAR] or [ZonePlacement.CLEAR]) while paused: resume,
 *   return title [RESUMED_TITLE]. A [ZonePlacement.AMBIGUOUS] fix never resumes.
 *
 * Holding ([holding]: writes wait for the next fix that shows the phone inside or outside, which decides whether
 * they are dropped or written) applies only when there are zones, and never while paused:
 * - from the start, before any fix: nothing yet shows where the phone is;
 * - after a [ZonePlacement.NEAR] fix, until the next fix: the phone may enter the zone before it;
 * - from a [ZonePlacement.AMBIGUOUS] fix, until a fix shows inside or outside; further ambiguous fixes do not
 *   extend the hold.
 * A [ZonePlacement.CLEAR] fix ends a hold; an INSIDE fix ends it with a pause.
 * - [onTick] ends a hold that has lasted more than [holdLimitMs] with a pause of its own ([pausedWithoutFix]):
 *   title [PAUSED_NO_FIX_TITLE], detail [NO_FIX_DETAIL], time the tick's wall time. It is counted in [pauses] only
 *   once a fix inside a zone confirms it; a fix outside resumes as usual. A hold that began before the first tick
 *   is timed from that tick.
 *
 * Neither title nor detail ever names or locates a zone, and the events carry no pci, arfcn or cause.
 * Callers pass only fixes the [com.fieldtap.core.location.FixSelector] accepted. The zones are copied at
 * construction.
 *
 * Owner: workstream `location-privacy-core`.
 */
class PrivacyZoneGate(zones: List<PrivacyZone>, private val holdLimitMs: Long = HOLD_LIMIT_MS) {
    private val zones: List<PrivacyZone> = zones.toList()
    private var isPaused = false
    private var withoutFix = false
    private var pauseCount = 0
    private var isHolding = this.zones.isNotEmpty()
    private var holdSinceElapsedMs: Long? = null

    init {
        require(holdLimitMs >= 0) { "holdLimitMs must not be negative: $holdLimitMs" }
    }

    val paused: Boolean get() = isPaused

    /** Pauses a fix inside a zone started or confirmed: `privacy.zone_pauses`. */
    val pauses: Int get() = pauseCount

    /** True while writes wait for a fix that shows where they were made; never while [paused]. */
    val holding: Boolean get() = isHolding

    /** True while [paused] because no fix showed where the phone is, rather than because a fix was inside a zone. */
    val pausedWithoutFix: Boolean get() = isPaused && withoutFix

    /** The placement of the last fix passed to [onFix]; [ZonePlacement.CLEAR] before any. */
    var lastPlacement: ZonePlacement = ZonePlacement.CLEAR
        private set

    /** How long the current hold has lasted at [nowElapsedMs]: null when not [holding], 0 before its first tick. */
    fun holdAgeMs(nowElapsedMs: Long): Long? {
        if (!isHolding) return null
        val since = holdSinceElapsedMs ?: return 0
        return (nowElapsedMs - since).coerceAtLeast(0)
    }

    fun onFix(fix: FixSample): EventRow? {
        val placement = PrivacyZones.placement(zones, fix)
        lastPlacement = placement
        return when (placement) {
            ZonePlacement.INSIDE -> {
                endHold()
                when {
                    !isPaused -> {
                        isPaused = true
                        withoutFix = false
                        pauseCount++
                        event(fix.observedWallMs, PAUSED_TITLE, detail = null)
                    }
                    withoutFix -> {
                        withoutFix = false
                        pauseCount++
                        null
                    }
                    else -> null
                }
            }

            ZonePlacement.AMBIGUOUS -> {
                if (!isPaused && !isHolding) hold(fix.observedElapsedMs)
                null
            }

            ZonePlacement.NEAR, ZonePlacement.CLEAR -> {
                val resumed = if (isPaused) {
                    isPaused = false
                    withoutFix = false
                    event(fix.observedWallMs, RESUMED_TITLE, detail = null)
                } else {
                    null
                }
                if (placement == ZonePlacement.NEAR) hold(fix.observedElapsedMs) else endHold()
                resumed
            }
        }
    }

    /** Times the hold; returns the pause that ends one lasting more than [holdLimitMs]. */
    fun onTick(nowWallMs: Long, nowElapsedMs: Long): EventRow? {
        if (!isHolding) return null
        val since = holdSinceElapsedMs
        if (since == null) {
            holdSinceElapsedMs = nowElapsedMs
            return null
        }
        if (nowElapsedMs - since <= holdLimitMs) return null
        endHold()
        isPaused = true
        withoutFix = true
        return event(nowWallMs, PAUSED_NO_FIX_TITLE, NO_FIX_DETAIL)
    }

    private fun hold(sinceElapsedMs: Long) {
        isHolding = true
        holdSinceElapsedMs = sinceElapsedMs
    }

    private fun endHold() {
        isHolding = false
        holdSinceElapsedMs = null
    }

    private fun event(timeUtcMs: Long, title: String, detail: String?): EventRow = EventRow(
        timeUtcMs = timeUtcMs,
        rat = EventRat.NONE,
        kind = EventKind.PRIVACY_ZONE,
        severity = Severity.INFO,
        title = title,
        detail = detail,
    )

    companion object {
        const val PAUSED_TITLE: String = "Logging paused in a privacy zone"
        const val RESUMED_TITLE: String = "Logging resumed"
        const val PAUSED_NO_FIX_TITLE: String = "Logging paused until the location is known"
        const val NO_FIX_DETAIL: String = "no location fix showed the phone outside every privacy zone"

        /** How long writes may wait for a fix before they are dropped and logging pauses. */
        const val HOLD_LIMIT_MS: Long = 60_000
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
