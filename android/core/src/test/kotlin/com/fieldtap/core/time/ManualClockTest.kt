package com.fieldtap.core.time

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualClockTest {
    @Test
    fun startsAtTheGoldenSessionStartAndMovesBothClocksTogether() {
        val clock = ManualClock()

        assertEquals(1_789_050_600_000L, clock.wallMillis())
        assertEquals(25_323_456L, clock.elapsedRealtimeMillis())
        clock.advance(1_500)

        assertEquals(1_789_050_601_500L, clock.wallMillis())
        assertEquals(25_324_956L, clock.elapsedRealtimeMillis())
    }

    @Test
    fun theWallClockCanJumpWithoutMovingElapsedTime() {
        val clock = ManualClock(wallMs = 1_789_050_600_000L, elapsedMs = 5_000L)

        clock.wallMs -= 3_600_000L

        assertEquals(1_789_047_000_000L, clock.wallMillis())
        assertEquals(5_000L, clock.elapsedRealtimeMillis())
    }
}
