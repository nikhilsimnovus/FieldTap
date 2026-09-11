package com.fieldtap.core.nettest

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** On real threads: the blocking call stands for an HTTP response wait that only closing its socket ends. */
class InterruptingOnCancelTest {

    @Test
    fun cancellingTheCallerInterruptsABlockedCallAtOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val interrupts = AtomicInteger()
        val endedBy = AtomicReference<String?>(null)
        val download = scope.launch {
            interruptingOnCancel(interrupt = { interrupts.incrementAndGet(); closed.countDown() }) {
                entered.countDown()
                // Blocks the thread and ignores cancellation, as a socket read does, until its socket is closed.
                val how = if (closed.await(BLOCK_LIMIT_S, TimeUnit.SECONDS)) "socket closed" else "timed out"
                endedBy.set(how)
                how
            }
        }
        assertTrue(entered.await(WAIT_S, TimeUnit.SECONDS))

        val startedNs = System.nanoTime()
        download.cancelAndJoin()
        val tookMs = (System.nanoTime() - startedNs) / 1_000_000

        assertTrue("the cancelled call took $tookMs ms", tookMs < PROMPT_MS)
        assertEquals(1, interrupts.get())
        // The blocked call ended because its socket was closed, not by its own timeout. A cancelled caller never
        // receives the value, so it is read from inside the call.
        assertEquals("socket closed", endedBy.get())
        assertTrue(download.isCancelled)
        scope.cancel()
    }

    @Test
    fun aCallThatFinishesIsNeverInterrupted() = runBlocking {
        val interrupts = AtomicInteger()

        val value = withContext(Dispatchers.IO) { interruptingOnCancel(interrupt = { interrupts.incrementAndGet() }) { 42 } }
        Thread.sleep(100)

        assertEquals(42, value)
        assertEquals(0, interrupts.get())
    }

    @Test
    fun aCallThatFailsIsNeverInterrupted() = runBlocking {
        val interrupts = AtomicInteger()

        val failure = runCatching {
            withContext(Dispatchers.IO) {
                interruptingOnCancel(interrupt = { interrupts.incrementAndGet() }) { throw java.io.IOException("reset") }
            }
        }.exceptionOrNull()
        Thread.sleep(100)

        assertTrue(failure is java.io.IOException)
        assertEquals(0, interrupts.get())
    }

    private companion object {
        const val WAIT_S: Long = 10
        const val BLOCK_LIMIT_S: Long = 20
        const val PROMPT_MS: Long = 5_000
    }
}
