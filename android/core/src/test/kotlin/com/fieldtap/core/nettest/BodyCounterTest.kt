package com.fieldtap.core.nettest

import com.fieldtap.core.time.ManualClock
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyCounterTest {
    private val clock = ManualClock()

    @Test
    fun readsToTheEndOfTheBody() {
        val result = BodyCounter.count(ByteArrayInputStream(ByteArray(100_000)), 1_000_000, clock, clock.elapsedMs + 60_000)

        assertEquals(100_000L, result.bytes)
        assertEquals(BodyCount.End.COMPLETE, result.end)
        assertNull(result.error)
    }

    @Test
    fun stopsExactlyAtTheCap() {
        val result = BodyCounter.count(ByteArrayInputStream(ByteArray(300_000)), 100_000, clock, clock.elapsedMs + 60_000)

        assertEquals(100_000L, result.bytes)
        assertEquals(BodyCount.End.CAP, result.end)
    }

    @Test
    fun stopsAtTheDeadlineWhileDataStillArrives() {
        val slow = object : InputStream() {
            override fun read(): Int = 0

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                clock.advance(1_000)
                return minOf(len, 1_000)
            }
        }

        val result = BodyCounter.count(slow, 10_000_000, clock, clock.elapsedMs + 5_000)

        assertEquals(5_000L, result.bytes)
        assertEquals(BodyCount.End.DEADLINE, result.end)
    }

    @Test
    fun aFailedReadKeepsTheBytesReadSoFar() {
        val stalling = object : InputStream() {
            var reads = 0

            override fun read(): Int = throw IOException("not used")

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                if (reads > 2) throw SocketTimeoutException("stalled")
                return minOf(len, 500)
            }
        }

        val result = BodyCounter.count(stalling, 10_000_000, clock, clock.elapsedMs + 60_000)

        assertEquals(1_000L, result.bytes)
        assertEquals(BodyCount.End.ERROR, result.end)
        assertTrue(result.error is SocketTimeoutException)
    }

    @Test
    fun stopsAsSoonAsTheCallerIsNoLongerActive() {
        val result = BodyCounter.count(ByteArrayInputStream(ByteArray(10)), 100, clock, clock.elapsedMs + 60_000, isActive = { false })

        assertEquals(0L, result.bytes)
        assertEquals(BodyCount.End.CANCELLED, result.end)
    }

    @Test
    fun aCapOfZeroReadsNothing() {
        val result = BodyCounter.count(ByteArrayInputStream(ByteArray(10)), 0, clock, clock.elapsedMs + 60_000)

        assertEquals(0L, result.bytes)
        assertEquals(BodyCount.End.CAP, result.end)
    }
}
