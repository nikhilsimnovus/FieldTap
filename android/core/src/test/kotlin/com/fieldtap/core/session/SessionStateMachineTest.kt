package com.fieldtap.core.session

import com.fieldtap.core.privacy.Consent
import com.fieldtap.core.privacy.ConsentRecord
import com.fieldtap.core.session.SessionFixtures.DIR_NAME
import com.fieldtap.core.session.SessionFixtures.START_WALL_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {
    private val request = StartRequest(name = "Mall walk (north path)", note = "north path")
    private val roomy = StorageStatus(usedBytes = 0, freeBytes = 1_000_000_000, policy = StoragePolicy())
    private val previous = SessionOutcome("20260909-100000_Earlier", START_WALL_MS - 90_000_000, START_WALL_MS - 89_000_000, "user", false, 10)
    private val outcome = SessionOutcome(DIR_NAME, START_WALL_MS, START_WALL_MS + 120_000, "user", interrupted = false, freshSamples = 54)

    private fun currentConsent(): ConsentRecord =
        ConsentRecord(Consent.CURRENT.version, Consent.CURRENT.sha256, grantedUtcMs = START_WALL_MS - 86_400_000)

    private fun ready(): StartPreconditions = StartPreconditions(
        consent = currentConsent(),
        preciseLocationGranted = true,
        locationEnabled = true,
        storage = roomy,
    )

    @Test
    fun aStartWithEverythingInPlaceBeginsRecording() {
        val transition = SessionStateMachine.reduce(SessionState(lastOutcome = previous), SessionCommand.Start(request, ready()))

        assertEquals(SessionState(phase = SessionPhase.STARTING, request = request, lastOutcome = previous), transition.state)
        assertEquals(listOf(SessionEffect.BeginRecording(request)), transition.effects)
    }

    @Test
    fun refusalsAreCheckedInOrder() {
        var startRequest = StartRequest(name = " \t ")
        var preconditions = StartPreconditions(
            consent = null,
            preciseLocationGranted = false,
            locationEnabled = false,
            storage = StorageStatus(usedBytes = 2_000_000_000, freeBytes = 0, policy = StoragePolicy()),
        )
        val steps = listOf<Pair<StartRefusal, () -> Unit>>(
            StartRefusal.BLANK_NAME to { startRequest = request },
            StartRefusal.NO_CONSENT to { preconditions = preconditions.copy(consent = currentConsent()) },
            StartRefusal.NO_PRECISE_LOCATION to { preconditions = preconditions.copy(preciseLocationGranted = true) },
            StartRefusal.LOCATION_OFF to { preconditions = preconditions.copy(locationEnabled = true) },
            StartRefusal.STORAGE_FULL to { preconditions = preconditions.copy(storage = roomy) },
        )

        for ((expected, remedy) in steps) {
            val transition = SessionStateMachine.reduce(SessionState(), SessionCommand.Start(startRequest, preconditions))
            assertEquals(SessionState(lastRefusal = expected), transition.state)
            assertEquals(listOf(SessionEffect.Refuse(expected)), transition.effects)
            assertEquals(expected, SessionStateMachine.refusal(startRequest, preconditions))
            remedy()
        }

        // Every remedy applied: nothing else refuses. Readiness is not a precondition at all (decision 7).
        assertNull(SessionStateMachine.refusal(startRequest, preconditions))
        assertEquals(
            listOf(SessionEffect.BeginRecording(request)),
            SessionStateMachine.reduce(SessionState(), SessionCommand.Start(startRequest, preconditions)).effects,
        )
    }

    @Test
    fun consentToAnOlderTextIsNoConsent() {
        val older = ConsentRecord(
            version = "2026-09-01",
            sha256 = "85902057108ac48854a0c09cc66e7626789d3b7fb9f0c95c8ab6205520f5030f",
            grantedUtcMs = START_WALL_MS,
        )

        assertEquals(StartRefusal.NO_CONSENT, SessionStateMachine.refusal(request, ready().copy(consent = older)))
        assertEquals(StartRefusal.NO_CONSENT, SessionStateMachine.refusal(request, ready().copy(consent = null)))
    }

    @Test
    fun tooLittleFreeSpaceIsStorageFull() {
        val tight = StorageStatus(usedBytes = 0, freeBytes = 199_999_999, policy = StoragePolicy())

        assertEquals(StartRefusal.STORAGE_FULL, SessionStateMachine.refusal(request, ready().copy(storage = tight)))
    }

    @Test
    fun aStartWhileASessionExistsIsRefused() {
        for (phase in listOf(SessionPhase.STARTING, SessionPhase.RECORDING, SessionPhase.STOPPING)) {
            val state = SessionState(phase = phase, request = request, dirName = DIR_NAME, startedUtcMs = START_WALL_MS)

            val transition = SessionStateMachine.reduce(state, SessionCommand.Start(request, ready()))

            assertEquals(phase.name, state.copy(lastRefusal = StartRefusal.SESSION_RUNNING), transition.state)
            assertEquals(phase.name, listOf(SessionEffect.Refuse(StartRefusal.SESSION_RUNNING)), transition.effects)
        }
    }

    @Test
    fun transitionTable() {
        val idle = SessionState(lastOutcome = previous)
        val starting = SessionState(phase = SessionPhase.STARTING, request = request, lastOutcome = previous)
        val startingWithStop = starting.copy(stopCause = StopCause.USER)
        val recording = starting.copy(phase = SessionPhase.RECORDING, dirName = DIR_NAME, startedUtcMs = START_WALL_MS)
        val stopping = recording.copy(phase = SessionPhase.STOPPING, stopCause = StopCause.USER)
        val published = SessionState(lastOutcome = outcome)

        val started = SessionCommand.Started(DIR_NAME, START_WALL_MS)
        val startFailed = SessionCommand.StartFailed("no space left")
        val stopUser = SessionCommand.Stop(StopCause.USER)
        val stopStorage = SessionCommand.Stop(StopCause.STORAGE_FULL)
        val finished = SessionCommand.Finished(outcome)

        data class Row(
            val name: String,
            val state: SessionState,
            val command: SessionCommand,
            val expected: SessionState,
            val effects: List<SessionEffect>,
        )

        val table = listOf(
            Row("idle + started", idle, started, idle, emptyList()),
            Row("idle + start failed", idle, startFailed, idle, emptyList()),
            Row("idle + stop", idle, stopUser, idle, emptyList()),
            Row("idle + finished", idle, finished, idle, emptyList()),
            Row("starting + started", starting, started, recording, emptyList()),
            Row("starting + start failed", starting, startFailed, idle, emptyList()),
            Row("starting + stop is remembered", starting, stopUser, startingWithStop, emptyList()),
            Row("starting + second stop keeps the first cause", startingWithStop, stopStorage, startingWithStop, emptyList()),
            Row(
                "starting with a stop + started",
                startingWithStop,
                started,
                stopping,
                listOf(SessionEffect.EndRecording(StopCause.USER)),
            ),
            Row("starting with a stop + start failed", startingWithStop, startFailed, idle, emptyList()),
            Row("starting + finished", starting, finished, published, listOf(SessionEffect.Publish(outcome))),
            Row("recording + started", recording, started, recording, emptyList()),
            Row("recording + start failed", recording, startFailed, recording, emptyList()),
            Row(
                "recording + stop",
                recording,
                stopStorage,
                recording.copy(phase = SessionPhase.STOPPING, stopCause = StopCause.STORAGE_FULL),
                listOf(SessionEffect.EndRecording(StopCause.STORAGE_FULL)),
            ),
            Row("recording + finished", recording, finished, published, listOf(SessionEffect.Publish(outcome))),
            Row("stopping + started", stopping, started, stopping, emptyList()),
            Row("stopping + start failed", stopping, startFailed, stopping, emptyList()),
            Row("stopping + second stop", stopping, stopStorage, stopping, emptyList()),
            Row("stopping + finished", stopping, finished, published, listOf(SessionEffect.Publish(outcome))),
        )

        for (row in table) {
            val transition = SessionStateMachine.reduce(row.state, row.command)
            assertEquals(row.name, row.expected, transition.state)
            assertEquals(row.name, row.effects, transition.effects)
        }
    }
}
