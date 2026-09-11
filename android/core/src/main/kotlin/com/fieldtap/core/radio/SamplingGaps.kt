package com.fieldtap.core.radio

import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.format.CollectionMeta
import com.fieldtap.format.EventRow
import com.fieldtap.format.GapMeta

/** Tokens for `collection.gaps[].reason` and the sampling_gap event's `cause`. */
object GapReasons {
    /** Our own 1 s ticker stalled during the gap: the process was frozen or dozing. */
    const val APP_PAUSED: String = "app_paused"

    /** Service state was out of service or emergency-only at some point in the gap. */
    const val NO_SERVICE: String = "no_service"

    /** The screen was off at some answer in the gap. */
    const val SCREEN_OFF: String = "screen_off"

    const val UNKNOWN: String = "unknown"
}

/** One detected sampling gap. */
data class SamplingGap(
    /** Measurement time of the last fresh sample before the gap. */
    val startUtcMs: Long,
    /** Measurement time of the first fresh sample after it. */
    val stopUtcMs: Long,
    val reason: String,
    /** Wall clock when the ending answer arrived: the event's `time_utc`. */
    val observedWallMs: Long,
) {
    fun toMeta(): GapMeta = GapMeta(startUtcMs, stopUtcMs, reason)

    /**
     * `sampling_gap`, rat `-`, severity `warn`, title `Sampling gap`, detail
     * `no fresh cell info for 14.0 s` (seconds with one decimal), cause [reason], time [observedWallMs].
     */
    fun toEvent(): EventRow = TODO("radio-core")
}

/**
 * Sampling-gap detection.
 *
 * - A gap is found at a fresh answer whose primary measurement time is more than twice the
 *   interval in force at the previous fresh answer (by [CadencePolicy.intervalMs] of that answer's
 *   conditions) after the previous fresh answer's measurement time. It is reported when it ends; a
 *   session that stops inside a gap reports none.
 * - Reason, first that applies: [GapReasons.APP_PAUSED] if [onTick] saw two ticks more than 3000 ms
 *   apart inside the gap; [GapReasons.NO_SERVICE] if service state was out of service or
 *   emergency-only inside it; [GapReasons.SCREEN_OFF] if any answer inside it had the screen off;
 *   else [GapReasons.UNKNOWN].
 * - [reset] forgets the previous fresh answer; the pipeline calls it when logging resumes after a
 *   privacy-zone pause, so no gap straddles a pause.
 *
 * Tests: the golden gap (fresh at 90.4 s, next at 104.4 s, screen off, 2 s interval -> 14.0 s
 * `screen_off`, event at 104.9 s); a 10 s cadence with 12 s spacing is not a gap; tick stall.
 *
 * Owner: workstream `radio-core`.
 */
class SamplingGapDetector {
    /** Every answer, fresh or not. Returns the gap this answer ends, if any. */
    fun onAnswer(classified: ClassifiedAnswer): SamplingGap? = TODO("radio-core")

    fun onServiceState(state: ServiceStateSnapshot): Unit = TODO("radio-core")

    /** Called once a second by the session ticker. */
    fun onTick(nowElapsedMs: Long): Unit = TODO("radio-core")

    fun reset(): Unit = TODO("radio-core")
}

/**
 * The `collection` object of session.json.
 *
 * - `fresh_samples`: answers with `fresh`; `repeats_dropped`: answers with `repeat`.
 * - `median_fresh_interval_ms`: the intervals between consecutive fresh answers' primary measurement
 *   times (or the newest fresh cell's, with no primary), sorted, element at index `size / 2`; null with
 *   fewer than two fresh answers. (The golden session gives 2000.)
 * - `short_interval_pct`, `screen_on_pct`, `wifi_connected_pct`, `charging_pct`: percentage of
 *   `requestCellInfoUpdate` answers (source `request` only) whose conditions had that property; null
 *   with no request answers. Unrounded; session.json writes one decimal.
 * - `gaps`: every [SamplingGap] passed to [onGap], in order.
 * - Counts only what the pipeline passes while writing.
 *
 * Tests: golden values (54 fresh, 66 repeats, 88.3 short interval, 88.3 screen on, 0.0 Wi-Fi and
 * charging, one gap).
 *
 * Owner: workstream `radio-core`.
 */
class CollectionStats {
    fun onAnswer(classified: ClassifiedAnswer): Unit = TODO("radio-core")

    fun onGap(gap: SamplingGap): Unit = TODO("radio-core")

    fun snapshot(): CollectionMeta = TODO("radio-core")
}
