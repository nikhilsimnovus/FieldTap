package com.fieldtap.core.soak

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.radio.ClassifiedAnswer
import com.fieldtap.format.CellInfoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoakEvaluatorTest {
    private val start = 25_323_456L
    private val delta = 1e-9

    @Test
    fun aTickInEverySecondIsFullyLogged() {
        val evaluator = SoakEvaluator(start)
        for (second in 0 until 600) evaluator.onTick(start + second * 1_000L + 3)

        val result = evaluator.result(start + 600_000)

        assertEquals(600_000L, result.durationMs)
        assertEquals(600L, result.secondsElapsed)
        assertEquals(600L, result.secondsLogged)
        assertEquals(100.0, result.loggedPct, delta)
    }

    @Test
    fun secondsWithoutATickAreMissing() {
        val evaluator = SoakEvaluator(start)
        for (second in 0 until 600) {
            if (second !in 100 until 400) evaluator.onTick(start + second * 1_000L)
        }

        val result = evaluator.result(start + 600_000)

        assertEquals(300L, result.secondsLogged)
        assertEquals(50.0, result.loggedPct, delta)
    }

    @Test
    fun severalTicksInOneSecondCountOnce() {
        val evaluator = SoakEvaluator(start)
        evaluator.onTick(start + 10)
        evaluator.onTick(start + 500)
        evaluator.onTick(start + 999)

        val result = evaluator.result(start + 1_000)

        assertEquals(1L, result.secondsElapsed)
        assertEquals(1L, result.secondsLogged)
    }

    @Test
    fun ticksBeforeTheStartOrAtTheEndAreIgnored() {
        val evaluator = SoakEvaluator(start, durationMs = 10_000)
        evaluator.onTick(start - 1)
        evaluator.onTick(start + 10_000)
        evaluator.onTick(start + 50_000)

        val result = evaluator.result(start + 60_000)

        assertEquals(10L, result.secondsElapsed)
        assertEquals(0L, result.secondsLogged)
    }

    @Test
    fun anEarlyEndReportsThePartThatRan() {
        val evaluator = SoakEvaluator(start)
        for (second in 0..90) evaluator.onTick(start + second * 1_000L)

        val result = evaluator.result(start + 90_500)

        assertEquals(90L, result.secondsElapsed)
        assertEquals(90L, result.secondsLogged)
        assertEquals(600_000L, result.durationMs)
    }

    @Test
    fun nothingElapsedIsZeroPercent() {
        val evaluator = SoakEvaluator(start)
        evaluator.onTick(start)

        assertEquals(0L, evaluator.result(start).secondsElapsed)
        assertEquals(0.0, evaluator.result(start).loggedPct, delta)
        assertEquals(0L, evaluator.result(start - 5_000).secondsElapsed)
    }

    @Test
    fun answersAreCountedByFreshnessAndScreen() {
        val evaluator = SoakEvaluator(start)
        evaluator.onAnswer(classified(fresh = true, screenOn = true))
        evaluator.onAnswer(classified(fresh = false, screenOn = false))
        evaluator.onAnswer(classified(fresh = true, screenOn = false))

        val result = evaluator.result(start + 3_000)

        assertEquals(3, result.answers)
        assertEquals(2, result.freshAnswers)
        assertEquals(2, result.screenOffAnswers)
    }

    @Test
    fun aDurationBelowTheMinimumIsRaised() {
        assertEquals(SoakEvaluator.MIN_DURATION_MS, SoakEvaluator(start, durationMs = 1).durationMs)
        assertEquals(SoakEvaluator.DEFAULT_DURATION_MS, SoakEvaluator(start).durationMs)
    }

    @Test
    fun finishesExactlyAtTheDuration() {
        val evaluator = SoakEvaluator(start)
        assertFalse(evaluator.isFinished(start + 599_999))
        assertTrue(evaluator.isFinished(start + 600_000))
    }

    @Test
    fun loggedPctIsTheShareOfElapsedSeconds() {
        assertEquals(75.0, SoakResult(600_000, 200, 150, 0, 0, 0).loggedPct, delta)
        assertEquals(0.0, SoakResult(600_000, 0, 0, 0, 0, 0).loggedPct, delta)
    }

    private fun classified(fresh: Boolean, screenOn: Boolean): ClassifiedAnswer {
        val answer = CellInfoAnswer(
            source = CellInfoSource.REQUEST,
            cells = emptyList(),
            subId = null,
            conditions = DeviceConditions(screenOn = screenOn, charging = false, wifiConnected = false),
            observedWallMs = 1_789_050_600_000L,
            observedElapsedMs = start,
        )
        return ClassifiedAnswer(answer, emptyList(), primary = null, nsaSecondary = null, fresh = fresh, repeat = !fresh)
    }
}
