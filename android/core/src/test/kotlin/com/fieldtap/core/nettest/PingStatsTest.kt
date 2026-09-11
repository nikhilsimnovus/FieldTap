package com.fieldtap.core.nettest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PingStatsTest {
    private val delta = 1e-9

    @Test
    fun allRepliesGiveNoLossAndTheirMinMeanAndMax() {
        val summary = PingStats.summarize(5, listOf(38.2, 40.0, 44.7, 58.9, 41.7))

        assertEquals(0.0, summary.lossPct, delta)
        assertEquals(38.2, summary.rttMinMs!!, delta)
        assertEquals(44.7, summary.rttAvgMs!!, delta)
        assertEquals(58.9, summary.rttMaxMs!!, delta)
    }

    @Test
    fun lossIsTheShareOfEchoesWithoutReply() {
        val summary = PingStats.summarize(5, listOf(30.0, 50.0))

        assertEquals(60.0, summary.lossPct, delta)
        assertEquals(30.0, summary.rttMinMs!!, delta)
        assertEquals(40.0, summary.rttAvgMs!!, delta)
        assertEquals(50.0, summary.rttMaxMs!!, delta)
    }

    @Test
    fun noRepliesGiveFullLossAndNoRtts() {
        val summary = PingStats.summarize(5, emptyList())

        assertEquals(100.0, summary.lossPct, delta)
        assertNull(summary.rttMinMs)
        assertNull(summary.rttAvgMs)
        assertNull(summary.rttMaxMs)
    }

    @Test
    fun nothingSentIsFullLoss() {
        assertEquals(100.0, PingStats.summarize(0, emptyList()).lossPct, delta)
        assertEquals(100.0, PingStats.summarize(-1, emptyList()).lossPct, delta)
    }

    @Test
    fun aDuplicateReplyNeverMakesLossNegative() {
        val summary = PingStats.summarize(2, listOf(10.0, 11.0, 12.0))
        assertEquals(0.0, summary.lossPct, delta)
        assertEquals(12.0, summary.rttMaxMs!!, delta)
    }

    @Test
    fun rttsThatAreNotNumbersAreNotReplies() {
        val summary = PingStats.summarize(4, listOf(Double.NaN, -1.0, Double.POSITIVE_INFINITY, 20.0))
        assertEquals(75.0, summary.lossPct, delta)
        assertEquals(20.0, summary.rttAvgMs!!, delta)
    }

    @Test
    fun oneSubMillisecondReplyKeepsItsFraction() {
        val summary = PingStats.summarize(1, listOf(0.7))
        assertEquals(0.7, summary.rttMinMs!!, delta)
        assertEquals(0.7, summary.rttMaxMs!!, delta)
    }
}
