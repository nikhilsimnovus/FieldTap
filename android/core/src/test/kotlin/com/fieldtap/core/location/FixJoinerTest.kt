package com.fieldtap.core.location

import com.fieldtap.format.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FixJoinerTest {

    private fun at(elapsedMs: Long, lat: Double): com.fieldtap.core.input.FixSample =
        fix(elapsedMs, lat = lat, lon = -77.0)

    private fun position(lat: Double) = LatLon(lat, -77.0)

    @Test
    fun theNearestFixWithinFiveSecondsInclusiveGivesThePosition() {
        val joiner = FixJoiner()
        joiner.add(at(10_000, lat = 1.0))
        assertEquals(position(1.0), joiner.joinFinal(15_000))
        assertNull(joiner.joinFinal(15_001))
        assertEquals(position(1.0), joiner.joinFinal(5_000))
        assertNull(joiner.joinFinal(4_999))
    }

    @Test
    fun aTieTakesTheEarlierFix() {
        val joiner = FixJoiner()
        joiner.add(at(1_000, lat = 1.0))
        joiner.add(at(3_000, lat = 3.0))
        assertEquals(position(1.0), joiner.joinFinal(2_000))
        assertEquals(position(3.0), joiner.joinFinal(2_001))
        assertEquals(position(1.0), joiner.joinFinal(1_999))
    }

    @Test
    fun theJoinIsPendingUntilAFixAtOrAfterTheMeasurementArrives() {
        val joiner = FixJoiner()
        joiner.add(at(0, lat = 10.0))
        assertEquals(JoinResult.Pending, joiner.join(measurementElapsedMs = 400, nowElapsedMs = 900))
        joiner.add(at(1_000, lat = 11.0))
        assertEquals(JoinResult.Resolved(position(10.0)), joiner.join(measurementElapsedMs = 400, nowElapsedMs = 1_150))
    }

    @Test
    fun aFixExactlyAtTheMeasurementResolvesAtOnce() {
        val joiner = FixJoiner()
        joiner.add(at(5_000, lat = 5.0))
        assertEquals(JoinResult.Resolved(position(5.0)), joiner.join(measurementElapsedMs = 5_000, nowElapsedMs = 5_000))
    }

    @Test
    fun waitingEndsFiveAndAHalfSecondsAfterTheMeasurementGap() {
        val joiner = FixJoiner()
        joiner.add(at(0, lat = 10.0))
        assertEquals(JoinResult.Pending, joiner.join(measurementElapsedMs = 400, nowElapsedMs = 400 + 6_499))
        assertEquals(JoinResult.Resolved(position(10.0)), joiner.join(measurementElapsedMs = 400, nowElapsedMs = 400 + 6_500))
    }

    @Test
    fun withoutAnyFixTheJoinResolvesBlankOnceWaitingEnds() {
        val joiner = FixJoiner()
        assertEquals(JoinResult.Pending, joiner.join(measurementElapsedMs = 400, nowElapsedMs = 6_899))
        assertEquals(JoinResult.Resolved(null), joiner.join(measurementElapsedMs = 400, nowElapsedMs = 6_900))
        assertNull(joiner.joinFinal(400))
    }

    @Test
    fun aDistantLaterFixMakesTheBlankResultFinal() {
        val joiner = FixJoiner()
        joiner.add(at(0, lat = 1.0))
        joiner.add(at(20_000, lat = 2.0))
        assertEquals(JoinResult.Resolved(null), joiner.join(measurementElapsedMs = 10_000, nowElapsedMs = 20_150))
    }

    @Test
    fun staleRowsJoinAtTheirOriginalMeasurementTime() {
        val joiner = FixJoiner()
        for (second in 0..10) joiner.add(at(second * 1_000L, lat = second.toDouble()))
        // A repeat seen at 10.9 s still carries the measurement taken at 0.4 s.
        assertEquals(JoinResult.Resolved(position(0.0)), joiner.join(measurementElapsedMs = 400, nowElapsedMs = 10_900))
    }

    @Test
    fun fixesMoreThanSixtySecondsBehindTheNewestAreForgotten() {
        val joiner = FixJoiner()
        joiner.add(at(0, lat = 1.0))
        joiner.add(at(60_000, lat = 2.0))
        assertEquals("exactly 60 s behind is kept", position(1.0), joiner.joinFinal(0))
        joiner.add(at(60_001, lat = 3.0))
        assertNull(joiner.joinFinal(0))
    }

    @Test
    fun fixesAddedOutOfOrderAreJoinedInTimeOrder() {
        val joiner = FixJoiner()
        joiner.add(at(3_000, lat = 3.0))
        joiner.add(at(1_000, lat = 1.0))
        joiner.add(at(2_000, lat = 2.0))
        assertEquals(position(2.0), joiner.joinFinal(1_900))
        assertEquals(position(1.0), joiner.joinFinal(1_500))
        assertEquals(position(3.0), joiner.joinFinal(2_600))
        assertEquals(JoinResult.Resolved(position(3.0)), joiner.join(measurementElapsedMs = 2_600, nowElapsedMs = 2_600))
    }

    @Test
    fun fixesWithTheSameTimeKeepArrivalOrder() {
        val joiner = FixJoiner()
        joiner.add(at(1_000, lat = 1.0))
        joiner.add(at(1_000, lat = 1.5))
        assertEquals(position(1.0), joiner.joinFinal(1_000))
    }

    @Test
    fun negativeParametersAreProgrammingErrors() {
        assertThrows(IllegalArgumentException::class.java) { FixJoiner(maxGapMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { FixJoiner(retainMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { FixJoiner(lateFixSlackMs = -1) }
    }
}
