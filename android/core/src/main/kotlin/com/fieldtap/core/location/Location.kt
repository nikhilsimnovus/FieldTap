package com.fieldtap.core.location

import com.fieldtap.core.input.FixSample
import com.fieldtap.format.EventRow
import com.fieldtap.format.LatLon
import com.fieldtap.format.TrackRow

/**
 * Which fixes become track rows and join candidates.
 *
 * - Rejected: mock fixes unless [allowMock] (debug builds pass true); 0,0; latitude or longitude out
 *   of range; a fix whose `elapsedMs` is not later than the last accepted fix (keeps track.csv in
 *   time order and drops duplicates delivered by two providers).
 * - GPS fixes are accepted. A fused or network fix is accepted only when no GPS fix was accepted
 *   within the last [fallbackAfterMs] of fix time: they are indoor fallbacks, not a second track.
 *
 * Owner: workstream `location-privacy-core`.
 */
class FixSelector(
    private val allowMock: Boolean = false,
    private val fallbackAfterMs: Long = 3_000,
) {
    fun accept(fix: FixSample): Boolean = TODO("location-privacy-core")
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
 *   result is final.
 * - [joinFinal] resolves immediately with what is known; used when the session stops.
 * - Fixes older than [retainMs] behind the newest fix may be forgotten. Fixes inside a privacy zone
 *   are never added, so no row can take a position from inside a zone.
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
    fun add(fix: FixSample): Unit = TODO("location-privacy-core")

    fun join(measurementElapsedMs: Long, nowElapsedMs: Long): JoinResult = TODO("location-privacy-core")

    fun joinFinal(measurementElapsedMs: Long): LatLon? = TODO("location-privacy-core")
}

/**
 * `gps_lost` and `gps_restored`.
 *
 * - No event before the first fix.
 * - [onTick]: when more than [lostAfterMs] of elapsed time passed since the last fix and gps_lost has
 *   not been emitted since that fix: `gps_lost`, rat `-`, severity `warn`, title `GPS lost`, detail
 *   `no fix for more than 5 s`, time `nowWallMs`.
 * - [onFix] after gps_lost: `gps_restored`, severity `ok`, title `GPS restored`, time the fix's
 *   observed wall time.
 * - Every fix counts, including fixes suppressed inside a privacy zone (logging is paused there, so
 *   the recorder drops the events anyway).
 *
 * Owner: workstream `location-privacy-core`.
 */
class GpsEventDeriver(private val lostAfterMs: Long = 5_000) {
    fun onFix(fix: FixSample): EventRow? = TODO("location-privacy-core")

    fun onTick(nowWallMs: Long, nowElapsedMs: Long): EventRow? = TODO("location-privacy-core")
}

/**
 * track.csv rows: `time_utc` is `fix.wallMs`; accuracy, altitude, speed blank when null or outside
 * [com.fieldtap.format.Ranges]; provider from the fix.
 *
 * Owner: workstream `location-privacy-core`.
 */
object TrackRows {
    fun of(fix: FixSample): TrackRow = TODO("location-privacy-core")
}
