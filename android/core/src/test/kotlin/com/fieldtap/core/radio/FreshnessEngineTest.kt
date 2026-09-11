package com.fieldtap.core.radio

import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.radio.RadioFixtures.BOOT0
import com.fieldtap.core.radio.RadioFixtures.WALL0
import com.fieldtap.core.radio.RadioFixtures.answer
import com.fieldtap.core.radio.RadioFixtures.lte
import com.fieldtap.core.radio.RadioFixtures.nr
import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.Rat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshnessEngineTest {

    @Test
    fun requestsEverySecondOnATwoSecondModemAlternateFreshAndRepeat() {
        val engine = FreshnessEngine()
        var fresh = 0
        var repeats = 0
        for (second in 0 until 120) {
            val atMs = 900L + second * 1_000L
            val measuredMs = 400L + (second / 2) * 2_000L
            val neighbour = lte(measuredMs, pci = 213, status = CellSnapshot.CONNECTION_NONE, cellId = null, mcc = null, mnc = null)
            val result = engine.classify(answer(atMs, listOf(lte(measuredMs), neighbour)))
            assertEquals("second $second", second % 2 == 0, result.fresh)
            assertEquals("second $second", second % 2 == 1, result.repeat)
            assertEquals("second $second", listOf(result.repeat, result.repeat), result.cells.map { it.stale })
            val primary = result.primary!!
            assertEquals(atMs - measuredMs, primary.ageMs)
            assertEquals(WALL0 + measuredMs, primary.measurementWallMs)
            if (result.fresh) fresh += 1
            if (result.repeat) repeats += 1
        }
        assertEquals(60, fresh)
        assertEquals(60, repeats)
    }

    @Test
    fun aRepeatKeepsTheMeasurementTimeOfItsFirstSightingWhenTheClocksDrift() {
        val engine = FreshnessEngine()
        val first = engine.classify(answer(900, listOf(lte(400))))
        assertEquals(WALL0 + 400, first.primary!!.measurementWallMs)

        // The wall clock and elapsedRealtime are read one after the other and each is cut to a millisecond, so the
        // next answer can see them a millisecond further apart; and Android may step the wall clock.
        val jittered = answer(1_900, listOf(lte(400))).let { it.copy(observedWallMs = it.observedWallMs + 1) }
        val repeat = engine.classify(jittered)
        assertTrue(repeat.repeat)
        assertEquals(WALL0 + 400, repeat.primary!!.measurementWallMs)
        assertEquals(1_500L, repeat.primary.ageMs)

        val stepped = answer(2_900, listOf(lte(400))).let { it.copy(observedWallMs = it.observedWallMs - 3_600_000) }
        assertEquals(WALL0 + 400, engine.classify(stepped).primary!!.measurementWallMs)
    }

    @Test
    fun aPushAnswerRepeatingARequestAnswerIsARepeat() {
        val engine = FreshnessEngine()
        val cells = listOf(lte(400), nr(400))
        assertTrue(engine.classify(answer(900, cells)).fresh)

        val push = engine.classify(answer(1_300, cells, source = CellInfoSource.PUSH))
        assertFalse(push.fresh)
        assertTrue(push.repeat)
        assertTrue(push.cells.all { it.stale })
        assertEquals(900L, push.primary!!.ageMs)
        assertEquals(WALL0 + 400, push.primary.measurementWallMs)
    }

    @Test
    fun anNsaAnswerCanHaveAFreshAnchorAndAStaleLeg() {
        val engine = FreshnessEngine()
        engine.classify(answer(900, listOf(lte(400), nr(400))))

        val next = engine.classify(answer(2_900, listOf(lte(2_400), nr(400))))
        assertTrue(next.fresh)
        assertFalse(next.repeat)
        assertFalse(next.primary!!.stale)
        assertTrue(next.nsaSecondary!!.stale)
        assertEquals(2_500L, next.nsaSecondary.ageMs)
        assertEquals(WALL0 + 400, next.nsaSecondary.measurementWallMs)
    }

    @Test
    fun aStaleAnchorMakesTheAnswerARepeatEvenWithAFreshLeg() {
        val engine = FreshnessEngine()
        engine.classify(answer(900, listOf(lte(400), nr(400))))

        val next = engine.classify(answer(1_900, listOf(lte(400), nr(1_400))))
        assertFalse(next.fresh)
        assertTrue(next.repeat)
        assertFalse(next.nsaSecondary!!.stale)
    }

    @Test
    fun anEmptyAnswerIsNeitherFreshNorARepeat() {
        val result = FreshnessEngine().classify(answer(900, emptyList()))
        assertFalse(result.fresh)
        assertFalse(result.repeat)
        assertNull(result.primary)
        assertNull(result.nsaSecondary)
        assertTrue(result.cells.isEmpty())
    }

    @Test
    fun withoutAPrimaryAnyFreshCellMakesTheAnswerFresh() {
        val engine = FreshnessEngine()
        val a = lte(400, pci = 1, status = CellSnapshot.CONNECTION_NONE)
        val b = lte(600, pci = 2, status = CellSnapshot.CONNECTION_NONE)
        val first = engine.classify(answer(900, listOf(a, b)))
        assertNull(first.primary)
        assertTrue(first.fresh)
        assertEquals(b, first.freshReference()!!.cell)

        assertTrue(engine.classify(answer(1_900, listOf(a, b))).repeat)

        val third = engine.classify(answer(2_900, listOf(a, lte(2_600, pci = 2, status = CellSnapshot.CONNECTION_NONE))))
        assertTrue(third.fresh)
        assertEquals(listOf(true, false), third.cells.map { it.stale })
        assertEquals(BOOT0 + 2_600, third.freshReference()!!.cell.timestampMs)
    }

    @Test
    fun aRepeatHasNoFreshReference() {
        val engine = FreshnessEngine()
        engine.classify(answer(900, listOf(lte(400))))
        assertNull(engine.classify(answer(1_900, listOf(lte(400)))).freshReference())
    }

    @Test
    fun aTimestampAfterTheAnswerClampsTheAgeToZero() {
        val result = FreshnessEngine().classify(answer(500, listOf(lte(1_000))))
        assertEquals(0L, result.primary!!.ageMs)
        assertEquals(WALL0 + 500, result.primary.measurementWallMs)
    }

    @Test
    fun aNonsenseTimestampNeverThrows() {
        val cell = lte(0).copy(timestampMs = Long.MIN_VALUE)
        val result = FreshnessEngine().classify(answer(500, listOf(cell)))
        assertEquals(Long.MAX_VALUE, result.primary!!.ageMs)
        assertTrue(result.fresh)
    }

    @Test
    fun aMeasurementIsRememberedForHistoryMsAfterItsLastSighting() {
        val engine = FreshnessEngine(historyMs = 10_000)
        assertTrue(engine.classify(answer(0, listOf(lte(0)))).fresh)
        assertTrue(engine.classify(answer(10_000, listOf(lte(0)))).repeat)
        // Seen again at 10 s, so still remembered at 20 s.
        assertTrue(engine.classify(answer(20_000, listOf(lte(0)))).repeat)
        // Last seen at 20 s: forgotten 1 ms after 30 s.
        assertTrue(engine.classify(answer(30_001, listOf(lte(0)))).fresh)
    }

    @Test
    fun aMeasurementNotSeenAgainIsForgottenAfterHistoryMs() {
        val kept = FreshnessEngine(historyMs = 10_000)
        kept.classify(answer(0, listOf(lte(0))))
        assertTrue(kept.classify(answer(10_000, listOf(lte(0)))).repeat)

        val forgotten = FreshnessEngine(historyMs = 10_000)
        forgotten.classify(answer(0, listOf(lte(0))))
        assertTrue(forgotten.classify(answer(10_001, listOf(lte(0)))).fresh)
    }

    @Test
    fun timestampsThatNeverAdvanceStayRepeatsBeyondTheHistory() {
        val engine = FreshnessEngine()
        assertTrue(engine.classify(answer(0, listOf(lte(0)))).fresh)
        for (second in 1..300) {
            assertTrue("second $second", engine.classify(answer(second * 1_000L, listOf(lte(0)))).repeat)
        }
    }

    @Test
    fun anotherCellWithTheSameTimestampIsFresh() {
        val engine = FreshnessEngine()
        engine.classify(answer(900, listOf(lte(400))))
        val changed = engine.classify(answer(1_900, listOf(lte(400, pci = 213, cellId = 21_640_194L))))
        assertTrue(changed.fresh)
    }

    @Test
    fun twoEqualCellsInOneAnswerAreJudgedOnlyAgainstEarlierAnswers() {
        val engine = FreshnessEngine()
        val neighbour = lte(400, pci = 100, status = CellSnapshot.CONNECTION_NONE)
        val result = engine.classify(answer(900, listOf(lte(400), neighbour, neighbour)))
        assertEquals(listOf(false, false, false), result.cells.map { it.stale })
    }

    @Test
    fun resetForgetsEverySeenMeasurement() {
        val engine = FreshnessEngine()
        engine.classify(answer(900, listOf(lte(400))))
        engine.reset()
        assertTrue(engine.classify(answer(1_900, listOf(lte(400)))).fresh)
    }

    @Test
    fun theCellKeyHasAPlmnOnlyWhenMccAndMncAreBothKnown() {
        assertEquals("311480", CellKey.of(lte(0)).plmn)
        assertNull(CellKey.of(lte(0, mnc = null)).plmn)
        assertNull(CellKey.of(lte(0, mcc = null)).plmn)
        assertEquals(CellKey(Rat.NR, null, 393, 650_000, null), CellKey.of(nr(0)))
        assertEquals(CellKey(Rat.LTE, "311480", 212, 66_786, 21_640_193L), CellKey.of(lte(0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNegativeHistoryIsRefused() {
        FreshnessEngine(historyMs = -1)
    }
}
