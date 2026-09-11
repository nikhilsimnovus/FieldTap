package com.fieldtap.core.radio

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.CollectionMeta
import com.fieldtap.format.EventKind
import com.fieldtap.format.EventRat
import com.fieldtap.format.EventRow
import com.fieldtap.format.GapMeta
import com.fieldtap.format.Severity
import java.math.BigDecimal
import java.math.RoundingMode

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
     * The seconds are rounded as session.json rounds `gaps[].seconds`: half-even on the exact binary
     * value of `(stop - start) / 1000.0`, as Python's `"%.1f"`.
     */
    fun toEvent(): EventRow = EventRow(
        timeUtcMs = observedWallMs,
        rat = EventRat.NONE,
        kind = EventKind.SAMPLING_GAP,
        severity = Severity.WARN,
        title = "Sampling gap",
        detail = "no fresh cell info for ${secondsText()} s",
        cause = reason,
    )

    private fun secondsText(): String =
        BigDecimal((stopUtcMs - startUtcMs) / 1000.0).setScale(1, RoundingMode.HALF_EVEN).toPlainString()
}

/**
 * Sampling-gap detection.
 *
 * - A gap is found at a fresh answer whose primary measurement time is more than twice the interval
 *   in force when the gap began after the previous fresh answer's measurement time. It is reported
 *   when it ends; a session that stops inside a gap reports none. Measurement times are compared on
 *   elapsedRealtime (`timestampMs`); with no primary, the newest fresh cell stands in.
 * - The interval in force when the gap began is [CadencePolicy.intervalMs] of the conditions of the
 *   newest recent answer that arrived at or before the previous fresh sample was measured; when every
 *   recent answer arrived later, of the oldest of them, the nearest to that measurement; with no recent
 *   answer at all, of the previous fresh answer itself. The sample was measured under that cadence: when
 *   the screen goes off right after a 2 s sample, as in the golden session, the gap is judged against
 *   2 s, not against the 10 s that the answer carrying the sample already reports; and a sample measured
 *   with the screen off is judged against 10 s even when the screen came on before its answer arrived.
 *   (Judging against "the conditions of the previous fresh answer" finds no gap in the golden session;
 *   the golden session wins, see android/ARCHITECTURE.md sections 6.2 and 13.)
 * - A fresh answer whose measurement time is not after the previous one's neither ends a gap nor
 *   moves the reference.
 * - Reason, first that applies: [GapReasons.APP_PAUSED] if [onTick] saw two ticks more than 3000 ms
 *   apart, overlapping the gap by more than 3000 ms (a stall still running when the ending answer
 *   arrives counts up to that answer); [GapReasons.NO_SERVICE] if service state was out of service or
 *   emergency-only at some point inside it; [GapReasons.SCREEN_OFF] if any answer between the previous
 *   fresh answer (inclusive) and the ending one (exclusive) had the screen off; else [GapReasons.UNKNOWN].
 *   `UNKNOWN` service states neither start nor end a no-service period.
 * - [reset] forgets the previous fresh answer; the pipeline calls it when logging resumes after a
 *   privacy-zone pause, so no gap straddles a pause. The current service state and the last tick
 *   describe the present and are kept.
 *
 * Not thread-safe: called on the session dispatcher only.
 *
 * Tests: the golden gap (fresh at 90.4 s, next at 104.4 s, screen off, 2 s interval -> 14.0 s
 * `screen_off`, event at 104.9 s); a 10 s cadence with 12 s spacing is not a gap; tick stall.
 *
 * Owner: workstream `radio-core`.
 */
class SamplingGapDetector {
    private var reference: FreshReference? = null
    private val recentAnswers = ArrayDeque<AnswerMark>(RECENT_ANSWERS + 1)
    private var screenOffInWindow: Boolean = false
    private var lastTickElapsedMs: Long? = null
    private val stalls = ArrayDeque<Span>()
    private var serviceBadSinceMs: Long? = null
    private val badServiceSpans = ArrayDeque<Span>()

    /** Every answer, fresh or not. Returns the gap this answer ends, if any. */
    fun onAnswer(classified: ClassifiedAnswer): SamplingGap? {
        val answer = classified.answer
        var gap: SamplingGap? = null
        val cell = classified.freshReference()
        if (cell != null) {
            val measuredMs = cell.cell.timestampMs
            val previous = reference
            if (previous == null || measuredMs > previous.measurementElapsedMs) {
                if (previous != null && measuredMs - previous.measurementElapsedMs > 2 * previous.intervalMs) {
                    gap = SamplingGap(
                        startUtcMs = previous.measurementWallMs,
                        stopUtcMs = cell.measurementWallMs,
                        reason = reasonFor(previous.measurementElapsedMs, measuredMs, answer.observedElapsedMs),
                        observedWallMs = answer.observedWallMs,
                    )
                }
                reference = FreshReference(
                    measurementElapsedMs = measuredMs,
                    measurementWallMs = cell.measurementWallMs,
                    intervalMs = intervalInForce(measuredMs, answer),
                )
                startWindow(measuredMs)
            }
        }
        if (!answer.conditions.screenOn) screenOffInWindow = true
        recentAnswers.addLast(AnswerMark(answer.observedElapsedMs, CadencePolicy.intervalMs(answer.conditions)))
        while (recentAnswers.size > RECENT_ANSWERS) recentAnswers.removeFirst()
        return gap
    }

    fun onServiceState(state: ServiceStateSnapshot) {
        val bad = ServingCellSelector.isOutOfService(state) || ServingCellSelector.isEmergencyOnly(state)
        val since = serviceBadSinceMs
        if (bad) {
            if (since == null) serviceBadSinceMs = state.observedElapsedMs
        } else if (state.state == ServiceRegState.IN_SERVICE && since != null) {
            addSpan(badServiceSpans, Span(since, maxOf(since, state.observedElapsedMs)))
            serviceBadSinceMs = null
        }
    }

    /** Called once a second by the session ticker. */
    fun onTick(nowElapsedMs: Long) {
        val last = lastTickElapsedMs
        if (last != null && nowElapsedMs - last > TICK_STALL_MS) addSpan(stalls, Span(last, nowElapsedMs))
        if (last == null || nowElapsedMs > last) lastTickElapsedMs = nowElapsedMs
    }

    fun reset() {
        reference = null
        recentAnswers.clear()
        screenOffInWindow = false
        stalls.clear()
        badServiceSpans.clear()
    }

    private fun intervalInForce(measuredMs: Long, answer: CellInfoAnswer): Long {
        val nearest = recentAnswers.lastOrNull { it.observedElapsedMs <= measuredMs } ?: recentAnswers.firstOrNull()
        return nearest?.intervalMs ?: CadencePolicy.intervalMs(answer.conditions)
    }

    private fun reasonFor(startMs: Long, stopMs: Long, answerObservedMs: Long): String = when {
        stalledInside(startMs, stopMs, answerObservedMs) -> GapReasons.APP_PAUSED
        serviceBadInside(startMs, stopMs) -> GapReasons.NO_SERVICE
        screenOffInWindow -> GapReasons.SCREEN_OFF
        else -> GapReasons.UNKNOWN
    }

    private fun stalledInside(startMs: Long, stopMs: Long, answerObservedMs: Long): Boolean {
        if (stalls.any { overlapMs(it, startMs, stopMs) > TICK_STALL_MS }) return true
        val lastTick = lastTickElapsedMs ?: return false
        return answerObservedMs - lastTick > TICK_STALL_MS &&
            overlapMs(Span(lastTick, answerObservedMs), startMs, stopMs) > TICK_STALL_MS
    }

    private fun serviceBadInside(startMs: Long, stopMs: Long): Boolean {
        val since = serviceBadSinceMs
        if (since != null && since < stopMs) return true
        return badServiceSpans.any { it.startMs < stopMs && it.endMs > startMs }
    }

    private fun startWindow(startMs: Long) {
        screenOffInWindow = false
        stalls.removeAll { it.endMs <= startMs }
        badServiceSpans.removeAll { it.endMs <= startMs }
    }

    private fun addSpan(spans: ArrayDeque<Span>, span: Span) {
        spans.addLast(span)
        if (spans.size > MAX_SPANS) {
            // Merging the two oldest keeps every moment they covered; only the time between them is added.
            val first = spans.removeFirst()
            val second = spans.removeFirst()
            spans.addFirst(Span(minOf(first.startMs, second.startMs), maxOf(first.endMs, second.endMs)))
        }
    }

    private fun overlapMs(span: Span, startMs: Long, stopMs: Long): Long =
        (minOf(span.endMs, stopMs) - maxOf(span.startMs, startMs)).coerceAtLeast(0L)

    private data class FreshReference(
        val measurementElapsedMs: Long,
        val measurementWallMs: Long,
        /** The interval in force when this sample was measured. */
        val intervalMs: Long,
    )

    private data class AnswerMark(val observedElapsedMs: Long, val intervalMs: Long)

    private data class Span(val startMs: Long, val endMs: Long)

    private companion object {
        /** Ticks further apart than this mean the process did not run. */
        const val TICK_STALL_MS: Long = 3_000

        /**
         * How many answers are kept to find the one before a fresh sample's measurement: at one request a
         * second, pushes included, this reaches back well past the oldest sample kpi.csv accepts (11 s).
         */
        const val RECENT_ANSWERS: Int = 32

        /** Stall and no-service periods kept per window before the oldest are merged. */
        const val MAX_SPANS: Int = 64
    }
}

/**
 * The `collection` object of session.json.
 *
 * - `fresh_samples`: answers with `fresh`; `repeats_dropped`: answers with `repeat`.
 * - `median_fresh_interval_ms`: the intervals between consecutive fresh answers' primary measurement
 *   times (or the newest fresh cell's, with no primary), sorted, element at index `size / 2`; null with
 *   fewer than two fresh answers. (The golden session gives 2000.) Measurement times are compared on
 *   elapsedRealtime; a fresh answer measured no later than the previous one adds no interval.
 * - `short_interval_pct`, `screen_on_pct`, `wifi_connected_pct`, `charging_pct`: percentage of
 *   `requestCellInfoUpdate` answers (source `request` only) whose conditions had that property; null
 *   with no request answers. Unrounded; session.json writes one decimal.
 * - `gaps`: every [SamplingGap] passed to [onGap], in order.
 * - Counts only what the pipeline passes while writing. [onResume] breaks the interval chain, so no
 *   interval straddles a privacy-zone pause.
 *
 * Not thread-safe: called on the session dispatcher only.
 *
 * Tests: golden values (54 fresh, 66 repeats, 88.3 short interval, 88.3 screen on, 0.0 Wi-Fi and
 * charging, one gap).
 *
 * Owner: workstream `radio-core`.
 */
class CollectionStats {
    private var freshSamples: Long = 0
    private var repeatsDropped: Long = 0
    private var requestAnswers: Long = 0
    private var shortIntervalAnswers: Long = 0
    private var screenOnAnswers: Long = 0
    private var wifiConnectedAnswers: Long = 0
    private var chargingAnswers: Long = 0
    private var previousFreshElapsedMs: Long? = null
    private var intervals: LongArray = LongArray(INITIAL_INTERVALS)
    private var intervalCount: Int = 0
    private val gaps = ArrayList<GapMeta>()

    fun onAnswer(classified: ClassifiedAnswer) {
        if (classified.fresh) freshSamples += 1
        if (classified.repeat) repeatsDropped += 1
        val answer = classified.answer
        if (answer.source == CellInfoSource.REQUEST) {
            val conditions = answer.conditions
            requestAnswers += 1
            if (CadencePolicy.isShortInterval(conditions)) shortIntervalAnswers += 1
            if (conditions.screenOn) screenOnAnswers += 1
            if (conditions.wifiConnected) wifiConnectedAnswers += 1
            if (conditions.charging) chargingAnswers += 1
        }
        val reference = classified.freshReference() ?: return
        val measuredMs = reference.cell.timestampMs
        val previous = previousFreshElapsedMs
        if (previous == null || measuredMs > previous) {
            if (previous != null) addInterval(measuredMs - previous)
            previousFreshElapsedMs = measuredMs
        }
    }

    fun onGap(gap: SamplingGap) {
        gaps.add(gap.toMeta())
    }

    /** Forgets the previous fresh answer: the next interval starts after the pause. */
    fun onResume() {
        previousFreshElapsedMs = null
    }

    fun snapshot(): CollectionMeta {
        val median = if (intervalCount == 0) {
            null
        } else {
            val sorted = intervals.copyOf(intervalCount)
            sorted.sort()
            sorted[intervalCount / 2]
        }
        return CollectionMeta(
            medianFreshIntervalMs = median,
            shortIntervalPct = percentOfRequests(shortIntervalAnswers),
            screenOnPct = percentOfRequests(screenOnAnswers),
            wifiConnectedPct = percentOfRequests(wifiConnectedAnswers),
            chargingPct = percentOfRequests(chargingAnswers),
            freshSamples = freshSamples,
            repeatsDropped = repeatsDropped,
            gaps = gaps.toList(),
        )
    }

    private fun percentOfRequests(count: Long): Double? =
        if (requestAnswers == 0L) null else 100.0 * count / requestAnswers

    private fun addInterval(intervalMs: Long) {
        if (intervalCount == intervals.size) intervals = intervals.copyOf(intervals.size * 2)
        intervals[intervalCount] = intervalMs
        intervalCount += 1
    }

    private companion object {
        const val INITIAL_INTERVALS: Int = 256
    }
}
