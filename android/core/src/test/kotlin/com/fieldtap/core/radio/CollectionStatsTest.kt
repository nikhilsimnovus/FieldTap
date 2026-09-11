package com.fieldtap.core.radio

import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.radio.RadioFixtures.POCKET
import com.fieldtap.core.radio.RadioFixtures.SHORT
import com.fieldtap.core.radio.RadioFixtures.WALL0
import com.fieldtap.core.radio.RadioFixtures.answer
import com.fieldtap.core.radio.RadioFixtures.lte
import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.CollectionMeta
import com.fieldtap.format.GapMeta
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionStatsTest {
    private val engine = FreshnessEngine()
    private val stats = CollectionStats()

    /** An answer observed at [atMs] carrying the golden LTE anchor measured at [measuredAtMs]. */
    private fun feed(
        atMs: Long,
        measuredAtMs: Long,
        conditions: DeviceConditions = SHORT,
        source: CellInfoSource = CellInfoSource.REQUEST,
    ) {
        stats.onAnswer(engine.classify(answer(atMs, listOf(lte(measuredAtMs)), conditions = conditions, source = source)))
    }

    @Test
    fun theGoldenSessionGivesItsCollectionValues() {
        val detector = SamplingGapDetector()
        for (observed in GoldenSession.answers()) {
            val classified = engine.classify(observed)
            stats.onAnswer(classified)
            detector.onAnswer(classified)?.let { stats.onGap(it) }
        }
        val snapshot = stats.snapshot()
        assertEquals(54L, snapshot.freshSamples)
        assertEquals(66L, snapshot.repeatsDropped)
        assertEquals(2_000L, snapshot.medianFreshIntervalMs)
        // 106 of the 120 requests had the screen on with Wi-Fi off. The shares are kept unrounded.
        assertEquals(100.0 * 106 / 120, snapshot.shortIntervalPct!!, 0.0)
        assertEquals(100.0 * 106 / 120, snapshot.screenOnPct!!, 0.0)
        assertEquals(0.0, snapshot.wifiConnectedPct!!, 0.0)
        assertEquals(0.0, snapshot.chargingPct!!, 0.0)
        assertEquals(listOf(GapMeta(WALL0 + 90_400, WALL0 + 104_400, GapReasons.SCREEN_OFF)), snapshot.gaps)

        val asWritten = snapshot.copy(
            shortIntervalPct = oneDecimal(snapshot.shortIntervalPct),
            screenOnPct = oneDecimal(snapshot.screenOnPct),
            wifiConnectedPct = oneDecimal(snapshot.wifiConnectedPct),
            chargingPct = oneDecimal(snapshot.chargingPct),
        )
        assertEquals(GoldenSession.collection(), asWritten)
    }

    @Test
    fun nothingCountedIsTheEmptyCollection() {
        assertEquals(CollectionMeta.EMPTY, stats.snapshot())
    }

    @Test
    fun sharesCountOnlyRequestAnswers() {
        feed(900, 400, SHORT)
        feed(1_900, 400, POCKET)
        feed(2_300, 400, DeviceConditions(screenOn = false, charging = true, wifiConnected = true), CellInfoSource.PUSH)
        val snapshot = stats.snapshot()
        assertEquals(50.0, snapshot.shortIntervalPct!!, 0.0)
        assertEquals(50.0, snapshot.screenOnPct!!, 0.0)
        assertEquals(0.0, snapshot.wifiConnectedPct!!, 0.0)
        assertEquals(0.0, snapshot.chargingPct!!, 0.0)
        // The push still counts as an answer: it repeated a measurement already seen.
        assertEquals(1L, snapshot.freshSamples)
        assertEquals(2L, snapshot.repeatsDropped)
    }

    @Test
    fun eachShareFollowsItsOwnCondition() {
        feed(900, 400, DeviceConditions(screenOn = true, charging = false, wifiConnected = true))
        feed(1_900, 1_400, DeviceConditions(screenOn = true, charging = true, wifiConnected = true))
        feed(2_900, 2_400, DeviceConditions(screenOn = false, charging = true, wifiConnected = false))
        feed(3_900, 3_400, SHORT)
        val snapshot = stats.snapshot()
        assertEquals(50.0, snapshot.shortIntervalPct!!, 0.0)
        assertEquals(75.0, snapshot.screenOnPct!!, 0.0)
        assertEquals(50.0, snapshot.wifiConnectedPct!!, 0.0)
        assertEquals(50.0, snapshot.chargingPct!!, 0.0)
    }

    @Test
    fun withoutRequestAnswersTheSharesAreNull() {
        feed(900, 400, source = CellInfoSource.PUSH)
        val snapshot = stats.snapshot()
        assertNull(snapshot.shortIntervalPct)
        assertNull(snapshot.screenOnPct)
        assertNull(snapshot.wifiConnectedPct)
        assertNull(snapshot.chargingPct)
        assertEquals(1L, snapshot.freshSamples)
    }

    @Test
    fun theMedianIsTheSortedIntervalAtHalfTheCount() {
        feed(900, 400)
        assertNull(stats.snapshot().medianFreshIntervalMs)
        feed(2_900, 2_400)
        assertEquals(2_000L, stats.snapshot().medianFreshIntervalMs)
        feed(13_000, 12_400)
        feed(16_000, 15_400)
        feed(18_000, 17_400)
        // Intervals 2000, 10000, 3000 and 2000 sort to 2000, 2000, 3000, 10000: index 4 / 2 is 3000.
        assertEquals(3_000L, stats.snapshot().medianFreshIntervalMs)
    }

    @Test
    fun anEmptyAnswerIsNeitherFreshNorARepeatButCountsTowardTheShares() {
        stats.onAnswer(engine.classify(answer(900, emptyList(), conditions = POCKET)))
        feed(1_900, 1_400, SHORT)
        val snapshot = stats.snapshot()
        assertEquals(1L, snapshot.freshSamples)
        assertEquals(0L, snapshot.repeatsDropped)
        assertEquals(50.0, snapshot.shortIntervalPct!!, 0.0)
        assertEquals(50.0, snapshot.screenOnPct!!, 0.0)
    }

    @Test
    fun aFreshAnswerMeasuredBeforeThePreviousOneAddsNoInterval() {
        feed(10_900, 10_400)
        // A new primary cell, but measured at 1 s: fresh, and older than the sample before it.
        stats.onAnswer(engine.classify(answer(11_900, listOf(lte(1_000, pci = 300, cellId = 99L)))))
        feed(12_900, 12_400)
        val snapshot = stats.snapshot()
        assertEquals(3L, snapshot.freshSamples)
        assertEquals(2_000L, snapshot.medianFreshIntervalMs)
    }

    @Test
    fun resumeBreaksTheIntervalChain() {
        feed(900, 400)
        stats.onResume()
        feed(60_900, 60_400)
        feed(62_900, 62_400)
        // Without the break the intervals would be 60000 and 2000, and the median 60000.
        assertEquals(2_000L, stats.snapshot().medianFreshIntervalMs)
        assertEquals(3L, stats.snapshot().freshSamples)
    }

    @Test
    fun gapsAreKeptInOrderAndAnEarlierSnapshotDoesNotChange() {
        val first = SamplingGap(WALL0 + 400, WALL0 + 14_400, GapReasons.SCREEN_OFF, WALL0 + 14_900)
        val second = SamplingGap(WALL0 + 20_400, WALL0 + 60_400, GapReasons.APP_PAUSED, WALL0 + 60_900)
        stats.onGap(first)
        val before = stats.snapshot()
        stats.onGap(second)
        assertEquals(listOf(first.toMeta()), before.gaps)
        assertEquals(listOf(first.toMeta(), second.toMeta()), stats.snapshot().gaps)
    }
}

/** A share as session.json writes it: one decimal, half-even on the exact binary value. */
private fun oneDecimal(value: Double?): Double? =
    value?.let { BigDecimal(it).setScale(1, RoundingMode.HALF_EVEN).toDouble() }
