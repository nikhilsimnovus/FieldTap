package com.fieldtap.ui.common

import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** How a list row names a start day, and the localized pieces it is built from. */
class DisplayTimeDaysTest {
    /** 2026-09-11 18:19 UTC, a Friday. */
    private val now = ZonedDateTime.of(2026, 9, 11, 18, 19, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun daysBefore(days: Long, hour: Int = 9): Long =
        ZonedDateTime.of(2026, 9, 11, hour, 0, 0, 0, ZoneOffset.UTC).minusDays(days).toInstant().toEpochMilli()

    @Test
    fun aDayIsNamedByHowFarItLiesBeforeToday() {
        assertEquals(DayDistance.TODAY, DisplayTime.dayDistance(daysBefore(0, hour = 0), now, ZoneOffset.UTC))
        assertEquals(DayDistance.YESTERDAY, DisplayTime.dayDistance(daysBefore(1, hour = 23), now, ZoneOffset.UTC))
        assertEquals(DayDistance.THIS_WEEK, DisplayTime.dayDistance(daysBefore(2), now, ZoneOffset.UTC))
        assertEquals(DayDistance.THIS_WEEK, DisplayTime.dayDistance(daysBefore(6), now, ZoneOffset.UTC))
        assertEquals(DayDistance.THIS_YEAR, DisplayTime.dayDistance(daysBefore(7), now, ZoneOffset.UTC))
        assertEquals(DayDistance.EARLIER, DisplayTime.dayDistance(daysBefore(365), now, ZoneOffset.UTC))
        assertEquals("a clock set back is never today", DayDistance.THIS_YEAR, DisplayTime.dayDistance(daysBefore(-1), now, ZoneOffset.UTC))
    }

    @Test
    fun theDayBoundaryIsThePhonesZone() {
        // 23:30 UTC on the 10th is already the 11th in Kolkata, and "now" there is the 11th too.
        val lateUtc = ZonedDateTime.of(2026, 9, 10, 23, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(DayDistance.YESTERDAY, DisplayTime.dayDistance(lateUtc, now, ZoneOffset.UTC))
        assertEquals(DayDistance.TODAY, DisplayTime.dayDistance(lateUtc, now, ZoneId.of("Asia/Kolkata")))
    }

    @Test
    fun namesComeFromTheLocale() {
        assertEquals("Fri", DisplayTime.weekday(now, ZoneOffset.UTC, Locale.US))
        assertEquals("Sep 11", DisplayTime.monthDay(now, ZoneOffset.UTC, Locale.US))
        assertEquals("Sep 11, 2026", DisplayTime.date(now, ZoneOffset.UTC, Locale.US))
    }

    @Test
    fun theYearAndWhatBelongsToItLeaveThePattern() {
        assertEquals("MMM d", DisplayTime.withoutYear("MMM d, y"))
        assertEquals("d MMM", DisplayTime.withoutYear("d MMM y"))
        assertEquals("dd.MM", DisplayTime.withoutYear("dd.MM.y"))
        assertEquals("MM/dd", DisplayTime.withoutYear("y/MM/dd"))
        assertEquals("M月d日", DisplayTime.withoutYear("y年M月d日"))
        assertEquals("d 'de' MMM", DisplayTime.withoutYear("d 'de' MMM 'de' y"))
        assertEquals("MMM d.", DisplayTime.withoutYear("y. MMM d."))
        assertEquals("no year field", "MMM d", DisplayTime.withoutYear("MMM d"))
        assertEquals("never empty", "y", DisplayTime.withoutYear("y"))
    }
}
