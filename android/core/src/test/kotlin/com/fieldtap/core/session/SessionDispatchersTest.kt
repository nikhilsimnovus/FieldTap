package com.fieldtap.core.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** The session dispatcher on real threads: one task at a time within a session, nothing shared between sessions. */
class SessionDispatchersTest {
    @Test
    fun aSessionDispatcherRunsOneTaskAtATime() = runBlocking {
        val dispatcher = SessionDispatchers.newSessionDispatcher()
        val running = AtomicInteger(0)
        val mostAtOnce = AtomicInteger(0)

        withTimeout(TIMEOUT_MS) {
            (1..16).map {
                async(dispatcher) {
                    mostAtOnce.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                    // Blocking on purpose: a second task entering meanwhile would be counted above.
                    Thread.sleep(5)
                    running.decrementAndGet()
                }
            }.awaitAll()
        }

        assertEquals(1, mostAtOnce.get())
        assertEquals(0, running.get())
    }

    @Test
    fun twoSessionsNeverWaitForEachOther() = runBlocking {
        val bothInside = CountDownLatch(2)

        val entered = withTimeout(TIMEOUT_MS) {
            listOf(SessionDispatchers.newSessionDispatcher(), SessionDispatchers.newSessionDispatcher()).map { dispatcher ->
                async(dispatcher) {
                    bothInside.countDown()
                    bothInside.await(LATCH_SECONDS, TimeUnit.SECONDS)
                }
            }.awaitAll()
        }

        assertEquals(listOf(true, true), entered)
    }

    private companion object {
        const val TIMEOUT_MS: Long = 30_000
        const val LATCH_SECONDS: Long = 10
    }
}
