package com.fieldtap.ui.theme

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FormatsTest {
    private val us = Locale.US
    private val german = Locale.GERMANY

    @Test
    fun ageHasOneDecimalBelowTenSecondsRoundingHalfUp() {
        assertEquals("0.0", Formats.ageSeconds(0, us))
        assertEquals("0.0", Formats.ageSeconds(49, us))
        assertEquals("0.1", Formats.ageSeconds(50, us))
        assertEquals("2.0", Formats.ageSeconds(2_049, us))
        assertEquals("2.1", Formats.ageSeconds(2_050, us))
        assertEquals("2.5", Formats.ageSeconds(2_500, us))
        assertEquals("9.9", Formats.ageSeconds(9_949, us))
    }

    @Test
    fun ageIsWholeSecondsFromNineAndAHalfTenths() {
        assertEquals("10", Formats.ageSeconds(9_950, us))
        assertEquals("11", Formats.ageSeconds(11_000, us))
        assertEquals("75", Formats.ageSeconds(75_400, us))
        assertEquals("76", Formats.ageSeconds(75_500, us))
    }

    @Test
    fun negativeAgeIsZeroAndSeparatorFollowsLocale() {
        assertEquals("0.0", Formats.ageSeconds(-5, us))
        assertEquals("2,1", Formats.ageSeconds(2_100, german))
    }

    @Test
    fun elapsedIsMinutesSecondsThenHours() {
        assertEquals("0:00", Formats.elapsed(0))
        assertEquals("0:00", Formats.elapsed(-1))
        assertEquals("0:05", Formats.elapsed(5_999))
        assertEquals("1:05", Formats.elapsed(65_000))
        assertEquals("12:34", Formats.elapsed(754_000))
        assertEquals("59:59", Formats.elapsed(3_599_999))
        assertEquals("1:00:00", Formats.elapsed(3_600_000))
        assertEquals("1:02:03", Formats.elapsed(3_723_000))
        assertEquals("10:00:00", Formats.elapsed(36_000_000))
    }

    @Test
    fun bytesUseDecimalUnits() {
        assertEquals("0 B", Formats.decimalBytes(0, us))
        assertEquals("0 B", Formats.decimalBytes(-10, us))
        assertEquals("999 B", Formats.decimalBytes(999, us))
        assertEquals("1.0 kB", Formats.decimalBytes(1_000, us))
        assertEquals("4.2 kB", Formats.decimalBytes(4_200, us))
        assertEquals("99.9 kB", Formats.decimalBytes(99_949, us))
        assertEquals("100 kB", Formats.decimalBytes(99_950, us))
        assertEquals("999 kB", Formats.decimalBytes(999_499, us))
        assertEquals("1.0 MB", Formats.decimalBytes(999_500, us))
        assertEquals("4.2 MB", Formats.decimalBytes(4_200_000, us))
        assertEquals("250 MB", Formats.decimalBytes(250_000_000, us))
        assertEquals("1.9 GB", Formats.decimalBytes(1_949_999_999, us))
        assertEquals("2.0 GB", Formats.decimalBytes(2_000_000_000, us))
        assertEquals("4,2 MB", Formats.decimalBytes(4_200_000, german))
    }

    @Test
    fun oneDecimalRoundsHalfUpWithoutNegativeZero() {
        assertEquals("88.3", Formats.oneDecimal(88.25, us))
        assertEquals("88.3", Formats.oneDecimal(88.3, us))
        assertEquals("0.1", Formats.oneDecimal(0.05, us))
        assertEquals("0.0", Formats.oneDecimal(-0.04, us))
        assertEquals("-1.5", Formats.oneDecimal(-1.45, us))
        assertEquals("88,3", Formats.oneDecimal(88.25, german))
        assertThrows(IllegalArgumentException::class.java) { Formats.oneDecimal(Double.NaN, us) }
    }
}
