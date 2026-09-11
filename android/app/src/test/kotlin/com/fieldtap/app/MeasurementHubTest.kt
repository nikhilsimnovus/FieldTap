@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.fieldtap.app

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.DataConnState
import com.fieldtap.core.input.DataStateSnapshot
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.input.DisplayInfoSnapshot
import com.fieldtap.core.input.GnssSnapshot
import com.fieldtap.core.input.LocationAvailability
import com.fieldtap.core.input.MeasurementInput
import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.core.input.SignalSnapshot
import com.fieldtap.core.time.ManualClock
import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.FixProvider
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementHubTest {
    private val clock = ManualClock(wallMs = 1_789_050_600_000L, elapsedMs = 25_323_456L)
    private val service = ServiceStateSnapshot(ServiceRegState.IN_SERVICE, false, "311480", "Verizon", false, 1_789_050_600_000L, 25_323_456L)
    private val data = DataStateSnapshot(DataConnState.CONNECTED, networkType = 20, observedWallMs = 1_789_050_600_100L, observedElapsedMs = 25_323_556L)
    private val display = DisplayInfoSnapshot(networkType = 20, overrideNetworkType = 0, observedWallMs = 1_789_050_600_200L, observedElapsedMs = 25_323_656L)
    private val signal = SignalSnapshot(null, null, null, -80, -10, 12, 3, 25_323_000L, 1_789_050_600_250L, 25_323_706L)
    private val answer = CellInfoAnswer(
        source = CellInfoSource.REQUEST,
        cells = emptyList(),
        subId = 1,
        conditions = DeviceConditions(screenOn = true, charging = false, wifiConnected = false),
        observedWallMs = 1_789_050_600_300L,
        observedElapsedMs = 25_323_756L,
    )
    private val availability = LocationAvailability(true, true, setOf(FixProvider.GPS), 1_789_050_600_400L, 25_323_856L)
    private val gnss = GnssSnapshot(satellitesVisible = 12, satellitesUsedInFix = 8, observedWallMs = 1_789_050_600_500L, observedElapsedMs = 25_323_956L)

    @Test
    fun nothingStartsAtConstruction() = runTest {
        val radio = Source()
        val location = Source()
        MeasurementHub(backgroundScope, radio.flow, location.flow, clock)
        runCurrent()

        assertEquals(0, radio.subscriptions)
        assertEquals(0, location.subscriptions)
    }

    @Test
    fun everyCollectorSharesOneRegistrationPerSource() = runTest {
        val radio = Source()
        val location = Source()
        val hub = MeasurementHub(backgroundScope, radio.flow, location.flow, clock)
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
    fun aCollectorThatJoinsLateGetsTheCurrentStateStampedWhenItJoinedThenEveryInput() = runTest {
        val radio = Source()
        val location = Source()
        val hub = MeasurementHub(backgroundScope, radio.flow, location.flow, clock)
        // The Live screen collects; Android delivered service, data and display state once, at registration.
        backgroundScope.launch { hub.inputs.collect {} }
        runCurrent()
        for (input in listOf(service, data, display, signal, answer)) radio.emit(input)
        location.emit(availability)
        location.emit(gnss)
        runCurrent()
        clock.advance(30_000)

        // A session starts from the Live screen.
        val recorder = mutableListOf<MeasurementInput>()
        backgroundScope.launch { hub.inputsWithCurrentState.collect { recorder += it } }
        runCurrent()

        val wallMs = clock.wallMillis()
        val elapsedMs = clock.elapsedRealtimeMillis()
        assertEquals(
            listOf(
                service.copy(observedWallMs = wallMs, observedElapsedMs = elapsedMs),
                data.copy(observedWallMs = wallMs, observedElapsedMs = elapsedMs),
                display.copy(observedWallMs = wallMs, observedElapsedMs = elapsedMs),
                availability.copy(observedWallMs = wallMs, observedElapsedMs = elapsedMs),
            ),
            recorder,
        )
        assertEquals(display, hub.currentDisplayInfo())

        location.emit(gnss)
        runCurrent()
        assertEquals(gnss, recorder.last())
        assertEquals(1, radio.subscriptions)
    }

    @Test
    fun whatTheSourcesDeliveredIsForgottenWhenTheyStop() = runTest {
        val radio = Source()
        val location = Source()
        val hub = MeasurementHub(backgroundScope, radio.flow, location.flow, clock)
        val first = backgroundScope.launch { hub.inputs.collect {} }
        runCurrent()
        radio.emit(service)
        radio.emit(display)
        runCurrent()
        assertEquals(listOf<MeasurementInput>(service, display), hub.currentState())

        first.cancel()
        advanceTimeBy(MeasurementHub.STOP_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(0, radio.active)
        assertTrue(hub.currentState().isEmpty())
        assertNull(hub.currentDisplayInfo())

        val late = mutableListOf<MeasurementInput>()
        backgroundScope.launch { hub.inputsWithCurrentState.collect { late += it } }
        runCurrent()
        assertTrue("nothing from an earlier run is replayed", late.isEmpty())
    }

    @Test
    fun sourcesStopFiveSecondsAfterTheLastCollectorLeaves() = runTest {
        val radio = Source()
        val location = Source()
        val hub = MeasurementHub(backgroundScope, radio.flow, location.flow, clock)

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
        val hub = MeasurementHub(backgroundScope, radio.flow, location.flow, clock)

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
        val hub = MeasurementHub(backgroundScope, radio.flow, location.flow, clock)
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
        val hub = MeasurementHub(backgroundScope, failing, Source().flow, clock, onSourceError = { errors += it })
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
        val hub = MeasurementHub(backgroundScope, Source().flow, ending, clock)
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
