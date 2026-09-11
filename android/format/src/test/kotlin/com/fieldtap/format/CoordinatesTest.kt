package com.fieldtap.format

import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinatesTest {

    @Test
    fun formatWritesSevenDecimals() {
        assertEquals("38.8895000", Coordinates.format(38.8895))
        assertEquals("-77.0353323", Coordinates.format(-77.0353323))
        assertEquals("180.0000000", Coordinates.format(180.0))
        assertEquals("-90.0000000", Coordinates.format(-90.0))
        assertEquals("0.0000000", Coordinates.format(0.0))
    }

    @Test
    fun formatNeverWritesNegativeZero() {
        assertEquals("0.0000000", Coordinates.format(-0.0))
        assertEquals("0.0000000", Coordinates.format(-0.00000001))
    }

    @Test
    fun formatIsBlankForNonFiniteValues() {
        assertEquals("", Coordinates.format(Double.NaN))
        assertEquals("", Coordinates.format(Double.POSITIVE_INFINITY))
    }

    @Test
    fun approx110mRoundsToThreeDecimalsWrittenWithSeven() {
        assertEquals("38.8900000", Coordinates.format(Coordinates.approx110m(38.8895123)))
        assertEquals("-77.0350000", Coordinates.format(Coordinates.approx110m(-77.0353323)))
        assertEquals(38.89, Coordinates.approx110m(38.8895123), 0.0)
    }

    @Test
    fun approx110mRoundsTheExactBinaryValue() {
        // 38.8895 and 12.3455 are stored just below the tie, 38.8905 just above it.
        assertEquals("38.8890000", Coordinates.format(Coordinates.approx110m(38.8895)))
        assertEquals("38.8910000", Coordinates.format(Coordinates.approx110m(38.8905)))
        assertEquals("12.3450000", Coordinates.format(Coordinates.approx110m(12.3455)))
        // Rounding the shortest decimal form instead gives other digits.
        assertEquals("38.890", BigDecimal.valueOf(38.8895).setScale(3, RoundingMode.HALF_EVEN).toPlainString())
        assertEquals("12.346", BigDecimal.valueOf(12.3455).setScale(3, RoundingMode.HALF_UP).toPlainString())
    }

    @Test
    fun approx110mNeverReturnsNegativeZero() {
        val rounded = Coordinates.approx110m(-0.0004)
        assertEquals(0.0, rounded, 0.0)
        assertEquals(Double.POSITIVE_INFINITY, 1.0 / rounded, 0.0)
        assertEquals("0.0000000", Coordinates.format(rounded))
    }

    @Test
    fun approx110mKeepsNonFiniteValues() {
        assertTrue(Coordinates.approx110m(Double.NaN).isNaN())
        assertEquals(Double.NEGATIVE_INFINITY, Coordinates.approx110m(Double.NEGATIVE_INFINITY), 0.0)
    }

    @Test
    fun approx110mIsIdempotent() {
        for (value in listOf(38.8895123, -77.0353323, 0.0005, 89.9999, -179.9995)) {
            val once = Coordinates.approx110m(value)
            assertEquals(once, Coordinates.approx110m(once), 0.0)
        }
    }

    @Test
    fun nullIslandIsExactlyZeroZero() {
        assertTrue(Coordinates.isNullIsland(0.0, 0.0))
        assertTrue(Coordinates.isNullIsland(-0.0, 0.0))
        assertFalse(Coordinates.isNullIsland(0.0, 1e-9))
        assertFalse(Coordinates.isNullIsland(38.8895, -77.0353))
        assertFalse(Coordinates.isNullIsland(Double.NaN, 0.0))
    }
}
