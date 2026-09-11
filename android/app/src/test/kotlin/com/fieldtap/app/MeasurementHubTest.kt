@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.fieldtap.app

import com.fieldtap.core.input.GnssSnapshot
import com.fieldtap.core.input.MeasurementInput
import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.input.ServiceStateSnapshot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementHubTest {
    private val service = ServiceStateSnapshot(ServiceRegState.IN_SERVICE, false, "311480", "Verizon", false, 1_789_050_600_000L, 25_323_456L)
    private val gnss = GnssSnapshot(satellitesVisible = 12, satellitesUsedInFix = 8, observedWallMs = 1_789_050_600_500L, observedElapsedMs = 25_323_956L)

    @Test
    fun nothingStartsAtConstruction() = runTest {
        val radio = Source()
        val location = Source()
        MeasurementHub(backgroundScope, radio.flow, location.flow)
        runCurrent()

        assertEquals(0, radio.subscriptions)
        assertEquals(0, location.subscriptions)
    }

    @Test
    fun everyCollectorSharesOneRegistrationPerSource() = runTest {
        val radio = Source()
        val location = Source()
        val hub = MeasurementHub(backgroundScope, radio.flow, location.flow)
        val first = mutableListOf<MeasurementInput>()
        val second = mutableListOf<MeasurementInput>()

        backgroundScope.launch { hub.inputs.collect { first += it } }
        backgroundScope.launch { hub.inputs.collect { second += it } }
        runCurrent()
        radio.emit(service)
        location.emit(gnss)
        runCurrent()

        assertEquals(1, radio.subscriptions)
        assertEquals(1, location.subscriptions)
        assertEquals(setOf(service, gnss), first.toSet())
        assertEquals(setOf(service, gnss), second.toSet())
    }

    @Test
    fun sourcesStopFiveSecondsAfterTheLastCollectorLeaves() = runTest {
        val radio = Source()
        val location = Source()
        val hub = MeasurementHub(backgroundScope, radio.flow, location.flow)

        val collector = backgroundScope.launch { hub.inputs.collect {} }
        runCurrent()
        assertEquals(1, radio.active)
        assertEquals(1, location.active)

        collector.cancel()
        advanceTimeBy(MeasurementHub.STOP_TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(1, radio.active)
        assertEquals(1, location.active)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(0, radio.active)
        assertEquals(0, location.active)
    }

    @Test
    fun aCollectorThatReturnsWithinTheTimeoutKeepsTheRegistration() = runTest {
        val radio = Source()
        val location = Source()
        val hub = MeasurementHub(backgroundScope, radio.flow, location.flow)

        val first = backgroundScope.launch { hub.inputs.collect {} }
        runCurrent()
        first.cancel()
        advanceTimeBy(3_000)
        val second = backgroundScope.launch { hub.inputs.collect {} }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(1, radio.subscriptions)
        assertEquals(1, radio.active)
        second.cancel()
    }

    @Test
    fun radioInputsDoNotStartTheLocationSource() = runTest {
        val radio = Source()
        val location = Source()
        val hub = MeasurementHub(backgroundScope, radio.flow, location.flow)
        val received = mutableListOf<MeasurementInput>()

        backgroundScope.launch { hub.radioInputs.collect { received += it } }
        runCurrent()
        radio.emit(service)
        runCurrent()

        assertEquals(1, radio.active)
        assertEquals(0, location.subscriptions)
        assertEquals(listOf<MeasurementInput>(service), received)
    }

    @Test
    fun aFailingSourceIsReportedAndRestarted() = runTest {
        var attempts = 0
        val failing = flow<MeasurementInput> {
            attempts++
            if (attempts == 1) throw IllegalStateException("adapter bug")
            emit(service)
            awaitCancellation()
        }
        val errors = mutableListOf<Throwable>()
        val hub = MeasurementHub(backgroundScope, failing, Source().flow, onSourceError = { errors += it })
        val received = mutableListOf<MeasurementInput>()

        backgroundScope.launch { hub.inputs.collect { received += it } }
        runCurrent()
        assertEquals(1, errors.size)
        assertTrue(received.isEmpty())

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(listOf<MeasurementInput>(service), received)
    }

    @Test
    fun aSourceThatEndsByItselfIsSubscribedAgainAfterAPause() = runTest {
        var subscriptions = 0
        val ending = flow<MeasurementInput> {
            subscriptions++
            emit(gnss)
        }
        val hub = MeasurementHub(backgroundScope, Source().flow, ending)
        val received = mutableListOf<MeasurementInput>()

        backgroundScope.launch { hub.inputs.collect { received += it } }
        runCurrent()
        assertEquals(1, subscriptions)

        advanceTimeBy(MeasurementHub.SOURCE_RESUBSCRIBE_MS - 1)
        runCurrent()
        assertEquals(1, subscriptions)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, subscriptions)
        assertEquals(listOf<MeasurementInput>(gnss, gnss), received)
    }

    /** A cold source that counts its registrations. */
    private class Source {
        var subscriptions = 0
        var active = 0
        private val events = MutableSharedFlow<MeasurementInput>(extraBufferCapacity = 16)

        val flow: Flow<MeasurementInput> = flow {
            subscriptions++
            active++
            try {
                emitAll(events)
            } finally {
                active--
            }
        }

        suspend fun emit(input: MeasurementInput) {
            events.emit(input)
        }
    }
}
