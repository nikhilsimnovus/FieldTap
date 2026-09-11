package com.fieldtap.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneReachTest {

    @Test
    fun aFixWithoutAUsableSpeedCountsAsMovingAtTheTopSpeedAlready() {
        assertEquals(100.0, ZoneReach.startSpeedMps(null), 0.0)
        assertEquals(100.0, ZoneReach.startSpeedMps(Double.NaN), 0.0)
        assertEquals(100.0, ZoneReach.startSpeedMps(-1.0), 0.0)
        assertEquals(100.0, ZoneReach.startSpeedMps(250.0), 0.0)
        assertEquals("the reported speed plus its slack", 4.4, ZoneReach.startSpeedMps(1.4), 1e-9)

        assertEquals(100.0, ZoneReach.reachM(1_000, null), 1e-9)
        assertEquals(1_000L, ZoneReach.reachTimeMs(100.0, null))
    }

    @Test
    fun aWalkerSpeedsUpNoFasterThanASportsCarAndNoFurtherThanTheTopSpeed() {
        // From 1.4 m/s plus 3 m/s slack: 4.4 t + 5 t², until 100 m/s after 9.56 s, then 100 m/s.
        assertEquals(9.4, ZoneReach.reachM(1_000, 1.4), 1e-9)
        assertEquals(147.0, ZoneReach.reachM(5_000, 1.4), 1e-9)
        assertEquals(4.4 * 9.56 + 5 * 9.56 * 9.56 + 100 * 0.44, ZoneReach.reachM(10_000, 1.4), 1e-6)
        assertEquals(0.0, ZoneReach.reachM(0, 1.4), 0.0)
        assertEquals(0.0, ZoneReach.reachM(-5, null), 0.0)
    }

    @Test
    fun theReachTimeIsTheLeastWholeMillisecondAtMostTheTimeTheMarginTakes() {
        for (speed in listOf(null, 0.0, 1.4, 13.9, 30.0, 97.5)) {
            for (margin in listOf(0.5, 5.0, 45.0, 145.0, 499.0, 956.9, 5_000.0, 60_000.0)) {
                val ms = ZoneReach.reachTimeMs(margin, speed)
                assertTrue("$margin m at $speed m/s: $ms ms reaches too far", ZoneReach.reachM(ms, speed) <= margin + 1e-6)
                assertTrue("$margin m at $speed m/s: $ms ms is too short", ZoneReach.reachM(ms + 1, speed) >= margin - 1e-6)
            }
        }
        assertEquals(0L, ZoneReach.reachTimeMs(0.0, 1.4))
        assertEquals(0L, ZoneReach.reachTimeMs(-3.0, 1.4))
        assertEquals(0L, ZoneReach.reachTimeMs(Double.NaN, 1.4))
        assertEquals(Long.MAX_VALUE, ZoneReach.reachTimeMs(Double.POSITIVE_INFINITY, 1.4))
    }

    @Test
    fun aFixPlacesTheNextFiveSecondsOrNothingNeverAnInBetweenThatMeasuresTheDistance() {
        assertEquals(0L, ZoneReach.forwardMs(0))
        assertEquals(0L, ZoneReach.forwardMs(4_999))
        assertEquals(5_000L, ZoneReach.forwardMs(5_000))
        assertEquals(5_000L, ZoneReach.forwardMs(Long.MAX_VALUE))
    }
}
