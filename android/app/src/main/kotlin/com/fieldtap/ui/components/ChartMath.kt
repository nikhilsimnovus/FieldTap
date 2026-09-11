package com.fieldtap.ui.components

import com.fieldtap.core.live.ChartPoint
import com.fieldtap.core.radio.CadencePolicy
import com.fieldtap.ui.theme.SignalScale

/** Latest, lowest and highest value of a series in the chart window, for labels and TalkBack. */
data class SeriesStats(val latest: Int, val min: Int, val max: Int, val count: Int)

/**
 * The arithmetic behind [TimeSeriesChart] and [SignalHistoryChart], pure so it is unit-tested.
 *
 * Points are fresh samples only (the Live reducer never adds repeats). A line is broken wherever two
 * consecutive points are more than the gap threshold apart, so a sampling gap is never bridged by a
 * line that suggests measurements that did not happen.
 */
object ChartMath {
    /** Twice Android's long (10 s) interval plus 1 s of slack. */
    val DEFAULT_GAP_THRESHOLD_MS: Long = 2 * CadencePolicy.LONG_INTERVAL_MS + 1_000

    /**
     * The break threshold for the interval in force: 5 s on the 2 s interval, 21 s on the 10 s interval
     * or before the first answer. Mirrors the sampling-gap rule (more than twice the interval).
     */
    fun gapThresholdMs(shortInterval: Boolean?): Long {
        val interval = if (shortInterval == true) CadencePolicy.SHORT_INTERVAL_MS else CadencePolicy.LONG_INTERVAL_MS
        return 2 * interval + 1_000
    }

    /** Points at or after `now - windowMs`, in time order (stable for equal times). */
    fun visible(points: List<ChartPoint>, nowElapsedMs: Long, windowMs: Long): List<ChartPoint> {
        val start = nowElapsedMs - windowMs
        return points.filter { it.elapsedMs >= start }.sortedBy { it.elapsedMs }
    }

    /** [visible] points split into runs with no step longer than [gapThresholdMs]. */
    fun segments(
        points: List<ChartPoint>,
        nowElapsedMs: Long,
        windowMs: Long,
        gapThresholdMs: Long,
    ): List<List<ChartPoint>> {
        val shown = visible(points, nowElapsedMs, windowMs)
        if (shown.isEmpty()) return emptyList()
        val result = mutableListOf<List<ChartPoint>>()
        var current = mutableListOf(shown.first())
        for (i in 1 until shown.size) {
            val point = shown[i]
            if (point.elapsedMs - current.last().elapsedMs > gapThresholdMs) {
                result += current
                current = mutableListOf()
            }
            current += point
        }
        result += current
        return result
    }

    /** Horizontal position: 0 at the window start, 1 at now, clamped. */
    fun xFraction(elapsedMs: Long, nowElapsedMs: Long, windowMs: Long): Float {
        if (windowMs <= 0) return 1f
        val start = nowElapsedMs - windowMs
        return ((elapsedMs - start).toDouble() / windowMs).toFloat().coerceIn(0f, 1f)
    }

    /** Vertical position: 0 at the bottom of [range], 1 at the top, clamped. */
    fun yFraction(value: Int, range: IntRange): Float = SignalScale.fraction(value, range)

    /** Stats of the [visible] points, or null when there are none. */
    fun stats(points: List<ChartPoint>, nowElapsedMs: Long, windowMs: Long): SeriesStats? {
        val shown = visible(points, nowElapsedMs, windowMs)
        if (shown.isEmpty()) return null
        return SeriesStats(
            latest = shown.last().value,
            min = shown.minOf { it.value },
            max = shown.maxOf { it.value },
            count = shown.size,
        )
    }
}
