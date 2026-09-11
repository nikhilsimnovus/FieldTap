package com.fieldtap.ui.components

import com.fieldtap.core.live.ChartPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {
    private val now = 300_000L
    private val window = 300_000L

    private fun p(ms: Long, v: Int) = ChartPoint(elapsedMs = ms, value = v)

    @Test
    fun gapThresholdFollowsTheInterval() {
        assertEquals(5_000L, ChartMath.gapThresholdMs(shortInterval = true))
        assertEquals(21_000L, ChartMath.gapThresholdMs(shortInterval = false))
        assertEquals(21_000L, ChartMath.gapThresholdMs(shortInterval = null))
        assertEquals(21_000L, ChartMath.DEFAULT_GAP_THRESHOLD_MS)
    }

    @Test
    fun visibleKeepsTheWindowInTimeOrder() {
        val points = listOf(p(4_000, -90), p(-1, -80), p(0, -85), p(2_000, -88))
        assertEquals(listOf(p(0, -85), p(2_000, -88), p(4_000, -90)), ChartMath.visible(points, now, window))
    }

    @Test
    fun segmentsBreakOnlyWhenAStepExceedsTheThreshold() {
        val points = listOf(p(0, -90), p(2_000, -91), p(7_000, -92), p(12_001, -93), p(14_000, -94))
        val segments = ChartMath.segments(points, now, window, gapThresholdMs = 5_000)
        assertEquals(2, segments.size)
        assertEquals(listOf(p(0, -90), p(2_000, -91), p(7_000, -92)), segments[0])
        assertEquals(listOf(p(12_001, -93), p(14_000, -94)), segments[1])
        assertEquals(1, ChartMath.segments(points, now, window, gapThresholdMs = 21_000).size)
    }

    @Test
    fun anIsolatedPointIsItsOwnSegment() {
        val points = listOf(p(0, -90), p(60_000, -95), p(120_000, -99), p(122_000, -98))
        val segments = ChartMath.segments(points, now, window, gapThresholdMs = 5_000)
        assertEquals(listOf(1, 1, 2), segments.map { it.size })
    }

    @Test
    fun noPointsNoSegments() {
        assertTrue(ChartMath.segments(emptyList(), now, window, 5_000).isEmpty())
        assertTrue(ChartMath.segments(listOf(p(-10, -90)), now, window, 5_000).isEmpty())
    }

    @Test
    fun xFractionSpansTheWindowAndClamps() {
        assertEquals(0f, ChartMath.xFraction(0, now, window), 0f)
        assertEquals(1f, ChartMath.xFraction(now, now, window), 0f)
        assertEquals(0.5f, ChartMath.xFraction(150_000, now, window), 1e-6f)
        assertEquals(0f, ChartMath.xFraction(-50_000, now, window), 0f)
        assertEquals(1f, ChartMath.xFraction(now + 1_000, now, window), 0f)
        assertEquals(1f, ChartMath.xFraction(123, now, 0), 0f)
    }

    @Test
    fun yFractionUsesTheDisplayRange() {
        assertEquals(0.65f, ChartMath.yFraction(-75, -140..-40), 1e-6f)
        assertEquals(0f, ChartMath.yFraction(-150, -140..-40), 0f)
    }

    @Test
    fun statsUseTheNewestPointAsLatest() {
        val points = listOf(p(10_000, -80), p(2_000, -104), p(6_000, -92), p(-5, -60))
        val stats = ChartMath.stats(points, now, window)
        assertEquals(SeriesStats(latest = -80, min = -104, max = -80, count = 3), stats)
        assertNull(ChartMath.stats(emptyList(), now, window))
    }
}
