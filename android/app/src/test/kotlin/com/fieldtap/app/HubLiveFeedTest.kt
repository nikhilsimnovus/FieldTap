@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.fieldtap.app

import com.fieldtap.core.input.GnssSnapshot
import com.fieldtap.core.input.MeasurementInput
import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.core.live.LiveState
import com.fieldtap.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HubLiveFeedTest {

    @Test
    fun foldsInputsAndTicksOnceASecondWithTheElapsedClock() = runTest {
        val inputs = MutableSharedFlow<MeasurementInput>(extraBufferCapacity = 16)
        val feed = feed(inputs)

        val collector = backgroundScope.launch { feed.state.collect {} }
        runCurrent()
        assertEquals(ELAPSED_BASE, feed.state.value.nowElapsedMs)

        inputs.emit(gnss())
        inputs.emit(gnss())
        runCurrent()
        assertEquals(2, feed.state.value.gnss?.satellitesVisible)

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(ELAPSED_BASE + 3_000, feed.state.value.nowElapsedMs)
        collector.cancel()
    }

    @Test
    fun continuesFromTheLastStateWhenACollectorReturns() = runTest {
        val inputs = MutableSharedFlow<MeasurementInput>(extraBufferCapacity = 16)
        val feed = feed(inputs)

        val first = backgroundScope.launch { feed.state.collect {} }
        runCurrent()
        inputs.emit(gnss())
        runCurrent()
        first.cancel()
        runCurrent()

        val second = backgroundScope.launch { feed.state.collect {} }
        runCurrent()
        inputs.emit(gnss())
        runCurrent()

        assertEquals(2, feed.state.value.gnss?.satellitesVisible)
        second.cancel()
    }

    @Test
    fun stopsFoldingWhenNobodyCollects() = runTest {
        val inputs = MutableSharedFlow<MeasurementInput>(extraBufferCapacity = 16)
        val feed = feed(inputs)

        val collector = backgroundScope.launch { feed.state.collect {} }
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        val lastTick = feed.state.value.nowElapsedMs
        collector.cancel()
        runCurrent()

        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(ELAPSED_BASE + 2_000, lastTick)
        assertEquals(lastTick, feed.state.value.nowElapsedMs)
    }

    @Test
    fun aReducerFailureSkipsThatInputOnly() = runTest {
        val inputs = MutableSharedFlow<MeasurementInput>(extraBufferCapacity = 16)
        val errors = mutableListOf<Throwable>()
        val feed = feed(inputs, errors)

        val collector = backgroundScope.launch { feed.state.collect {} }
        runCurrent()
        inputs.emit(service("boom"))
        runCurrent()
        assertEquals(1, errors.size)
        assertNull(feed.state.value.service)

        val good = service("311480")
        inputs.emit(good)
        runCurrent()
        assertEquals(good, feed.state.value.service)

        inputs.emit(service("checked"))
        runCurrent()
        assertEquals("a checked exception is skipped the same way", 2, errors.size)
        assertEquals(good, feed.state.value.service)
        collector.cancel()
    }

    private fun TestScope.feed(inputs: Flow<MeasurementInput>, errors: MutableList<Throwable> = mutableListOf()) = HubLiveFeed(
        scope = backgroundScope,
        inputs = inputs,
        clock = TestClock(testScheduler),
        reduce = ::countingReduce,
        tick = ::tickTo,
        onError = { errors += it },
        context = StandardTestDispatcher(testScheduler),
    )

    private class TestClock(private val scheduler: TestCoroutineScheduler) : Clock {
        override fun wallMillis(): Long = WALL_BASE + scheduler.currentTime

        override fun elapsedRealtimeMillis(): Long = ELAPSED_BASE + scheduler.currentTime
    }
}

private const val WALL_BASE = 1_789_050_600_000L
private const val ELAPSED_BASE = 25_323_456L

/** A stand-in reducer: counts GNSS inputs, keeps the latest service state, and fails on operator "boom". */
private fun countingReduce(state: LiveState, input: MeasurementInput): LiveState = when (input) {
    is GnssSnapshot -> state.copy(
        gnss = GnssSnapshot((state.gnss?.satellitesVisible ?: 0) + 1, 0, input.observedWallMs, input.observedElapsedMs),
    )
    is ServiceStateSnapshot -> {
        if (input.operatorNumeric == "boom") throw IllegalStateException("reducer bug")
        if (input.operatorNumeric == "checked") throw java.io.IOException("checked failure")
        state.copy(service = input)
    }
    else -> state
}

private fun tickTo(state: LiveState, nowElapsedMs: Long): LiveState = state.copy(nowElapsedMs = nowElapsedMs)

private fun gnss(): GnssSnapshot = GnssSnapshot(1, 1, WALL_BASE, ELAPSED_BASE)

private fun service(operator: String): ServiceStateSnapshot =
    ServiceStateSnapshot(ServiceRegState.IN_SERVICE, false, operator, null, false, WALL_BASE, ELAPSED_BASE)
