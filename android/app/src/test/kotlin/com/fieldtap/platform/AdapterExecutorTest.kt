package com.fieldtap.platform

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterExecutorTest {

    @Test
    fun callbacksRunInOrderOnTheAdaptersOwnThread() {
        val executor = AdapterExecutor("fieldtap-test")
        val seen: MutableList<String> = Collections.synchronizedList(ArrayList())
        val done = CountDownLatch(1)
        try {
            for (index in 1..5) {
                executor.execute { seen.add("${Thread.currentThread().name}:$index") }
            }
            executor.execute { done.countDown() }

            assertTrue("callbacks did not run", done.await(5, TimeUnit.SECONDS))
            assertEquals((1..5).map { "fieldtap-test:$it" }, seen.toList())
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun callbacksAfterShutdownAreDiscardedWithoutThrowing() {
        val executor = AdapterExecutor("fieldtap-test-late")
        executor.shutdown()
        val ran = AtomicBoolean(false)

        executor.execute { ran.set(true) }

        assertTrue(executor.isShutdown)
        assertFalse(ran.get())
    }
}
