package com.fieldtap.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SignalScaleTest {
    private fun rsrp(value: Int) = SignalScale.quality(SignalMetric.RSRP, value)

    @Test
    fun rsrpBoundariesMatchTheReportRouteColours() {
        assertEquals(SignalQuality.EXCELLENT, rsrp(-44))
        assertEquals(SignalQuality.EXCELLENT, rsrp(-85))
        assertEquals(SignalQuality.GOOD, rsrp(-86))
        assertEquals(SignalQuality.GOOD, rsrp(-95))
        assertEquals(SignalQuality.FAIR, rsrp(-96))
        assertEquals(SignalQuality.FAIR, rsrp(-105))
        assertEquals(SignalQuality.POOR, rsrp(-106))
        assertEquals(SignalQuality.POOR, rsrp(-140))
    }

    @Test
    fun rsrqBoundaries() {
        val q = { v: Int -> SignalScale.quality(SignalMetric.RSRQ, v) }
        assertEquals(SignalQuality.EXCELLENT, q(-3))
        assertEquals(SignalQuality.EXCELLENT, q(-10))
        assertEquals(SignalQuality.GOOD, q(-11))
        assertEquals(SignalQuality.GOOD, q(-15))
        assertEquals(SignalQuality.FAIR, q(-16))
        assertEquals(SignalQuality.FAIR, q(-20))
        assertEquals(SignalQuality.POOR, q(-21))
    }

    @Test
    fun sinrBoundaries() {
        val q = { v: Int -> SignalScale.quality(SignalMetric.SINR, v) }
        assertEquals(SignalQuality.EXCELLENT, q(30))
        assertEquals(SignalQuality.EXCELLENT, q(20))
        assertEquals(SignalQuality.GOOD, q(19))
        assertEquals(SignalQuality.GOOD, q(13))
        assertEquals(SignalQuality.FAIR, q(12))
        assertEquals(SignalQuality.FAIR, q(0))
        assertEquals(SignalQuality.POOR, q(-1))
    }

    @Test
    fun unknownValuesHaveNoQuality() {
        assertNull(SignalScale.quality(SignalMetric.RSRP, null))
        assertNull(SignalScale.quality(SignalMetric.SINR, Double.NaN))
    }

    @Test
    fun decimalsUseTheSameInclusiveLowerBounds() {
        assertEquals(SignalQuality.EXCELLENT, SignalScale.quality(SignalMetric.RSRP, -85.0))
        assertEquals(SignalQuality.GOOD, SignalScale.quality(SignalMetric.RSRP, -85.4))
        assertEquals(SignalQuality.FAIR, SignalScale.quality(SignalMetric.RSRP, -105.0))
        assertEquals(SignalQuality.POOR, SignalScale.quality(SignalMetric.RSRP, -105.01))
    }

    @Test
    fun displayRangesAreTheReportAxes() {
        assertEquals(-140..-40, SignalScale.displayRange(SignalMetric.RSRP))
        assertEquals(-25..40, SignalScale.displayRange(SignalMetric.SINR))
        assertEquals(-30..0, SignalScale.displayRange(SignalMetric.RSRQ))
    }

    @Test
    fun keyReferenceIsTheReportLine() {
        assertEquals(-105, SignalScale.keyReference(SignalMetric.RSRP))
        assertEquals(-15, SignalScale.keyReference(SignalMetric.RSRQ))
        assertEquals(0, SignalScale.keyReference(SignalMetric.SINR))
    }

    @Test
    fun fractionIsClampedToTheRange() {
        assertEquals(0f, SignalScale.fraction(SignalMetric.RSRP, -140), 0f)
        assertEquals(1f, SignalScale.fraction(SignalMetric.RSRP, -40), 0f)
        assertEquals(0.5f, SignalScale.fraction(SignalMetric.RSRP, -90), 1e-6f)
        assertEquals(0f, SignalScale.fraction(SignalMetric.RSRP, -156), 0f)
        assertEquals(1f, SignalScale.fraction(SignalMetric.RSRP, -29), 0f)
        assertEquals(0f, SignalScale.fraction(5, 5..5), 0f)
    }

    @Test
    fun thresholdsMustFallStrictly() {
        assertThrows(IllegalArgumentException::class.java) { SignalThresholds(-90, -90, -100) }
        assertThrows(IllegalArgumentException::class.java) { SignalThresholds(-100, -95, -90) }
        assertEquals(listOf(-85, -95, -105), SignalScale.RSRP_THRESHOLDS.boundaries)
    }

    @Test
    fun barsFallWithQuality() {
        assertEquals(listOf(4, 3, 2, 1), SignalQuality.entries.map { it.bars })
    }
}
