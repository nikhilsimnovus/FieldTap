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
import kotlin.math.floor
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

    /**
     * Outside every zone, but within [PrivacyZones.NEAR_ZONE_M] of a zone's edge. Descriptive only: how long a fix outside
     * keeps writes going is [ZoneReach]'s, from the distance itself.
     */
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

    /**
     * How far [fix] is from the nearest zone edge, less its whole accuracy: the least distance the phone must still
     * cover to be inside a zone. 0 when the accuracy circle reaches into a zone or a value is not a number; infinite
     * without zones.
     */
    fun marginM(zones: List<PrivacyZone>, fix: FixSample): Double {
        if (zones.isEmpty()) return Double.POSITIVE_INFINITY
        val reach = accuracyReach(fix.accuracyM)
        var margin = Double.POSITIVE_INFINITY
        for (zone in zones) {
            val distance = distanceM(zone.lat, zone.lon, fix.lat, fix.lon) - zone.radiusM - reach
            if (distance.isNaN()) return 0.0
            margin = min(margin, distance)
        }
        return margin.coerceAtLeast(0.0)
    }

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
 * How far a phone can travel: whether it could have been inside a privacy zone while no fix placed it.
 *
 * The bound is physical and deliberately generous. From the speed a fix reports, plus [SPEED_SLACK_MPS] for its error,
 * the phone may speed up at [MAX_ACCELERATION_MPS2] (a sports car's full throttle) to [MAX_SPEED_MPS] (faster than a
 * car on any road or a high-speed train). A fix without a usable speed counts as moving at [MAX_SPEED_MPS] already.
 * The distance to cover is [PrivacyZones.marginM]: to the nearest zone edge, less the fix's whole accuracy.
 *
 * - [reachM]: the most distance covered in [reachM]'s time from a speed.
 * - [reachTimeMs]: the least time to cover a margin, in whole milliseconds rounded down; [Long.MAX_VALUE] for an
 *   infinite margin (no zones).
 * - [forwardMs]: how long after a fix inputs count as taken outside on that fix alone: [FORWARD_MS] when reaching a
 *   zone takes at least that long, else 0. It has two values only, so where writing stops after a fix never measures
 *   how far a zone is.
 *
 * Owner: workstream `location-privacy-core`.
 */
object ZoneReach {
    const val MAX_SPEED_MPS: Double = 100.0
    const val MAX_ACCELERATION_MPS2: Double = 10.0
    const val SPEED_SLACK_MPS: Double = 3.0
    const val FORWARD_MS: Long = 5_000

    /** The speed a reach starts from: [speedMps] plus the slack, at most [MAX_SPEED_MPS]; unknown or unusable is [MAX_SPEED_MPS]. */
    fun startSpeedMps(speedMps: Double?): Double =
        if (speedMps == null || !speedMps.isFinite() || speedMps < 0.0) MAX_SPEED_MPS else min(speedMps + SPEED_SLACK_MPS, MAX_SPEED_MPS)

    /** The most distance, in metres, covered in [elapsedMs] starting at [speedMps]. */
    fun reachM(elapsedMs: Long, speedMps: Double?): Double {
        if (elapsedMs <= 0) return 0.0
        val seconds = elapsedMs / 1_000.0
        val speed = startSpeedMps(speedMps)
        val toTopSpeed = (MAX_SPEED_MPS - speed) / MAX_ACCELERATION_MPS2
        return if (seconds <= toTopSpeed) {
            speed * seconds + MAX_ACCELERATION_MPS2 * seconds * seconds / 2
        } else {
            speed * toTopSpeed + MAX_ACCELERATION_MPS2 * toTopSpeed * toTopSpeed / 2 + MAX_SPEED_MPS * (seconds - toTopSpeed)
        }
    }

    /** The least time, in milliseconds rounded down, to cover [marginM] starting at [speedMps]. */
    fun reachTimeMs(marginM: Double, speedMps: Double?): Long {
        if (marginM.isNaN() || marginM <= 0.0) return 0
        if (marginM.isInfinite()) return Long.MAX_VALUE
        val speed = startSpeedMps(speedMps)
        val toTopSpeed = (MAX_SPEED_MPS - speed) / MAX_ACCELERATION_MPS2
        val speedingUpM = speed * toTopSpeed + MAX_ACCELERATION_MPS2 * toTopSpeed * toTopSpeed / 2
        val seconds = if (marginM <= speedingUpM) {
            (-speed + sqrt(speed * speed + 2 * MAX_ACCELERATION_MPS2 * marginM)) / MAX_ACCELERATION_MPS2
        } else {
            toTopSpeed + (marginM - speedingUpM) / MAX_SPEED_MPS
        }
        val ms = floor(seconds * 1_000)
        return if (ms >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else ms.toLong()
    }

    /** [FORWARD_MS] when reaching a zone takes at least that long, else 0. */
    fun forwardMs(reachTimeMs: Long): Long = if (reachTimeMs >= FORWARD_MS) FORWARD_MS else 0
}

/**
 * The pause state machine for privacy zones, and the hold that keeps writes back until fixes show where they were
 * made. Without zones it never pauses and never holds.
 *
 * Paused (nothing but `privacy_zone` events is written):
 * - [onFix] with a fix [ZonePlacement.INSIDE] a zone while not paused: pause, count a pause, return a `privacy_zone`
 *   event: rat `-`, severity `info`, title [PAUSED_TITLE], no detail, time the fix's observed wall time.
 * - [onFix] with a fix outside every zone ([ZonePlacement.NEAR] or [ZonePlacement.CLEAR]) after that pause: resume,
 *   title [RESUMED_TITLE]. A [ZonePlacement.AMBIGUOUS] fix never resumes.
 * - Paused without a fix ([pausedWithoutFix]), title [PAUSED_NO_FIX_TITLE], detail [NO_FIX_DETAIL]:
 *   - [onTick], once more than [holdLimitMs] has passed since the newest fix outside every zone was observed (before
 *     any, since the first tick); time the tick's wall time.
 *   - [onFix] with a fix outside every zone when, since the fix outside every zone before it (before any, since the
 *     start), the phone could have gone into a zone and back ([ZoneReach]: the two fixes' reach times add up to no
 *     more than the time between them); time this fix's observed wall time.
 *   It is counted in [pauses] only once a fix inside a zone confirms it, and then resumes like the pause above.
 *   Otherwise it resumes at a fix outside every zone when the fix outside every zone before it left no time to visit
 *   a zone in between, so a phone skirting a zone pauses once and resumes once.
 *
 * Holding: while not paused, an input may be written only if it was observed by [outsideUntilMs], on the elapsed
 * clock: the newest fix outside every zone plus its [ZoneReach.forwardMs], or the time of an [ZonePlacement.AMBIGUOUS]
 * fix after that fix if earlier; nothing before the first fix outside every zone. Later inputs wait: the next fix
 * outside every zone either shows the phone stayed outside, and moves [outsideUntilMs] past them, or pauses logging,
 * which drops them. [holding] is true while the newest time the gate was told, by a fix or a tick, is past
 * [outsideUntilMs].
 *
 * Neither title nor detail ever names or locates a zone, and the events carry no pci, arfcn or cause. Every event time
 * is a fix's or a tick's, never one computed from a distance. Callers pass only fixes the
 * [com.fieldtap.core.location.FixSelector] accepted, in its order. The zones are copied at construction.
 *
 * @param startElapsedMs the session's start on the elapsed clock, from which the time before the first fix counts;
 *   null takes the first time the gate is told, by a fix or a tick.
 *
 * Owner: workstream `location-privacy-core`.
 */
class PrivacyZoneGate(
    zones: List<PrivacyZone>,
    private val holdLimitMs: Long = HOLD_LIMIT_MS,
    startElapsedMs: Long? = null,
) {
    private val zones: List<PrivacyZone> = zones.toList()
    private var isPaused = false
    private var withoutFix = false
    private var pauseCount = 0
    private var startMs: Long? = startElapsedMs
    private var firstTickElapsedMs: Long? = null
    private var newestSeenElapsedMs: Long? = null
    private var lastOutside: OutsideFix? = null
    private var ambiguousSinceElapsedMs: Long? = null

    init {
        require(holdLimitMs >= 0) { "holdLimitMs must not be negative: $holdLimitMs" }
    }

    val paused: Boolean get() = isPaused

    /** Pauses a fix inside a zone started or confirmed: `privacy.zone_pauses`. */
    val pauses: Int get() = pauseCount

    /**
     * Inputs observed at or before this elapsed time may be written: [Long.MAX_VALUE] without zones; null while paused
     * or before the first fix outside every zone.
     */
    val outsideUntilMs: Long?
        get() {
            if (zones.isEmpty()) return Long.MAX_VALUE
            if (isPaused) return null
            val outside = lastOutside ?: return null
            val until = outside.elapsedMs + outside.forwardMs
            return ambiguousSinceElapsedMs?.let { min(until, it) } ?: until
        }

    /** True while inputs arriving now wait for a fix that shows where they were made; never while [paused]. */
    val holding: Boolean
        get() {
            if (zones.isEmpty() || isPaused) return false
            val until = outsideUntilMs ?: return true
            val newest = newestSeenElapsedMs ?: return false
            return newest > until
        }

    /** True while [paused] because no fix showed where the phone is, rather than because a fix was inside a zone. */
    val pausedWithoutFix: Boolean get() = isPaused && withoutFix

    /** The placement of the last fix passed to [onFix]; [ZonePlacement.CLEAR] before any. */
    var lastPlacement: ZonePlacement = ZonePlacement.CLEAR
        private set

    /** How long inputs have waited at [nowElapsedMs]: null when nothing waits then, 0 before the first tick and fix. */
    fun holdAgeMs(nowElapsedMs: Long): Long? {
        if (zones.isEmpty() || isPaused) return null
        val until = outsideUntilMs
        if (until != null) return if (nowElapsedMs > until) nowElapsedMs - until else null
        val since = firstTickElapsedMs ?: return 0
        return (nowElapsedMs - since).coerceAtLeast(0)
    }

    fun onFix(fix: FixSample): EventRow? {
        if (startMs == null) startMs = fix.elapsedMs
        newestSeenElapsedMs = maxOf(newestSeenElapsedMs ?: fix.observedElapsedMs, fix.observedElapsedMs)
        val placement = PrivacyZones.placement(zones, fix)
        lastPlacement = placement
        return when (placement) {
            ZonePlacement.INSIDE -> {
                lastOutside = null
                ambiguousSinceElapsedMs = null
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
                if (!isPaused && ambiguousSinceElapsedMs == null) ambiguousSinceElapsedMs = fix.elapsedMs
                null
            }

            ZonePlacement.NEAR, ZonePlacement.CLEAR -> onOutside(fix)
        }
    }

    /** Times the wait for a fix outside every zone; returns the pause when it has lasted more than [holdLimitMs]. */
    fun onTick(nowWallMs: Long, nowElapsedMs: Long): EventRow? {
        if (startMs == null) startMs = nowElapsedMs
        if (firstTickElapsedMs == null) firstTickElapsedMs = nowElapsedMs
        newestSeenElapsedMs = maxOf(newestSeenElapsedMs ?: nowElapsedMs, nowElapsedMs)
        if (zones.isEmpty() || isPaused) return null
        val since = lastOutside?.observedElapsedMs ?: firstTickElapsedMs ?: nowElapsedMs
        if (nowElapsedMs - since <= holdLimitMs) return null
        isPaused = true
        withoutFix = true
        ambiguousSinceElapsedMs = null
        return event(nowWallMs, PAUSED_NO_FIX_TITLE, NO_FIX_DETAIL)
    }

    private fun onOutside(fix: FixSample): EventRow? {
        ambiguousSinceElapsedMs = null
        if (zones.isEmpty()) return null
        val previous = lastOutside
        val current = OutsideFix(
            elapsedMs = fix.elapsedMs,
            observedElapsedMs = fix.observedElapsedMs,
            reachTimeMs = ZoneReach.reachTimeMs(PrivacyZones.marginM(zones, fix), fix.speedMps),
        )
        lastOutside = current
        return when {
            isPaused && !withoutFix -> resume(fix)
            isPaused -> if (previous != null && !couldVisitZone(previous, current)) resume(fix) else null
            couldVisitZone(previous, current) -> {
                isPaused = true
                withoutFix = true
                event(fix.observedWallMs, PAUSED_NO_FIX_TITLE, NO_FIX_DETAIL)
            }
            else -> null
        }
    }

    /** Whether the phone could have been inside a zone between [previous] (null: the start) and [current]. */
    private fun couldVisitZone(previous: OutsideFix?, current: OutsideFix): Boolean {
        val fromMs = previous?.elapsedMs ?: startMs ?: return false
        val betweenMs = current.elapsedMs - fromMs
        if (betweenMs <= 0) return false
        val previousReachMs = previous?.reachTimeMs ?: 0L
        val reachMs = if (previousReachMs > Long.MAX_VALUE - current.reachTimeMs) Long.MAX_VALUE else previousReachMs + current.reachTimeMs
        return reachMs <= betweenMs
    }

    private fun resume(fix: FixSample): EventRow {
        isPaused = false
        withoutFix = false
        return event(fix.observedWallMs, RESUMED_TITLE, detail = null)
    }

    private fun event(timeUtcMs: Long, title: String, detail: String?): EventRow = EventRow(
        timeUtcMs = timeUtcMs,
        rat = EventRat.NONE,
        kind = EventKind.PRIVACY_ZONE,
        severity = Severity.INFO,
        title = title,
        detail = detail,
    )

    /** A fix outside every zone, as the gate remembers it. */
    private class OutsideFix(val elapsedMs: Long, val observedElapsedMs: Long, val reachTimeMs: Long) {
        val forwardMs: Long get() = ZoneReach.forwardMs(reachTimeMs)
    }

    companion object {
        const val PAUSED_TITLE: String = "Logging paused in a privacy zone"
        const val RESUMED_TITLE: String = "Logging resumed"
        const val PAUSED_NO_FIX_TITLE: String = "Logging paused until the location is known"
        const val NO_FIX_DETAIL: String = "no location fix showed the phone outside every privacy zone"

        /** How long writes may wait for a fix outside every zone before they are dropped and logging pauses. */
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
