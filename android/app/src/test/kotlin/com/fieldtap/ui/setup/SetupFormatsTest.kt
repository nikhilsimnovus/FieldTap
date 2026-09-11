package com.fieldtap.ui.setup

import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupFormatsTest {
    /** 2026-09-10T14:30:00Z. */
    private val now = 1_789_050_600_000L

    @Test
    fun datesAndTimesFollowTheLocaleAndTimeZone() {
        assertEquals("10.09.2026", SetupFormats.date(now, ZoneOffset.UTC, Locale.GERMANY))
        assertEquals("14:30", SetupFormats.time(now, ZoneOffset.UTC, Locale.GERMANY))
        assertEquals("16:30", SetupFormats.time(now, ZoneId.of("Europe/Berlin"), Locale.GERMANY))
        val both = SetupFormats.dateTime(now, ZoneOffset.UTC, Locale.GERMANY)
        assertTrue(both, both.contains("10.09.2026") && both.contains("14:30"))
    }

    @Test
    fun aLateUtcTimeIsTheNextDayFurtherEast() {
        val lateUtc = now + 9 * 3_600_000L + 45 * 60_000L // 2026-09-11T00:15Z
        assertEquals("11.09.2026", SetupFormats.date(lateUtc, ZoneOffset.UTC, Locale.GERMANY))
        assertEquals("10.09.2026", SetupFormats.date(lateUtc, ZoneId.of("America/New_York"), Locale.GERMANY))
    }

    @Test
    fun metresHaveAtMostOneDecimalAndNoTrailingZero() {
        assertEquals("200", SetupFormats.metres(200.0))
        assertEquals("150.6", SetupFormats.metres(150.55))
        assertEquals("5000", SetupFormats.metres(5_000.0))
        assertEquals("12.5", SetupFormats.metres(12.5))
        assertEquals("0", SetupFormats.metres(0.04))
        assertEquals("0", SetupFormats.metres(-0.0))
        assertEquals("NaN", SetupFormats.metres(Double.NaN))
    }

    @Test
    fun coordinatesUseAPointInEveryLocaleAndNeverMinusZero() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("52.52001", SetupFormats.coordinate(52.5200081))
            assertEquals("-33.86880", SetupFormats.coordinate(-33.8688))
            assertEquals("0.00000", SetupFormats.coordinate(-0.000001))
            assertEquals("13.404954", SetupFormats.coordinate(13.404954, SetupFormats.EDIT_COORDINATE_DECIMALS))
            assertEquals("Infinity", SetupFormats.coordinate(Double.POSITIVE_INFINITY))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
