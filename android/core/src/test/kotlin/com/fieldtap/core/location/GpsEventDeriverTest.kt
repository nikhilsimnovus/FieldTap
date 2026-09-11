package com.fieldtap.core.location

import com.fieldtap.format.EventKind
import com.fieldtap.format.EventRat
import com.fieldtap.format.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GpsEventDeriverTest {

    @Test
    fun noEventBeforeTheFirstFix() {
        val deriver = GpsEventDeriver()
        assertNull(deriver.onTick(nowWallMs = 1_789_050_700_000L, nowElapsedMs = 10_000_000L))
    }

    @Test
    fun gpsLostIsWrittenOnceWhenMoreThanFiveSecondsPassWithoutAFix() {
        val deriver = GpsEventDeriver()
        val first = fix(1_000) // observed at 1_150
        assertNull(deriver.onFix(first))
        assertNull(deriver.onTick(nowWallMs = 1L, nowElapsedMs = 6_150))

        val lost = requireNotNull(deriver.onTick(nowWallMs = 1_789_050_606_151L, nowElapsedMs = 6_151)) { "gps_lost is due" }
        assertEquals(EventKind.GPS_LOST, lost.kind)
        assertEquals(EventRat.NONE, lost.rat)
        assertEquals(Severity.WARN, lost.severity)
        assertEquals("GPS lost", lost.title)
        assertEquals("no fix for more than 5 s", lost.detail)
        assertEquals(1_789_050_606_151L, lost.timeUtcMs)
        assertNull(lost.pci)
        assertNull(lost.arfcn)
        assertNull(lost.cause)

        assertNull("only once per loss", deriver.onTick(nowWallMs = 2L, nowElapsedMs = 9_000))
    }

    @Test
    fun gpsRestoredIsWrittenAtTheObservedTimeOfTheNextFix() {
        val deriver = GpsEventDeriver()
        deriver.onFix(fix(1_000))
        assertNotNull(deriver.onTick(nowWallMs = 1L, nowElapsedMs = 7_000))

        val next = fix(8_000, deliveryDelayMs = 300)
        val restored = requireNotNull(deriver.onFix(next)) { "gps_restored is due" }
        assertEquals(EventKind.GPS_RESTORED, restored.kind)
        assertEquals(EventRat.NONE, restored.rat)
        assertEquals(Severity.OK, restored.severity)
        assertEquals("GPS restored", restored.title)
        assertNull(restored.detail)
        assertEquals(next.observedWallMs, restored.timeUtcMs)

        assertNull(deriver.onFix(fix(9_000)))
    }

    @Test
    fun aFixBeforeTheDeadlineMovesTheDeadline() {
        val deriver = GpsEventDeriver()
        deriver.onFix(fix(1_000))
        deriver.onFix(fix(5_000)) // observed at 5_150
        assertNull(deriver.onTick(nowWallMs = 1L, nowElapsedMs = 10_150))
        assertNotNull(deriver.onTick(nowWallMs = 1L, nowElapsedMs = 10_151))
    }

    @Test
    fun aLossAfterARestoreIsReportedAgain() {
        val deriver = GpsEventDeriver()
        deriver.onFix(fix(1_000))
        assertNotNull(deriver.onTick(nowWallMs = 1L, nowElapsedMs = 7_000))
        assertNotNull(deriver.onFix(fix(8_000)))
        assertNull(deriver.onTick(nowWallMs = 1L, nowElapsedMs = 13_150))
        assertNotNull(deriver.onTick(nowWallMs = 1L, nowElapsedMs = 13_151))
    }

    @Test
    fun theDetailFollowsTheThreshold() {
        val deriver = GpsEventDeriver(lostAfterMs = 2_500)
        deriver.onFix(fix(0))
        assertEquals("no fix for more than 2.5 s", deriver.onTick(nowWallMs = 1L, nowElapsedMs = 2_651)?.detail)
    }

    @Test
    fun locationServicesSwitchedOffAreAGpsLossAtOnceThatSaysWhy() {
        val deriver = GpsEventDeriver()
        deriver.onFix(fix(1_000))

        val lost = requireNotNull(deriver.onLocationServices(enabled = false, observedWallMs = 1_789_050_602_500L, observedElapsedMs = 2_500)) {
            "gps_lost is written when location services go off"
        }
        assertEquals(EventKind.GPS_LOST, lost.kind)
        assertEquals(EventRat.NONE, lost.rat)
        assertEquals(Severity.WARN, lost.severity)
        assertEquals("GPS lost", lost.title)
        assertEquals("Location services turned off", lost.detail)
        assertEquals(GpsEventDeriver.LOCATION_OFF_DETAIL, lost.detail)
        assertEquals(1_789_050_602_500L, lost.timeUtcMs)
        assertNull(lost.pci)
        assertNull(lost.arfcn)
        assertNull(lost.cause)

        assertNull("the same switch reported again is the same loss", deriver.onLocationServices(false, 1L, 3_000))
        assertNull("no timed loss is added while location is off", deriver.onTick(nowWallMs = 1L, nowElapsedMs = 60_000))
    }

    @Test
    fun switchedBackOnTheNextFixRestoresGpsNotTheSwitch() {
        val deriver = GpsEventDeriver()
        deriver.onFix(fix(1_000))
        assertNotNull(deriver.onLocationServices(false, 1L, 2_000))

        assertNull(deriver.onLocationServices(true, 2L, 20_000))
        assertNull("still lost until a fix arrives", deriver.onTick(nowWallMs = 3L, nowElapsedMs = 40_000))
        val next = fix(41_000)
        val restored = requireNotNull(deriver.onFix(next)) { "gps_restored is due" }
        assertEquals(EventKind.GPS_RESTORED, restored.kind)
        assertNull(restored.detail)
        assertEquals(next.observedWallMs, restored.timeUtcMs)

        // The next loss is timed again, from that fix.
        assertNull(deriver.onTick(nowWallMs = 4L, nowElapsedMs = 46_150))
        assertEquals("no fix for more than 5 s", deriver.onTick(nowWallMs = 4L, nowElapsedMs = 46_151)?.detail)
    }

    @Test
    fun aFixMeasuredBeforeLocationWentOffRestoresNothing() {
        val deriver = GpsEventDeriver()
        deriver.onFix(fix(1_000))
        assertNotNull(deriver.onLocationServices(false, 1L, 2_000))

        assertNull("measured at 1.9 s and delivered at 2.4 s", deriver.onFix(fix(1_900, deliveryDelayMs = 500)))
        assertNull(deriver.onTick(nowWallMs = 1L, nowElapsedMs = 30_000))
        // Measured after the switch: location is on again, even if Android never said so.
        assertNotNull(deriver.onFix(fix(8_000)))
    }

    @Test
    fun theSwitchIsAGpsLossBeforeTheFirstFixAndAfterATimedOne() {
        val beforeAnyFix = GpsEventDeriver()
        assertEquals(GpsEventDeriver.LOCATION_OFF_DETAIL, beforeAnyFix.onLocationServices(false, 1L, 500)?.detail)
        assertNotNull("the first fix after it restores GPS", beforeAnyFix.onFix(fix(9_000)))

        val inATunnel = GpsEventDeriver()
        inATunnel.onFix(fix(1_000))
        assertEquals("no fix for more than 5 s", inATunnel.onTick(nowWallMs = 1L, nowElapsedMs = 7_000)?.detail)
        assertEquals(GpsEventDeriver.LOCATION_OFF_DETAIL, inATunnel.onLocationServices(false, 2L, 9_000)?.detail)
        assertNotNull(inATunnel.onFix(fix(30_000)))
        assertNull("one restore ends both", inATunnel.onFix(fix(31_000)))
    }

    @Test
    fun everySwitchOffIsWrittenAndSwitchingOnNever() {
        val deriver = GpsEventDeriver()
        deriver.onFix(fix(1_000))
        assertNotNull(deriver.onLocationServices(false, 1L, 2_000))
        assertNull(deriver.onLocationServices(true, 1L, 3_000))
        assertNotNull(deriver.onLocationServices(false, 1L, 4_000))
        assertNull(deriver.onLocationServices(true, 1L, 5_000))
    }
}
