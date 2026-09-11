package com.fieldtap.core.location

import com.fieldtap.core.input.FixSample
import com.fieldtap.format.Coordinates
import com.fieldtap.format.EventKind
import com.fieldtap.format.EventRat
import com.fieldtap.format.EventRow
import com.fieldtap.format.FixProvider
import com.fieldtap.format.LatLon
import com.fieldtap.format.Ranges
import com.fieldtap.format.Severity
import com.fieldtap.format.TrackRow
import java.math.BigDecimal
import kotlin.math.abs

/**
 * Which fixes become track rows and join candidates.
 *
 * - Rejected: mock fixes unless [allowMock] (debug builds pass true); 0,0; latitude or longitude out
 *   of range or not a number; a fix whose `elapsedMs` is not later than the last accepted fix (keeps
 *   track.csv in time order and drops duplicates delivered by two providers).
 * - GPS fixes are accepted. A fused or network fix is accepted only when no GPS fix was accepted
 *   within the last [fallbackAfterMs] of fix time, that is when the newest accepted GPS fix is more
 *   than [fallbackAfterMs] older than it: they are indoor fallbacks, not a second track.
 * - A rejected fix changes nothing, so a burst of mock or 0,0 fixes cannot hold back a real one.
 *
 * Single-threaded: the session dispatcher only.
 *
 * Owner: workstream `location-privacy-core`.
 */
class FixSelector(
    private val allowMock: Boolean = false,
    private val fallbackAfterMs: Long = 3_000,
) {
    private var lastAcceptedElapsedMs: Long? = null
    private var lastGpsElapsedMs: Long? = null

    init {
        require(fallbackAfterMs >= 0) { "fallbackAfterMs must not be negative: $fallbackAfterMs" }
    }

    /** True when [fix] is accepted; an accepted fix becomes the reference for the next decision. */
    fun accept(fix: FixSample): Boolean {
        if (fix.mock && !allowMock) return false
        if (!hasUsablePosition(fix)) return false
        val lastAccepted = lastAcceptedElapsedMs
        if (lastAccepted != null && fix.elapsedMs <= lastAccepted) return false
        if (fix.provider != FixProvider.GPS) {
            val lastGps = lastGpsElapsedMs
            if (lastGps != null && fix.elapsedMs - lastGps <= fallbackAfterMs) return false
        }
        lastAcceptedElapsedMs = fix.elapsedMs
        if (fix.provider == FixProvider.GPS) lastGpsElapsedMs = fix.elapsedMs
        return true
    }

    private fun hasUsablePosition(fix: FixSample): Boolean =
        fix.lat in Ranges.LAT && fix.lon in Ranges.LON && !Coordinates.isNullIsland(fix.lat, fix.lon)
}

/** The outcome of joining a measurement to the track. */
sealed interface JoinResult {
    /** A nearer fix may still arrive; ask again later. */
    data object Pending : JoinResult

    /** Final: the position, or null for blank lat and lon. */
    data class Resolved(val position: LatLon?) : JoinResult
}

/**
 * The GPS join: `fieldtap.gps.tag_rows(rows, track, max_gap_s=5.0)` on the monotonic clock.
 *
 * - The position of the accepted fix whose `elapsedMs` is nearest to the measurement's elapsed time,
 *   at most [maxGapMs] away inclusive; on a tie, the earlier fix. None within range: null.
 * - [join] returns [JoinResult.Pending] while a nearer fix could still arrive: when no accepted fix
 *   at or after the measurement time exists yet and `nowElapsedMs < measurementElapsedMs + maxGapMs +
 *   lateFixSlackMs`. Once a fix at or after the measurement exists, or that time has passed, the
 *   result is final: fixes arrive in time order (see [FixSelector]), so a later fix can never be
 *   nearer than one already at or after the measurement.
 * - [joinFinal] resolves immediately with what is known; used when the session stops.
 * - Fixes older than [retainMs] behind the newest fix are forgotten. Fixes inside a privacy zone
 *   are never added, so no row can take a position from inside a zone.
 *
 * Single-threaded: the session dispatcher only.
 *
 * Tests: the golden kpi and cellinfo lat/lon (1 Hz track, rows at .400 take the fix at .000 of the
 * same second); a tie; 5000 ms exactly matches, 5001 does not; pending until the next fix; stale
 * cellinfo rows join at their original measurement time.
 *
 * Owner: workstream `location-privacy-core`.
 */
class FixJoiner(
    private val maxGapMs: Long = com.fieldtap.format.Schema.GPS_MATCH_MS,
    private val retainMs: Long = 60_000,
    private val lateFixSlackMs: Long = 1_500,
) {
    /** Accepted fixes outside every zone, ascending by `elapsedMs`; equal times keep arrival order. */
    private val fixes = ArrayDeque<FixSample>()

    init {
        require(maxGapMs >= 0) { "maxGapMs must not be negative: $maxGapMs" }
        require(retainMs >= 0) { "retainMs must not be negative: $retainMs" }
        require(lateFixSlackMs >= 0) { "lateFixSlackMs must not be negative: $lateFixSlackMs" }
    }

    /** Adds a join candidate and forgets fixes more than [retainMs] older than the newest one. */
    fun add(fix: FixSample) {
        val newest = fixes.lastOrNull()
        if (newest == null || fix.elapsedMs >= newest.elapsedMs) {
            fixes.addLast(fix)
        } else {
            // FixSelector never delivers an older fix, but the buffer stays sorted whoever calls add.
            var index = fixes.size
            while (index > 0 && fixes[index - 1].elapsedMs > fix.elapsedMs) index--
            fixes.add(index, fix)
        }
        val horizon = fixes.last().elapsedMs - retainMs
        while (fixes.first().elapsedMs < horizon) fixes.removeFirst()
    }

    /** The position for a measurement taken at [measurementElapsedMs], or [JoinResult.Pending]. */
    fun join(measurementElapsedMs: Long, nowElapsedMs: Long): JoinResult {
        val newest = fixes.lastOrNull()
        val fixAtOrAfter = newest != null && newest.elapsedMs >= measurementElapsedMs
        if (!fixAtOrAfter && nowElapsedMs < measurementElapsedMs + maxGapMs + lateFixSlackMs) {
            return JoinResult.Pending
        }
        return JoinResult.Resolved(nearest(measurementElapsedMs))
    }

    /** The position from the fixes known now, without waiting for later ones. */
    fun joinFinal(measurementElapsedMs: Long): LatLon? = nearest(measurementElapsedMs)

    private fun nearest(measurementElapsedMs: Long): LatLon? {
        var best: FixSample? = null
        var bestGap = Long.MAX_VALUE
        for (fix in fixes) {
            val gap = abs(fix.elapsedMs - measurementElapsedMs)
            // Ascending order and a strict comparison keep the earlier fix on a tie.
            if (gap < bestGap) {
                best = fix
                bestGap = gap
            } else if (fix.elapsedMs > measurementElapsedMs) {
                break
            }
        }
        return if (best != null && bestGap <= maxGapMs) LatLon(best.lat, best.lon) else null
    }
}

/**
 * `gps_lost` and `gps_restored`.
 *
 * - No event before the first fix.
 * - [onTick]: when more than [lostAfterMs] of elapsed time passed since the last fix arrived (its
 *   `observedElapsedMs`, so a fix delivered a little late is not a gap) and gps_lost has not been
 *   emitted since that fix: `gps_lost`, rat `-`, severity `warn`, title [LOST_TITLE], detail
 *   `no fix for more than 5 s`, time `nowWallMs`.
 * - [onFix] after gps_lost: `gps_restored`, rat `-`, severity `ok`, title [RESTORED_TITLE], time the
 *   fix's observed wall time.
 * - Every fix the [FixSelector] accepted counts, including fixes suppressed inside a privacy zone
 *   (logging is paused there, so the recorder drops the events anyway).
 *
 * Single-threaded: the session dispatcher only.
 *
 * Owner: workstream `location-privacy-core`.
 */
class GpsEventDeriver(private val lostAfterMs: Long = 5_000) {
    private var lastFixObservedElapsedMs: Long? = null
    private var lostReported = false
    private val lostDetail = "no fix for more than ${seconds(lostAfterMs)} s"

    init {
        require(lostAfterMs >= 0) { "lostAfterMs must not be negative: $lostAfterMs" }
    }

    /** Records an accepted fix; returns `gps_restored` when it ends a reported loss. */
    fun onFix(fix: FixSample): EventRow? {
        lastFixObservedElapsedMs = fix.observedElapsedMs
        if (!lostReported) return null
        lostReported = false
        return EventRow(
            timeUtcMs = fix.observedWallMs,
            rat = EventRat.NONE,
            kind = EventKind.GPS_RESTORED,
            severity = Severity.OK,
            title = RESTORED_TITLE,
        )
    }

    /** Returns `gps_lost` once per loss, when it becomes due. */
    fun onTick(nowWallMs: Long, nowElapsedMs: Long): EventRow? {
        val last = lastFixObservedElapsedMs ?: return null
        if (lostReported || nowElapsedMs - last <= lostAfterMs) return null
        lostReported = true
        return EventRow(
            timeUtcMs = nowWallMs,
            rat = EventRat.NONE,
            kind = EventKind.GPS_LOST,
            severity = Severity.WARN,
            title = LOST_TITLE,
            detail = lostDetail,
        )
    }

    companion object {
        const val LOST_TITLE: String = "GPS lost"
        const val RESTORED_TITLE: String = "GPS restored"

        /** `5000` -> `5`, `2500` -> `2.5`: locale-independent, no trailing zeros. */
        private fun seconds(ms: Long): String = BigDecimal.valueOf(ms, 3).stripTrailingZeros().toPlainString()
    }
}

/**
 * track.csv rows: `time_utc` is `fix.wallMs` (the fix time on the app's wall clock, not the observed
 * time); accuracy, altitude and speed blank when null, not finite, or outside
 * [com.fieldtap.format.Ranges]; provider from the fix. Formatting is `TrackCsv`'s.
 *
 * Owner: workstream `location-privacy-core`.
 */
object TrackRows {
    fun of(fix: FixSample): TrackRow = TrackRow(
        timeUtcMs = fix.wallMs,
        position = LatLon(fix.lat, fix.lon),
        accuracyM = fix.accuracyM?.takeIf { it.isFinite() && it >= Ranges.ACCURACY_M_MIN },
        altitudeM = fix.altitudeM?.takeIf { it in Ranges.ALTITUDE_M },
        speedMps = fix.speedMps?.takeIf { it in Ranges.SPEED_MPS },
        provider = fix.provider,
    )
}
