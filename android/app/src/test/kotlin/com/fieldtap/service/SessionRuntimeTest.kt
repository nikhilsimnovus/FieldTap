@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.fieldtap.service

import com.fieldtap.app.SessionStatus
import com.fieldtap.app.SoakState
import com.fieldtap.app.StartResult
import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.input.LocationAvailability
import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.core.nettest.TestSettings
import com.fieldtap.core.session.ExitRecord
import com.fieldtap.core.session.HeartbeatRecord
import com.fieldtap.core.session.OpenSession
import com.fieldtap.core.session.RecorderCommand
import com.fieldtap.core.session.RecoveryAction
import com.fieldtap.core.session.SessionCommand
import com.fieldtap.core.session.SessionListing
import com.fieldtap.core.session.SessionOutcome
import com.fieldtap.core.session.StartRefusal
import com.fieldtap.core.session.StartRequest
import com.fieldtap.core.session.StopCause
import com.fieldtap.core.session.StoragePolicy
import com.fieldtap.core.session.StorageStatus
import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.TrafficTest
import com.fieldtap.recovery.LaunchRecovery
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRuntimeTest {

    @Test
    fun aStartWithoutConsentIsRefusedAndNothingStarts() = runTest {
        val h = RuntimeHarness(this)
        h.settings.set(h.settings.value.copy(consent = null))

        assertEquals(StartResult.Refused(StartRefusal.NO_CONSENT), h.runtime.start(StartRequest("walk")))
        assertTrue(h.platform.started.isEmpty())
        assertEquals(SessionStatus.Idle, h.runtime.status.value)
    }

    @Test
    fun refusalsFollowThePreconditions() = runTest {
        val h = RuntimeHarness(this)

        assertEquals(StartResult.Refused(StartRefusal.BLANK_NAME), h.runtime.start(StartRequest("   ")))
        h.platform.precise = false
        assertEquals(StartResult.Refused(StartRefusal.NO_PRECISE_LOCATION), h.runtime.start(StartRequest("walk")))
        h.platform.precise = true
        h.platform.locationOn = false
        assertEquals(StartResult.Refused(StartRefusal.LOCATION_OFF), h.runtime.start(StartRequest("walk")))
        h.platform.locationOn = true
        h.storage = StorageStatus(usedBytes = 0, freeBytes = 10_000_000, policy = StoragePolicy())
        assertEquals(StartResult.Refused(StartRefusal.STORAGE_FULL), h.runtime.start(StartRequest("walk")))

        assertTrue(h.platform.started.isEmpty())
        assertEquals(SessionStatus.Idle, h.runtime.status.value)
    }

    @Test
    fun nothingIsPreparedUntilTheServiceIsInTheForeground() = runTest {
        val h = RuntimeHarness(this)

        assertEquals(StartResult.Accepted, h.runtime.start(StartRequest("walk")))
        runCurrent()

        assertEquals(listOf(SessionService.ACTION_START), h.platform.started.toList())
        assertTrue(h.runtime.status.value is SessionStatus.Starting)
        assertTrue(h.factory.prepared.isEmpty())
    }

    @Test
    fun anAcceptedStartRecordsAndForwardsEveryInput() = runTest {
        val h = RuntimeHarness(this)
        val recorder = startRecording(h)

        assertEquals(listOf(StartRequest("walk")), h.factory.prepared.toList())
        assertEquals(1, recorder.runs.get())
        assertEquals(recorder.dirName, h.runtime.activeDirName())
        assertEquals(recorder.dirName, (h.runtime.status.value as SessionStatus.Recording).snapshot.dirName)
        assertEquals(WALL_BASE, h.settings.value.lastSessionStartedUtcMs)

        val service = serviceState()
        h.inputs.emit(service)
        runCurrent()
        assertEquals(listOf(RecorderCommand.Measurement(service)), recorder.commandsOf<RecorderCommand.Measurement>())

        recorder.snapshot.value = recorder.snapshot.value.copy(freshSamples = 7)
        runCurrent()
        assertEquals(7L, (h.runtime.status.value as SessionStatus.Recording).snapshot.freshSamples)
    }

    @Test
    fun statusSaysRecordingOnlyOnceTheSessionFilesExist() = runTest {
        val h = RuntimeHarness(this)
        val created = CompletableDeferred<Unit>()
        h.factory.createGate = created

        assertEquals(StartResult.Accepted, h.runtime.start(StartRequest("walk")))
        assertTrue(h.serviceStarts(SessionService.ACTION_START))
        runCurrent()

        val recorder = h.recorder()
        assertEquals(1, recorder.runs.get())
        assertTrue("status is ${h.runtime.status.value}", h.runtime.status.value is SessionStatus.Starting)
        assertEquals("the directory is guarded while its files are made", recorder.dirName, h.runtime.activeDirName())
        assertFalse(h.runtime.mark("too early"))
        assertNull(h.settings.value.lastSessionStartedUtcMs)

        h.inputs.emit(serviceState())
        runCurrent()
        assertEquals("inputs are queued for the recorder meanwhile", 1, recorder.commandsOf<RecorderCommand.Measurement>().size)

        created.complete(Unit)
        runCurrent()

        assertTrue("status is ${h.runtime.status.value}", h.runtime.status.value is SessionStatus.Recording)
        assertEquals(WALL_BASE, h.settings.value.lastSessionStartedUtcMs)
        assertTrue(h.runtime.mark("now"))
    }

    @Test
    fun sessionFilesThatCannotBeCreatedFailTheStartAndRemoveTheEmptyDirectory() = runTest {
        val h = RuntimeHarness(this)
        h.factory.createFailure = IOException("read-only storage")

        assertEquals(StartResult.Accepted, h.runtime.start(StartRequest("walk")))
        assertTrue(h.serviceStarts(SessionService.ACTION_START))
        runCurrent()

        assertEquals(SessionStatus.Idle, h.runtime.status.value)
        assertNull("no session exists, so none is published", h.runtime.lastOutcome.value)
        assertEquals(listOf(h.recorder().dirName), h.factory.discarded.toList())
        assertNull(h.runtime.activeDirName())
        assertNull(h.settings.value.lastSessionStartedUtcMs)
        assertEquals(1, h.host.stops)
        assertTrue(h.platform.logs.contains("Could not create the session files"))

        h.factory.createFailure = null
        startRecording(h, StartRequest("walk again"))
    }

    @Test
    fun stopEndsTheRecordingPublishesTheOutcomeAndStopsTheService() = runTest {
        val h = RuntimeHarness(this)
        val recorder = startRecording(h)

        h.runtime.stop()
        runCurrent()

        assertEquals(listOf(RecorderCommand.Stop(StopCause.USER)), recorder.commandsOf<RecorderCommand.Stop>())
        assertEquals(SessionStatus.Idle, h.runtime.status.value)
        assertEquals(recorder.dirName, h.runtime.lastOutcome.value?.dirName)
        assertEquals("user", h.runtime.lastOutcome.value?.stoppedBy)
        assertEquals(1, h.host.stops)
        assertNull(h.runtime.activeDirName())
        assertTrue(h.factory.discarded.isEmpty())

        h.runtime.stop()
        runCurrent()
        assertEquals(1, recorder.commandsOf<RecorderCommand.Stop>().size)
        assertEquals(1, h.host.stops)
    }

    @Test
    fun aSecondStartIsRefusedWhileASessionRuns() = runTest {
        val h = RuntimeHarness(this)
        startRecording(h)

        assertEquals(StartResult.Refused(StartRefusal.SESSION_RUNNING), h.runtime.start(StartRequest("another")))
        assertEquals(1, h.factory.prepared.size)
    }

    @Test
    fun aRecorderThatStopsItselfIsFinishedWithItsCause() = runTest {
        val h = RuntimeHarness(this)
        val recorder = startRecording(h)

        recorder.stopByItself(StopCause.STORAGE_FULL.token)
        runCurrent()

        assertEquals(SessionStatus.Idle, h.runtime.status.value)
        assertEquals("storage_full", h.runtime.lastOutcome.value?.stoppedBy)
        assertTrue("a finished recorder is not told to stop", recorder.commandsOf<RecorderCommand.Stop>().isEmpty())
        assertEquals(1, h.host.stops)
        assertNull(h.runtime.activeDirName())
    }

    @Test
    fun aRecorderThatCrashesPublishesTheStopItWrote() = runTest {
        val h = RuntimeHarness(this)
        val recorder = startRecording(h)

        recorder.crash(IllegalStateException("pipeline bug"), finalizedAs = "crash")
        runCurrent()

        val outcome = h.runtime.lastOutcome.value
        assertEquals(SessionStatus.Idle, h.runtime.status.value)
        assertEquals("crash", outcome?.stoppedBy)
        assertEquals(false, outcome?.interrupted)
        assertTrue(h.platform.logs.contains("The recorder stopped with an error"))
        assertEquals(1, h.host.stops)
    }

    @Test
    fun aRecorderThatFailsWithoutFinalizingIsPublishedAsInterrupted() = runTest {
        val h = RuntimeHarness(this)
        val recorder = startRecording(h)

        recorder.crash(IOException("disk removed"))
        runCurrent()

        val outcome = h.runtime.lastOutcome.value
        assertEquals(SessionStatus.Idle, h.runtime.status.value)
        assertEquals("unknown", outcome?.stoppedBy)
        assertEquals(true, outcome?.interrupted)
        assertEquals(recorder.dirName, outcome?.dirName)
        assertTrue(h.platform.logs.contains("The recorder stopped with an error"))
        assertEquals(1, h.host.stops)
    }

    @Test
    fun markWritesOnlyWhileRecordingAndOutsideAPrivacyZone() = runTest {
        val h = RuntimeHarness(this)
        assertFalse(h.runtime.mark("before the session"))

        val recorder = startRecording(h)
        assertTrue(h.runtime.mark("  north door  "))
        assertTrue(h.runtime.mark("   "))
        recorder.setPaused(true)
        assertFalse(h.runtime.mark("inside a zone"))

        val marks = recorder.commandsOf<RecorderCommand.Mark>()
        assertEquals(listOf("north door", null), marks.map { it.note })
        assertEquals(WALL_BASE + testScheduler.currentTime, marks.first().wallMs)
    }

    @Test
    fun theNotificationActionsMarkAndStop() = runTest {
        val h = RuntimeHarness(this)
        val recorder = startRecording(h)

        assertTrue(h.serviceStarts(SessionService.ACTION_MARK))
        assertEquals(listOf<String?>(null), recorder.commandsOf<RecorderCommand.Mark>().map { it.note })

        h.serviceStarts(SessionService.ACTION_STOP)
        runCurrent()
        assertEquals(SessionStatus.Idle, h.runtime.status.value)
        assertEquals("user", h.runtime.lastOutcome.value?.stoppedBy)
        assertFalse("an idle service is not needed", h.serviceStarts(SessionService.ACTION_MARK))
    }

    @Test
    fun anExternalCommandOtherThanStopIsIgnored() = runTest {
        val h = RuntimeHarness(this)

        h.runtime.dispatch(SessionCommand.Started("20260910-143000_fake", WALL_BASE))
        assertEquals(SessionStatus.Idle, h.runtime.status.value)
        assertTrue(h.platform.logs.contains("Ignored an external Started command"))

        val recorder = startRecording(h)
        h.runtime.dispatch(SessionCommand.Stop(StopCause.USER))
        runCurrent()

        assertEquals("user", h.runtime.lastOutcome.value?.stoppedBy)
        assertEquals(1, recorder.commandsOf<RecorderCommand.Stop>().size)
    }

    @Test
    fun aDestroyedServiceStopsTheSessionWithServiceDestroyed() = runTest {
        val h = RuntimeHarness(this)
        val recorder = startRecording(h)

        h.runtime.detachHost(h.host)
        runCurrent()

        assertEquals(listOf(RecorderCommand.Stop(StopCause.SERVICE_DESTROYED)), recorder.commandsOf<RecorderCommand.Stop>())
        assertEquals("service_destroyed", h.runtime.lastOutcome.value?.stoppedBy)
        assertEquals(SessionStatus.Idle, h.runtime.status.value)
        assertEquals("a detached service is not stopped again", 0, h.host.stops)
    }

    @Test
    fun revokedPreciseLocationStopsTheSession() = runTest {
        val h = RuntimeHarness(this)
        startRecording(h)

        h.inputs.emit(
            LocationAvailability(
                locationEnabled = true,
                preciseLocationGranted = false,
                providers = emptySet(),
                observedWallMs = WALL_BASE,
                observedElapsedMs = ELAPSED_BASE,
            ),
        )
        runCurrent()

        assertEquals("permission_revoked", h.runtime.lastOutcome.value?.stoppedBy)
        assertEquals(SessionStatus.Idle, h.runtime.status.value)
    }

    @Test
    fun androidRefusingTheServiceStartReturnsToIdle() = runTest {
        val h = RuntimeHarness(this)
        h.platform.refuseStart = IllegalStateException("app in background")

        assertEquals(StartResult.Accepted, h.runtime.start(StartRequest("walk")))
        runCurrent()

        assertEquals(SessionStatus.Idle, h.runtime.status.value)
        assertTrue(h.factory.prepared.isEmpty())
        assertTrue(h.platform.logs.contains("Android refused to start the session service"))
    }

    @Test
    fun aRefusedForegroundServiceReturnsToIdleWithoutADirectory() = runTest {
        val h = RuntimeHarness(this)
        assertEquals(StartResult.Accepted, h.runtime.start(StartRequest("walk")))

        h.runtime.attachHost(h.host)
        h.runtime.onForegroundRefused(SessionService.ACTION_START, SecurityException("location permission missing"))
        runCurrent()

        assertEquals(SessionStatus.Idle, h.runtime.status.value)
        assertFalse(h.runtime.onServiceStartCommand(SessionService.ACTION_START))
        runCurrent()
        assertTrue(h.factory.prepared.isEmpty())
        assertEquals(1, h.host.stops)
    }

    @Test
    fun aServiceThatNeverStartsTimesOut() = runTest {
        val h = RuntimeHarness(this)
        assertEquals(StartResult.Accepted, h.runtime.start(StartRequest("walk")))

        advanceTimeBy(SessionRuntime.SERVICE_START_TIMEOUT_MS + 1)
        runCurrent()

        assertEquals(SessionStatus.Idle, h.runtime.status.value)
        assertTrue(h.platform.logs.contains("The session service did not start in time"))
    }

    @Test
    fun aFailedPreparationReturnsToIdleAndStopsTheService() = runTest {
        val h = RuntimeHarness(this)
        h.factory.failure = IOException("storage unavailable")

        assertEquals(StartResult.Accepted, h.runtime.start(StartRequest("walk")))
        assertTrue(h.serviceStarts(SessionService.ACTION_START))
        runCurrent()

        assertEquals(SessionStatus.Idle, h.runtime.status.value)
        assertNull(h.runtime.lastOutcome.value)
        assertEquals(1, h.host.stops)
        assertTrue(h.platform.logs.contains("Could not prepare the session directory"))
    }

    @Test
    fun aStopBeforeTheServiceStartsCreatesNoDirectory() = runTest {
        val h = RuntimeHarness(this)
        assertEquals(StartResult.Accepted, h.runtime.start(StartRequest("walk")))

        h.runtime.stop()
        assertEquals(SessionStatus.Idle, h.runtime.status.value)
        assertFalse(h.serviceStarts(SessionService.ACTION_START))
        runCurrent()

        assertTrue(h.factory.prepared.isEmpty())
        assertNull(h.runtime.lastOutcome.value)
    }

    @Test
    fun aStopDuringPreparationEndsTheSessionRightAfterItStarts() = runTest {
        val h = RuntimeHarness(this)
        val gate = CompletableDeferred<Unit>()
        h.factory.gate = gate
        assertEquals(StartResult.Accepted, h.runtime.start(StartRequest("walk")))
        assertTrue(h.serviceStarts(SessionService.ACTION_START))
        runCurrent()
        assertEquals(1, h.factory.prepared.size)

        h.runtime.stop()
        runCurrent()
        assertTrue(h.runtime.status.value is SessionStatus.Starting)

        gate.complete(Unit)
        runCurrent()

        assertEquals(SessionStatus.Idle, h.runtime.status.value)
        assertEquals("user", h.runtime.lastOutcome.value?.stoppedBy)
        assertEquals(listOf(RecorderCommand.Stop(StopCause.USER)), h.recorder().commandsOf<RecorderCommand.Stop>())
    }

    @Test
    fun aStopWhileTheFilesAreCreatedEndsTheSessionAsSoonAsTheyExist() = runTest {
        val h = RuntimeHarness(this)
        val created = CompletableDeferred<Unit>()
        h.factory.createGate = created
        assertEquals(StartResult.Accepted, h.runtime.start(StartRequest("walk", testsEnabled = true)))
        assertTrue(h.serviceStarts(SessionService.ACTION_START))
        runCurrent()

        h.runtime.stop()
        runCurrent()
        assertTrue(h.runtime.status.value is SessionStatus.Starting)

        created.complete(Unit)
        runCurrent()

        assertEquals(SessionStatus.Idle, h.runtime.status.value)
        assertEquals("user", h.runtime.lastOutcome.value?.stoppedBy)
        assertEquals(listOf(RecorderCommand.Stop(StopCause.USER)), h.recorder().commandsOf<RecorderCommand.Stop>())
        assertTrue(h.factory.discarded.isEmpty())

        advanceTimeBy(20_000)
        runCurrent()
        assertEquals("no test runs for a session that stopped at once", 0, h.transport.pings.get())
    }

    @Test
    fun testsRunOnlyWhenTheRequestAskedForThem() = runTest {
        val h = RuntimeHarness(this)
        h.factory.tests = TestSettings(downloadUrl = null)

        val withoutTests = startRecording(h, StartRequest("walk"))
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(0, h.transport.pings.get())
        assertTrue(withoutTests.commandsOf<RecorderCommand.Traffic>().isEmpty())
        h.runtime.stop()
        runCurrent()

        val withTests = startRecording(h, StartRequest("walk again", testsEnabled = true))
        advanceTimeBy(15_000)
        runCurrent()

        assertEquals(1, h.transport.pings.get())
        val traffic = withTests.commandsOf<RecorderCommand.Traffic>().single().record
        assertEquals(TrafficTest.PING, traffic.row.test)
        assertTrue(traffic.row.ok)
    }

    @Test
    fun startWaitsForLaunchRecoveryToFinish() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = RuntimeHarness(this, gate = { gate.await() })

        val result = async { h.runtime.start(StartRequest("walk")) }
        runCurrent()
        assertFalse(result.isCompleted)
        assertTrue(h.platform.started.isEmpty())
        assertEquals(SessionStatus.Idle, h.runtime.status.value)

        gate.complete(Unit)
        assertEquals(StartResult.Accepted, result.await())
        assertEquals(listOf(SessionService.ACTION_START), h.platform.started.toList())
    }

    @Test
    fun launchRecoveryClosesOldSessionsBeforeTheServiceStarts() = runTest {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val open = OpenSession(
            listing = SessionListing("20260909-080000_morning", File("20260909-080000_morning"), null, null, 0),
            heartbeat = HeartbeatRecord(WALL_BASE - 3_600_000, ELAPSED_BASE - 3_600_000, 99),
            lastWriteWallMs = WALL_BASE - 3_600_000,
        )
        val recovery = LaunchRecovery(
            findOpen = {
                events += "recovery found"
                listOf(open)
            },
            exitRecords = { listOf(ExitRecord(pid = 99, timestampWallMs = WALL_BASE - 3_590_000, reason = 3, description = null)) },
            close = { action ->
                events += "recovery closed"
                SessionOutcome(action.dirName, 0, action.stoppedUtcMs, action.stoppedBy, interrupted = true, freshSamples = 0)
            },
            activeDirName = { null },
            dispatcher = StandardTestDispatcher(testScheduler),
            log = {},
            plan = { sessions, _, _ ->
                sessions.map { RecoveryAction.CloseInterrupted(it.listing.dirName, WALL_BASE - 3_600_000, "low_memory", null) }
            },
        )
        val h = RuntimeHarness(this, gate = { recovery.run() })
        h.platform.onStart = { action -> events += "service $action" }

        launch { recovery.run() }
        assertEquals(StartResult.Accepted, h.runtime.start(StartRequest("walk")))

        assertEquals(listOf("recovery found", "recovery closed", "service ${SessionService.ACTION_START}"), events.toList())
        assertEquals(listOf("20260909-080000_morning"), recovery.closed.value.map { it.dirName })
    }

    @Test
    fun theSoakTestTicksForItsDurationAndStopsTheService() = runTest {
        val h = RuntimeHarness(this)

        h.runtime.startSoak(60_000)
        assertEquals(SoakState.Running(0, 60_000), h.runtime.soakState.value)
        assertEquals(listOf(SessionService.ACTION_SOAK), h.platform.started.toList())
        assertTrue(h.serviceStarts(SessionService.ACTION_SOAK))
        runCurrent()

        h.radioInputs.emit(answer(screenOn = false))
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(SoakState.Running(30_000, 60_000), h.runtime.soakState.value)

        advanceTimeBy(30_001)
        runCurrent()
        val done = h.runtime.soakState.value as SoakState.Done
        assertEquals(60L, done.result.secondsElapsed)
        assertEquals(60L, done.result.secondsLogged)
        assertEquals(1, done.result.answers)
        assertEquals(1, done.result.screenOffAnswers)
        assertEquals(1, h.host.stops)
    }

    @Test
    fun aDestroyedSoakServiceReportsThePartThatRan() = runTest {
        val h = RuntimeHarness(this)
        h.runtime.startSoak(60_000)
        assertTrue(h.serviceStarts(SessionService.ACTION_SOAK))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        h.runtime.detachHost(h.host)

        val done = h.runtime.soakState.value as SoakState.Done
        assertEquals(10L, done.result.secondsElapsed)
        assertEquals(10L, done.result.secondsLogged)
    }

    @Test
    fun theSoakTestIsRefusedWhileASessionRuns() = runTest {
        val h = RuntimeHarness(this)
        startRecording(h)

        h.runtime.startSoak(60_000)

        assertEquals(SoakState.Idle, h.runtime.soakState.value)
        assertEquals(listOf(SessionService.ACTION_START), h.platform.started.toList())
        assertTrue(h.platform.logs.contains("The soak test cannot start while a session runs"))
    }

    @Test
    fun aSessionStartCancelsTheSoakTest() = runTest {
        val h = RuntimeHarness(this)
        h.runtime.startSoak(60_000)
        assertTrue(h.serviceStarts(SessionService.ACTION_SOAK))
        runCurrent()

        assertEquals(StartResult.Accepted, h.runtime.start(StartRequest("walk")))

        assertEquals(SoakState.Idle, h.runtime.soakState.value)
        assertEquals(listOf(SessionService.ACTION_SOAK, SessionService.ACTION_START), h.platform.started.toList())
        assertEquals("the service stays for the session", 0, h.host.stops)
    }

    @Test
    fun cancellingTheSoakTestReturnsToIdleAndStopsTheService() = runTest {
        val h = RuntimeHarness(this)
        h.runtime.startSoak(60_000)
        assertTrue(h.serviceStarts(SessionService.ACTION_SOAK))
        runCurrent()

        h.runtime.cancelSoak()
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(SoakState.Idle, h.runtime.soakState.value)
        assertEquals(1, h.host.stops)
    }

    private suspend fun TestScope.startRecording(h: RuntimeHarness, request: StartRequest = StartRequest("walk")): FakeRecorder {
        assertEquals(StartResult.Accepted, h.runtime.start(request))
        assertTrue(h.serviceStarts(SessionService.ACTION_START))
        runCurrent()
        assertTrue("status is ${h.runtime.status.value}", h.runtime.status.value is SessionStatus.Recording)
        return h.recorder()
    }

    private fun serviceState() =
        ServiceStateSnapshot(ServiceRegState.IN_SERVICE, false, "311480", "Verizon", false, WALL_BASE, ELAPSED_BASE)

    private fun answer(screenOn: Boolean) = CellInfoAnswer(
        source = CellInfoSource.REQUEST,
        cells = emptyList(),
        subId = null,
        conditions = DeviceConditions(screenOn = screenOn, charging = false, wifiConnected = false),
        observedWallMs = WALL_BASE,
        observedElapsedMs = ELAPSED_BASE,
    )
}
