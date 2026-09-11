package com.fieldtap.core.radio

import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.radio.RadioFixtures.BOOT0
import com.fieldtap.core.radio.RadioFixtures.POCKET
import com.fieldtap.core.radio.RadioFixtures.SHORT
import com.fieldtap.core.radio.RadioFixtures.WALL0
import com.fieldtap.core.radio.RadioFixtures.answer
import com.fieldtap.core.radio.RadioFixtures.lte
import com.fieldtap.core.radio.RadioFixtures.serviceState
import com.fieldtap.format.EventKind
import com.fieldtap.format.EventRat
import com.fieldtap.format.EventRow
import com.fieldtap.format.GapMeta
import com.fieldtap.format.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SamplingGapsTest {
    private val engine = FreshnessEngine()
    private val detector = SamplingGapDetector()

    /** An answer observed at [atMs] whose primary cell, the golden LTE anchor, was measured at [measuredAtMs]. */
    private fun answerAt(atMs: Long, measuredAtMs: Long, conditions: DeviceConditions = SHORT): SamplingGap? =
        detector.onAnswer(engine.classify(answer(atMs, listOf(lte(measuredAtMs)), conditions = conditions)))

    /** The session ticker at [fromMs], then every [stepMs] up to [toMs] inclusive. */
    private fun ticks(fromMs: Long, toMs: Long, stepMs: Long = 1_000) {
        var atMs = fromMs
        while (atMs <= toMs) {
            detector.onTick(BOOT0 + atMs)
            atMs += stepMs
        }
    }

    private fun serviceAt(atMs: Long, state: ServiceRegState) {
        detector.onServiceState(serviceState(atMs, state))
    }

    @Test
    fun aGapBecomesTheGoldenSamplingGapRowAndGapsEntry() {
        val gap = SamplingGap(WALL0 + 90_400, WALL0 + 104_400, GapReasons.SCREEN_OFF, WALL0 + 104_900)
        assertEquals(
            EventRow(
                WALL0 + 104_900,
                EventRat.NONE,
                EventKind.SAMPLING_GAP,
                Severity.WARN,
                "Sampling gap",
                "no fresh cell info for 14.0 s",
                cause = "screen_off",
            ),
            gap.toEvent(),
        )
        assertEquals(GapMeta(WALL0 + 90_400, WALL0 + 104_400, "screen_off"), gap.toMeta())
        assertEquals(GoldenSession.eventRows().single { it.kind == EventKind.SAMPLING_GAP }, gap.toEvent())
        assertEquals(GoldenSession.collection().gaps.single(), gap.toMeta())
    }

    @Test
    fun theDetailRoundsSecondsAsPythonDoes() {
        fun detail(durationMs: Long): String? =
            SamplingGap(WALL0, WALL0 + durationMs, GapReasons.UNKNOWN, WALL0 + durationMs).toEvent().detail
        assertEquals("no fresh cell info for 4.0 s", detail(4_001))
        // An exact tie rounds to even; 0.15 and 14.05 are just below and just above a tie in binary.
        assertEquals("no fresh cell info for 0.2 s", detail(250))
        assertEquals("no fresh cell info for 0.1 s", detail(150))
        assertEquals("no fresh cell info for 14.1 s", detail(14_050))
        assertEquals("no fresh cell info for 20.0 s", detail(20_001))
        assertEquals("no fresh cell info for 3600.0 s", detail(3_600_000))
    }

    @Test
    fun theGoldenSessionHasOneFourteenSecondScreenOffGap() {
        val found = ArrayList<SamplingGap>()
        for (observed in GoldenSession.answers()) {
            detector.onTick(observed.observedElapsedMs - 400)
            detector.onAnswer(engine.classify(observed))?.let { found.add(it) }
        }
        assertEquals(listOf(SamplingGap(WALL0 + 90_400, WALL0 + 104_400, GapReasons.SCREEN_OFF, WALL0 + 104_900)), found)
        assertEquals(GoldenSession.eventRows().filter { it.kind == EventKind.SAMPLING_GAP }, found.map { it.toEvent() })
        assertEquals(GoldenSession.collection().gaps, found.map { it.toMeta() })
    }

    @Test
    fun onTheShortIntervalMoreThanFourSecondsWithoutAFreshSampleIsAGap() {
        assertNull(answerAt(900, 400))
        assertNull(answerAt(4_900, 4_400))
        val gap = answerAt(9_400, 8_401)!!
        assertEquals(WALL0 + 4_400, gap.startUtcMs)
        assertEquals(WALL0 + 8_401, gap.stopUtcMs)
        assertEquals(WALL0 + 9_400, gap.observedWallMs)
        assertEquals(GapReasons.UNKNOWN, gap.reason)
    }

    @Test
    fun aTenSecondCadenceWithTwelveSecondSpacingIsNotAGap() {
        assertNull(answerAt(500, 0, POCKET))
        assertNull(answerAt(12_500, 12_000, POCKET))
        assertNull(answerAt(24_500, 24_000, POCKET))
        assertNull(answerAt(44_500, 44_000, POCKET))
        val gap = answerAt(64_501, 64_001, POCKET)!!
        assertEquals(WALL0 + 44_000, gap.startUtcMs)
        assertEquals(WALL0 + 64_001, gap.stopUtcMs)
        assertEquals(GapReasons.SCREEN_OFF, gap.reason)
    }

    @Test
    fun wifiWithTheScreenOnGivesTheLongIntervalAndNoScreenOffReason() {
        val wifi = DeviceConditions(screenOn = true, charging = false, wifiConnected = true)
        assertNull(answerAt(500, 0, wifi))
        assertNull(answerAt(20_500, 20_000, wifi))
        assertEquals(GapReasons.UNKNOWN, answerAt(40_501, 40_001, wifi)!!.reason)
    }

    @Test
    fun aGapIsJudgedByTheIntervalInForceWhenItsLastSampleWasMeasured() {
        // As in the golden session: the screen goes off just after a 2 s sample, so the answer carrying
        // that sample already reports the 10 s interval.
        assertNull(answerAt(900, 400, SHORT))
        assertNull(answerAt(1_900, 400, SHORT))
        assertNull(answerAt(2_900, 2_400, POCKET))
        for (second in 3..16) assertNull(answerAt(second * 1_000L + 900, 2_400, POCKET))
        val gap = answerAt(17_900, 17_400, SHORT)!!
        assertEquals(WALL0 + 2_400, gap.startUtcMs)
        assertEquals(WALL0 + 17_400, gap.stopUtcMs)
        assertEquals(GapReasons.SCREEN_OFF, gap.reason)
    }

    @Test
    fun aScreenAlreadyOffWhenTheSampleWasMeasuredGivesTheLongInterval() {
        assertNull(answerAt(900, 400, POCKET))
        assertNull(answerAt(1_900, 400, POCKET))
        assertNull(answerAt(2_900, 2_400, POCKET))
        assertNull(answerAt(17_900, 17_400, SHORT))
    }

    @Test
    fun aSampleMeasuredBeforeEveryRecentAnswerTakesTheCadenceOfTheOldestOne() {
        // Seen before the detector's first answer, so the next answer carrying it is a repeat.
        engine.classify(answer(1_000, listOf(lte(500))))
        assertNull(answerAt(3_000, 500, POCKET))
        // Measured at 2.5 s with the screen off, delivered at 10 s after the screen came on.
        assertNull(answerAt(10_000, 2_500, SHORT))
        // 9 s later: within twice the 10 s in force when the 2.5 s sample was measured.
        assertNull(answerAt(12_000, 11_500, SHORT))
    }

    @Test
    fun aTickerStallInsideTheGapMakesItAppPausedBeforeAnyOtherReason() {
        detector.onTick(BOOT0 + 500)
        assertNull(answerAt(900, 400))
        ticks(1_500, 4_500)
        // No tick from 4.5 s to 14.5 s: the process was frozen.
        ticks(14_500, 14_500)
        serviceAt(15_000, ServiceRegState.OUT_OF_SERVICE)
        ticks(15_500, 15_500)
        serviceAt(16_000, ServiceRegState.IN_SERVICE)
        ticks(16_500, 17_500)
        assertNull(answerAt(17_900, 400, POCKET))
        ticks(18_500, 20_500)
        assertEquals(GapReasons.APP_PAUSED, answerAt(20_900, 20_400)!!.reason)
    }

    @Test
    fun aStallStillRunningWhenTheEndingAnswerArrivesCounts() {
        detector.onTick(BOOT0 + 500)
        assertNull(answerAt(900, 400))
        ticks(1_500, 3_500)
        // No tick since 3.5 s: the answer is delivered as the process wakes, before the ticker runs.
        assertEquals(GapReasons.APP_PAUSED, answerAt(20_900, 20_400)!!.reason)
    }

    @Test
    fun ticksThreeSecondsApartOrAStallMostlyBeforeTheGapAreNotAppPaused() {
        detector.onTick(BOOT0 - 3_000)
        assertNull(answerAt(900, 400))
        // Stalled for 6.3 s, of which only 2.9 s fall inside the gap.
        detector.onTick(BOOT0 + 3_300)
        // Then exactly 3 s apart, which is not a stall; the last tick is 2.6 s before the ending answer.
        ticks(6_300, 18_300, stepMs = 3_000)
        assertEquals(GapReasons.UNKNOWN, answerAt(20_900, 20_400)!!.reason)
    }

    @Test
    fun serviceLostOrEmergencyOnlyInsideTheGapIsNoService() {
        assertNull(answerAt(900, 400))
        serviceAt(3_000, ServiceRegState.OUT_OF_SERVICE)
        serviceAt(9_000, ServiceRegState.IN_SERVICE)
        // The screen was off in the gap too, but no service comes first.
        assertNull(answerAt(9_900, 400, POCKET))
        assertNull(answerAt(19_900, 400, SHORT))
        assertEquals(GapReasons.NO_SERVICE, answerAt(20_900, 20_400)!!.reason)

        serviceAt(22_000, ServiceRegState.EMERGENCY_ONLY)
        // Still emergency-only when the next gap ends.
        assertEquals(GapReasons.NO_SERVICE, answerAt(40_900, 40_400)!!.reason)
    }

    @Test
    fun anOutageThatEndedBeforeTheGapBeganIsNotNoService() {
        serviceAt(0, ServiceRegState.OUT_OF_SERVICE)
        serviceAt(300, ServiceRegState.IN_SERVICE)
        assertNull(answerAt(900, 400))
        assertEquals(GapReasons.UNKNOWN, answerAt(20_900, 20_400)!!.reason)
    }

    @Test
    fun anUnknownServiceStateNeitherStartsNorEndsAnOutage() {
        assertNull(answerAt(900, 400))
        serviceAt(2_000, ServiceRegState.UNKNOWN)
        assertEquals(GapReasons.UNKNOWN, answerAt(20_900, 20_400)!!.reason)

        serviceAt(22_000, ServiceRegState.POWER_OFF)
        serviceAt(23_000, ServiceRegState.UNKNOWN)
        assertEquals(GapReasons.NO_SERVICE, answerAt(40_900, 40_400)!!.reason)
    }

    @Test
    fun onlyAnswersBeforeTheEndingOneDecideScreenOff() {
        assertNull(answerAt(900, 400))
        assertEquals(GapReasons.UNKNOWN, answerAt(20_900, 20_400, POCKET)!!.reason)
    }

    @Test
    fun resetForgetsThePreviousSampleButKeepsTheServiceState() {
        assertNull(answerAt(900, 400))
        serviceAt(11_000, ServiceRegState.OUT_OF_SERVICE)
        detector.reset()
        // Sixty seconds without a fresh sample, but across a pause: no gap.
        assertNull(answerAt(60_900, 60_400))
        val gap = answerAt(90_900, 90_400)!!
        assertEquals(WALL0 + 60_400, gap.startUtcMs)
        assertEquals(GapReasons.NO_SERVICE, gap.reason)
    }

    @Test
    fun aFreshAnswerMeasuredNoLaterThanThePreviousOneNeitherEndsAGapNorMovesTheReference() {
        assertNull(answerAt(10_900, 10_400))
        val older = lte(1_000, pci = 300, cellId = 99L)
        assertNull(detector.onAnswer(engine.classify(answer(11_900, listOf(older)))))
        // 4 s after the 10.4 s sample: had the reference moved to 1 s, this would be a gap.
        assertNull(answerAt(14_900, 14_400))
    }

    @Test
    fun withoutAPrimaryTheNewestFreshCellTimesTheAnswer() {
        fun neighbours(atMs: Long, measuredAtMs: Long): SamplingGap? = detector.onAnswer(
            engine.classify(
                answer(
                    atMs,
                    listOf(
                        lte(measuredAtMs - 200, pci = 1, status = CellSnapshot.CONNECTION_NONE),
                        lte(measuredAtMs, pci = 2, status = CellSnapshot.CONNECTION_NONE),
                    ),
                ),
            ),
        )
        assertNull(neighbours(900, 400))
        val gap = neighbours(20_900, 20_400)!!
        assertEquals(WALL0 + 400, gap.startUtcMs)
        assertEquals(WALL0 + 20_400, gap.stopUtcMs)
    }
}
