package com.fieldtap.core.session

import com.fieldtap.core.input.CellInfoRequestFailed
import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.GnssSnapshot
import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.ListenerReport
import com.fieldtap.core.input.LocationAvailability
import com.fieldtap.core.input.RadioListener
import com.fieldtap.core.input.SignalSnapshot
import com.fieldtap.core.location.LocationStep
import com.fieldtap.core.nettest.TrafficRecord
import com.fieldtap.core.privacy.PrivacyZoneGate
import com.fieldtap.core.radio.ClassifiedAnswer
import com.fieldtap.core.radio.ClassifiedCell
import com.fieldtap.core.radio.RadioStep
import com.fieldtap.core.session.SessionFixtures.DIR_NAME
import com.fieldtap.core.session.SessionFixtures.P1
import com.fieldtap.core.session.SessionFixtures.P2
import com.fieldtap.core.session.SessionFixtures.PID
import com.fieldtap.core.session.SessionFixtures.START_ELAPSED_MS
import com.fieldtap.core.session.SessionFixtures.START_WALL_MS
import com.fieldtap.core.session.SessionFixtures.answer
import com.fieldtap.core.session.SessionFixtures.cellInfoCandidate
import com.fieldtap.core.session.SessionFixtures.cellRow
import com.fieldtap.core.session.SessionFixtures.collection
import com.fieldtap.core.session.SessionFixtures.dataState
import com.fieldtap.core.session.SessionFixtures.displayInfo
import com.fieldtap.core.session.SessionFixtures.event
import com.fieldtap.core.session.SessionFixtures.fix
import com.fieldtap.core.session.SessionFixtures.identity
import com.fieldtap.core.session.SessionFixtures.kpiCandidate
import com.fieldtap.core.session.SessionFixtures.pingRow
import com.fieldtap.core.session.SessionFixtures.serviceState
import com.fieldtap.core.session.SessionFixtures.trackRow
import com.fieldtap.core.time.ManualClock
import com.fieldtap.format.EventKind
import com.fieldtap.format.EventRat
import com.fieldtap.format.FixProvider
import com.fieldtap.format.Rat
import com.fieldtap.format.ServingRat
import com.fieldtap.format.Severity
import com.fieldtap.format.TrafficTest
import java.io.IOException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The recorder against fake pipelines and fake files. The ManualClock is moved in step with the test
 * scheduler's virtual time, so the recorder's own 1 s ticker and the clock it reads agree.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRecorderTest {
    private class Rig {
        val clock = ManualClock(wallMs = START_WALL_MS, elapsedMs = START_ELAPSED_MS)
        val files = FakeSessionFiles()
        val radio = FakeRadioPipeline()
        val location = FakeLocationPipeline()
        var storageStatus = StorageStatus(usedBytes = 0, freeBytes = 10_000_000_000, policy = StoragePolicy())
        var storageChecks = 0

        /** Thrown by the storage measurement when set. */
        var storageFailure: Exception? = null
        val recorder = SessionRecorder(SessionFixtures.config(), clock, files, radio, location) {
            storageChecks++
            storageFailure?.let { throw it }
            storageStatus
        }
    }

    private fun TestScope.start(rig: Rig): Deferred<SessionOutcome> {
        val run = async { rig.recorder.run() }
        runCurrent()
        return run
    }

    private fun TestScope.send(rig: Rig, vararg commands: RecorderCommand) {
        for (command in commands) rig.recorder.submit(command)
        runCurrent()
    }

    private fun TestScope.passSeconds(rig: Rig, seconds: Int) {
        repeat(seconds) {
            rig.clock.advance(1_000)
            advanceTimeBy(1_000)
            runCurrent()
        }
    }

    private suspend fun TestScope.stop(rig: Rig, run: Deferred<SessionOutcome>, cause: StopCause = StopCause.USER): SessionOutcome {
        rig.recorder.submit(RecorderCommand.Stop(cause))
        runCurrent()
        return run.await()
    }

    @Test
    fun createsTheFilesAndWritesTheFirstHeartbeatAtOnce() = runTest {
        val rig = Rig()
        val run = start(rig)

        assertEquals(
            listOf(
                FileCall.Create(SessionMetaFactory.open(identity())),
                FileCall.Heartbeat(HeartbeatRecord(START_WALL_MS, START_ELAPSED_MS, PID)),
            ),
            rig.files.calls,
        )
        val snapshot = rig.recorder.snapshot.value
        assertEquals(DIR_NAME, snapshot.dirName)
        assertEquals(START_WALL_MS, snapshot.startedUtcMs)
        assertFalse(snapshot.stopping)
        assertNull(rig.recorder.outcome.value)

        stop(rig, run)
    }

    @Test
    fun rowsKeepTheirOrderAcrossTheGpsJoinDelay() = runTest {
        val rig = Rig()
        val run = start(rig)
        val measured1 = START_ELAPSED_MS + 300
        val measured2 = START_ELAPSED_MS + 2_300
        val cell1 = cellInfoCandidate(measured1, seenUtcMs = START_WALL_MS + 400, timeEpochMs = START_WALL_MS + 300)
        val kpi1 = kpiCandidate(measured1, timeEpochMs = START_WALL_MS + 300, rsrpDbm = -84)
        val cell2 = cellInfoCandidate(measured2, seenUtcMs = START_WALL_MS + 2_400, timeEpochMs = START_WALL_MS + 2_300)
        val kpi2 = kpiCandidate(measured2, timeEpochMs = START_WALL_MS + 2_300, rsrpDbm = -90)
        val serving = event(START_WALL_MS + 300, EventKind.SERVING_CELL, "Serving cell", EventRat.LTE)
        rig.radio.steps.addLast(RadioStep(listOf(cell1), listOf(kpi1), listOf(serving)))
        rig.radio.steps.addLast(RadioStep(listOf(cell2), listOf(kpi2), emptyList()))

        send(rig, RecorderCommand.Measurement(answer(START_WALL_MS + 400, START_ELAPSED_MS + 400)))
        // The event is written at once; the rows wait for the GPS join.
        assertEquals(listOf(FileCall.Event(serving)), rig.files.rowsAndEvents)

        rig.location.resolved[measured2] = P2
        send(rig, RecorderCommand.Measurement(answer(START_WALL_MS + 2_400, START_ELAPSED_MS + 2_400)))
        // The second answer's rows could be joined already, but they stay behind the first answer's.
        assertEquals(listOf(FileCall.Event(serving)), rig.files.rowsAndEvents)

        rig.location.resolved[measured1] = P1
        passSeconds(rig, 1)

        val written1 = kpi1.copy(row = kpi1.row.copy(position = P1))
        val written2 = kpi2.copy(row = kpi2.row.copy(position = P2))
        assertEquals(
            listOf(
                FileCall.Event(serving),
                FileCall.CellInfo(cell1.row.copy(position = P1)),
                FileCall.Kpi(written1.row),
                FileCall.CellInfo(cell2.row.copy(position = P2)),
                FileCall.Kpi(written2.row),
            ),
            rig.files.rowsAndEvents,
        )
        assertEquals(listOf(written1, written2), rig.radio.kpiWritten)
        assertEquals(listOf(true, true), rig.radio.cellInfoCalls.map { it.second })

        passSeconds(rig, 2)
        assertEquals(listOf(written1, written2), rig.radio.kpiWritten)
        stop(rig, run)
    }

    @Test
    fun serviceDataAndDisplayEventsAreWrittenAtOnce() = runTest {
        val rig = Rig()
        val run = start(rig)
        val lost = event(START_WALL_MS + 100, EventKind.SERVICE_LOST, "Service lost", EventRat.LTE)
        val data = event(START_WALL_MS + 200, EventKind.DATA_STATE, "Mobile data disconnected", severity = Severity.WARN)
        val icon = event(START_WALL_MS + 300, EventKind.NR_DISPLAY, "5G icon off", EventRat.NR)
        rig.radio.serviceEvents = listOf(lost)
        rig.radio.dataEvents = listOf(data)
        rig.radio.displayEvents = listOf(icon)

        send(
            rig,
            RecorderCommand.Measurement(serviceState(START_WALL_MS + 100)),
            RecorderCommand.Measurement(dataState(START_WALL_MS + 200)),
            RecorderCommand.Measurement(displayInfo(START_WALL_MS + 300)),
        )

        assertEquals(listOf(FileCall.Event(lost), FileCall.Event(data), FileCall.Event(icon)), rig.files.rowsAndEvents)
        assertEquals(listOf(true), rig.radio.serviceCalls)
        assertEquals(listOf(true), rig.radio.dataCalls)
        assertEquals(listOf(true), rig.radio.displayCalls)
        assertEquals(3, rig.recorder.snapshot.value.eventsWritten)
        stop(rig, run)
    }

    @Test
    fun displayOnlyInputsWriteNothing() = runTest {
        val rig = Rig()
        val run = start(rig)
        val before = rig.files.calls.toList()

        send(
            rig,
            RecorderCommand.Measurement(
                SignalSnapshot(-97, -10, 12, null, null, null, 3, START_ELAPSED_MS, START_WALL_MS + 100, START_ELAPSED_MS + 100),
            ),
            RecorderCommand.Measurement(GnssSnapshot(12, 8, START_WALL_MS + 200, START_ELAPSED_MS + 200)),
            RecorderCommand.Measurement(
                LocationAvailability(true, true, setOf(FixProvider.GPS), START_WALL_MS + 300, START_ELAPSED_MS + 300),
            ),
            RecorderCommand.Measurement(
                ListenerReport(RadioListener.CELL_INFO_PUSH, ListenerOutcome.MISSING_PERMISSION, null, START_WALL_MS + 400, START_ELAPSED_MS + 400),
            ),
            RecorderCommand.Measurement(CellInfoRequestFailed(1, "timeout", START_WALL_MS + 500, START_ELAPSED_MS + 500)),
        )

        assertEquals(before, rig.files.calls)
        stop(rig, run)
    }

    @Test
    fun whilePausedNothingButPrivacyZoneEventsIsWritten() = runTest {
        val rig = Rig()
        val run = start(rig)
        val inside = fix(elapsedMs = START_ELAPSED_MS + 1_000, wallMs = START_WALL_MS + 1_000)
        val outside = fix(elapsedMs = START_ELAPSED_MS + 9_000, wallMs = START_WALL_MS + 9_000, lat = 38.8990)
        val paused = event(inside.observedWallMs, EventKind.PRIVACY_ZONE, PrivacyZoneGate.PAUSED_TITLE)
        val resumed = event(outside.observedWallMs, EventKind.PRIVACY_ZONE, PrivacyZoneGate.RESUMED_TITLE)
        val restoredInside = event(inside.observedWallMs, EventKind.GPS_RESTORED, "GPS restored")
        val restoredOutside = event(outside.observedWallMs, EventKind.GPS_RESTORED, "GPS restored")
        val outsideTrack = trackRow(outside)
        rig.location.onFixStep = { sample ->
            when (sample) {
                inside -> {
                    rig.location.paused = true
                    rig.location.zonePauses = 1
                    LocationStep(track = null, events = listOf(paused, restoredInside), pauseChanged = true)
                }
                outside -> {
                    rig.location.paused = false
                    LocationStep(track = outsideTrack, events = listOf(resumed, restoredOutside), pauseChanged = true)
                }
                else -> LocationStep(track = trackRow(sample), events = emptyList(), pauseChanged = false)
            }
        }
        send(rig, RecorderCommand.Measurement(inside))

        // Inside the zone the pipelines still hand out rows and events: the recorder must drop every one.
        val measured = START_ELAPSED_MS + 1_700
        rig.radio.steps.addLast(
            RadioStep(
                listOf(cellInfoCandidate(measured, seenUtcMs = START_WALL_MS + 2_000, timeEpochMs = START_WALL_MS + 1_700)),
                listOf(kpiCandidate(measured, timeEpochMs = START_WALL_MS + 1_700)),
                listOf(event(START_WALL_MS + 1_700, EventKind.SERVING_CELL, "Serving cell", EventRat.LTE)),
            ),
        )
        rig.location.resolved[measured] = P1
        rig.radio.serviceEvents = listOf(event(START_WALL_MS + 2_100, EventKind.SERVICE_LOST, "Service lost", EventRat.LTE))
        rig.radio.dataEvents = listOf(event(START_WALL_MS + 2_200, EventKind.DATA_STATE, "Mobile data disconnected", severity = Severity.WARN))
        rig.radio.displayEvents = listOf(event(START_WALL_MS + 2_300, EventKind.NR_DISPLAY, "5G icon off", EventRat.NR))
        rig.location.tickEvents = listOf(event(START_WALL_MS + 3_000, EventKind.GPS_LOST, "GPS lost"))
        val failure = SessionEvents.testFailed(START_WALL_MS + 2_500, TrafficTest.PING, "no cellular network")
        send(
            rig,
            RecorderCommand.Measurement(answer(START_WALL_MS + 2_000, START_ELAPSED_MS + 2_000)),
            RecorderCommand.Measurement(serviceState(START_WALL_MS + 2_100)),
            RecorderCommand.Measurement(dataState(START_WALL_MS + 2_200)),
            RecorderCommand.Measurement(displayInfo(START_WALL_MS + 2_300)),
            RecorderCommand.Traffic(TrafficRecord(pingRow(START_WALL_MS + 2_500), failure)),
            RecorderCommand.Mark("inside the zone", START_WALL_MS + 2_600),
        )
        passSeconds(rig, 5)

        assertEquals(listOf(FileCall.Event(paused)), rig.files.rowsAndEvents)
        assertEquals(listOf(false), rig.radio.cellInfoCalls.map { it.second })
        assertEquals(listOf(false), rig.radio.serviceCalls)
        assertEquals(listOf(false), rig.radio.dataCalls)
        assertEquals(listOf(false), rig.radio.displayCalls)
        assertTrue(rig.radio.kpiWritten.isEmpty())
        assertTrue(rig.recorder.snapshot.value.paused)

        rig.location.tickEvents = emptyList()
        val serviceBack = event(outside.observedWallMs, EventKind.SERVICE_RESTORED, "Service restored", EventRat.LTE)
        rig.radio.resumeEvents = listOf(serviceBack)
        send(rig, RecorderCommand.Measurement(outside))

        assertEquals(
            listOf(
                FileCall.Event(paused),
                FileCall.Event(resumed),
                FileCall.Event(restoredOutside),
                FileCall.Event(serviceBack),
                FileCall.Track(outsideTrack),
            ),
            rig.files.rowsAndEvents,
        )
        assertEquals(listOf(outside.observedWallMs to outside.observedElapsedMs), rig.radio.resumeCalls)
        val snapshot = rig.recorder.snapshot.value
        assertFalse(snapshot.paused)
        assertEquals(1L, snapshot.trackRows)
        stop(rig, run)
    }

    @Test
    fun enteringAZoneWritesOnlyTheRowsMeasuredByTheLastFixOutsideIt() = runTest {
        val rig = Rig()
        val run = start(rig)
        val before = START_ELAPSED_MS + 300
        val after = START_ELAPSED_MS + 700
        val cellBefore = cellInfoCandidate(before, seenUtcMs = START_WALL_MS + 400, timeEpochMs = START_WALL_MS + 300)
        val kpiBefore = kpiCandidate(before, timeEpochMs = START_WALL_MS + 300)
        val cellAfter = cellInfoCandidate(after, seenUtcMs = START_WALL_MS + 800, timeEpochMs = START_WALL_MS + 700)
        val kpiAfter = kpiCandidate(after, timeEpochMs = START_WALL_MS + 700)
        rig.radio.steps.addLast(RadioStep(listOf(cellBefore), listOf(kpiBefore), emptyList()))
        rig.radio.steps.addLast(RadioStep(listOf(cellAfter), listOf(kpiAfter), emptyList()))
        rig.location.lastFixSample = fix(elapsedMs = START_ELAPSED_MS + 500, wallMs = START_WALL_MS + 500)
        send(
            rig,
            RecorderCommand.Measurement(answer(START_WALL_MS + 400, START_ELAPSED_MS + 400)),
            RecorderCommand.Measurement(answer(START_WALL_MS + 800, START_ELAPSED_MS + 800)),
        )
        assertTrue(rig.files.rowsAndEvents.isEmpty())

        val inside = fix(elapsedMs = START_ELAPSED_MS + 1_500, wallMs = START_WALL_MS + 1_500)
        val paused = event(inside.observedWallMs, EventKind.PRIVACY_ZONE, PrivacyZoneGate.PAUSED_TITLE)
        rig.location.joinFinalPosition = { P1 }
        rig.location.onFixStep = {
            rig.location.paused = true
            LocationStep(track = null, events = listOf(paused), pauseChanged = true)
        }
        send(rig, RecorderCommand.Measurement(inside))

        // Measured by the last fix outside the zone: written with its position. Measured after it, between that fix
        // and the one inside the zone: it may have been measured inside, so it is dropped.
        assertEquals(
            listOf(
                FileCall.CellInfo(cellBefore.row.copy(position = P1)),
                FileCall.Kpi(kpiBefore.row.copy(position = P1)),
                FileCall.Event(paused),
            ),
            rig.files.rowsAndEvents,
        )
        assertEquals(listOf(before, before), rig.location.joinFinalCalls)
        assertEquals(listOf(kpiBefore.copy(row = kpiBefore.row.copy(position = P1))), rig.radio.kpiWritten)
        stop(rig, run)
    }

    @Test
    fun aZoneEnteredBeforeAnyFixOutsideWritesNoneOfTheRowsWaitingForAPosition() = runTest {
        val rig = Rig()
        val run = start(rig)
        val measured = START_ELAPSED_MS + 300
        rig.radio.steps.addLast(
            RadioStep(
                listOf(cellInfoCandidate(measured, seenUtcMs = START_WALL_MS + 400, timeEpochMs = START_WALL_MS + 300)),
                listOf(kpiCandidate(measured, timeEpochMs = START_WALL_MS + 300)),
                emptyList(),
            ),
        )
        send(rig, RecorderCommand.Measurement(answer(START_WALL_MS + 400, START_ELAPSED_MS + 400)))
        val inside = fix(elapsedMs = START_ELAPSED_MS + 900, wallMs = START_WALL_MS + 900)
        val paused = event(inside.observedWallMs, EventKind.PRIVACY_ZONE, PrivacyZoneGate.PAUSED_TITLE)
        rig.location.joinFinalPosition = { P1 }
        rig.location.onFixStep = {
            rig.location.paused = true
            LocationStep(track = null, events = listOf(paused), pauseChanged = true)
        }

        send(rig, RecorderCommand.Measurement(inside))

        assertEquals(listOf(FileCall.Event(paused)), rig.files.rowsAndEvents)
        assertTrue(rig.radio.kpiWritten.isEmpty())
        stop(rig, run)
    }

    @Test
    fun aCachedCellListIsNeitherHandledNorWritten() = runTest {
        val rig = Rig()
        val run = start(rig)
        val measured = START_ELAPSED_MS - 5_000
        rig.radio.steps.addLast(
            RadioStep(listOf(cellInfoCandidate(measured, seenUtcMs = START_WALL_MS + 100, timeEpochMs = START_WALL_MS - 5_000)), emptyList(), emptyList()),
        )

        send(rig, RecorderCommand.Measurement(answer(START_WALL_MS + 100, START_ELAPSED_MS + 100).copy(cached = true)))
        passSeconds(rig, 7)

        assertTrue(rig.radio.cellInfoCalls.isEmpty())
        assertTrue(rig.files.rowsAndEvents.isEmpty())
        stop(rig, run)
    }

    @Test
    fun whileHoldingInputsWaitForAFixThatShowsThePhoneOutsideEveryZone() = runTest {
        val rig = Rig()
        val run = start(rig)
        rig.location.holding = true
        val measured = START_ELAPSED_MS + 300
        val cell = cellInfoCandidate(measured, seenUtcMs = START_WALL_MS + 400, timeEpochMs = START_WALL_MS + 300)
        val kpi = kpiCandidate(measured, timeEpochMs = START_WALL_MS + 300)
        val serving = event(START_WALL_MS + 300, EventKind.SERVING_CELL, "Serving cell", EventRat.LTE)
        val lost = event(START_WALL_MS + 450, EventKind.SERVICE_LOST, "Service lost", EventRat.LTE)
        val failure = SessionEvents.testFailed(START_WALL_MS + 500, TrafficTest.PING, "no cellular network")
        val ping = pingRow(START_WALL_MS + 500)
        rig.radio.steps.addLast(RadioStep(listOf(cell), listOf(kpi), listOf(serving)))
        rig.radio.serviceEvents = listOf(lost)
        rig.location.resolved[measured] = P1

        send(
            rig,
            RecorderCommand.Measurement(answer(START_WALL_MS + 400, START_ELAPSED_MS + 400)),
            RecorderCommand.Measurement(serviceState(START_WALL_MS + 450)),
            RecorderCommand.Traffic(TrafficRecord(ping, failure)),
            RecorderCommand.Mark("held back", START_WALL_MS + 600),
        )
        passSeconds(rig, 7)
        // Nothing is handled yet, not even by the pipelines: the next fix decides.
        assertTrue(rig.files.rowsAndEvents.isEmpty())
        assertTrue(rig.radio.cellInfoCalls.isEmpty())
        assertTrue(rig.radio.serviceCalls.isEmpty())

        val outside = fix(elapsedMs = START_ELAPSED_MS + 7_900, wallMs = START_WALL_MS + 7_900)
        val track = trackRow(outside)
        rig.location.onFixStep = { sample ->
            rig.location.holding = false
            rig.location.lastFixSample = sample
            LocationStep(track = track, events = emptyList(), pauseChanged = false, confirmsOutside = true)
        }
        send(rig, RecorderCommand.Measurement(outside))

        assertEquals(
            listOf(
                FileCall.Event(serving),
                FileCall.Event(lost),
                FileCall.Traffic(ping),
                FileCall.Event(failure),
                FileCall.Event(SessionEvents.marker(START_WALL_MS + 600, "held back")),
                FileCall.Track(track),
                FileCall.CellInfo(cell.row.copy(position = P1)),
                FileCall.Kpi(kpi.row.copy(position = P1)),
            ),
            rig.files.rowsAndEvents,
        )
        assertEquals(listOf(true), rig.radio.cellInfoCalls.map { it.second })
        assertEquals(listOf(true), rig.radio.serviceCalls)
        stop(rig, run)
    }

    @Test
    fun whileHoldingAFixInsideAZoneDropsEverythingHeld() = runTest {
        val rig = Rig()
        val run = start(rig)
        rig.location.holding = true
        val measured = START_ELAPSED_MS + 300
        rig.radio.steps.addLast(
            RadioStep(
                listOf(cellInfoCandidate(measured, seenUtcMs = START_WALL_MS + 400, timeEpochMs = START_WALL_MS + 300)),
                listOf(kpiCandidate(measured, timeEpochMs = START_WALL_MS + 300)),
                listOf(event(START_WALL_MS + 300, EventKind.SERVING_CELL, "Serving cell", EventRat.LTE)),
            ),
        )
        rig.radio.serviceEvents = listOf(event(START_WALL_MS + 450, EventKind.SERVICE_LOST, "Service lost", EventRat.LTE))
        rig.location.resolved[measured] = P1
        send(
            rig,
            RecorderCommand.Measurement(answer(START_WALL_MS + 400, START_ELAPSED_MS + 400)),
            RecorderCommand.Measurement(serviceState(START_WALL_MS + 450)),
            RecorderCommand.Traffic(TrafficRecord(pingRow(START_WALL_MS + 500), null)),
            RecorderCommand.Mark("inside after all", START_WALL_MS + 600),
        )

        val inside = fix(elapsedMs = START_ELAPSED_MS + 900, wallMs = START_WALL_MS + 900)
        val paused = event(inside.observedWallMs, EventKind.PRIVACY_ZONE, PrivacyZoneGate.PAUSED_TITLE)
        rig.location.onFixStep = {
            rig.location.holding = false
            rig.location.paused = true
            LocationStep(track = null, events = listOf(paused), pauseChanged = true)
        }
        send(rig, RecorderCommand.Measurement(inside))
        passSeconds(rig, 7)

        assertEquals(listOf(FileCall.Event(paused)), rig.files.rowsAndEvents)
        // The pipelines learn what was held, so de-duplication and the latest state stay current, but nothing is written.
        assertEquals(listOf(false), rig.radio.cellInfoCalls.map { it.second })
        assertEquals(listOf(false), rig.radio.serviceCalls)
        assertTrue(rig.radio.kpiWritten.isEmpty())
        stop(rig, run)
    }

    @Test
    fun aHoldThatLastsTooLongPausesOnATickAndDropsTheRowsItCannotPlace() = runTest {
        val rig = Rig()
        val run = start(rig)
        val early = START_ELAPSED_MS + 50
        val late = START_ELAPSED_MS + 300
        val earlyRow = cellInfoCandidate(early, seenUtcMs = START_WALL_MS + 400, timeEpochMs = START_WALL_MS + 50)
        rig.radio.steps.addLast(RadioStep(listOf(earlyRow), emptyList(), emptyList()))
        rig.radio.steps.addLast(
            RadioStep(listOf(cellInfoCandidate(late, seenUtcMs = START_WALL_MS + 450, timeEpochMs = START_WALL_MS + 300)), emptyList(), emptyList()),
        )
        send(
            rig,
            RecorderCommand.Measurement(answer(START_WALL_MS + 400, START_ELAPSED_MS + 400)),
            RecorderCommand.Measurement(answer(START_WALL_MS + 450, START_ELAPSED_MS + 450)),
        )
        // A fix near a zone at 100 ms: from now on inputs wait for the next one, which never comes.
        rig.location.lastFixSample = fix(elapsedMs = START_ELAPSED_MS + 100, wallMs = START_WALL_MS + 100)
        rig.location.holding = true
        rig.radio.steps.addLast(RadioStep(listOf(cellInfoCandidate(START_ELAPSED_MS + 600, START_WALL_MS + 700, START_WALL_MS + 600)), emptyList(), emptyList()))
        send(rig, RecorderCommand.Measurement(answer(START_WALL_MS + 700, START_ELAPSED_MS + 700)))

        val pausedWithoutFix = event(START_WALL_MS + 1_000, EventKind.PRIVACY_ZONE, PrivacyZoneGate.PAUSED_NO_FIX_TITLE)
        var fired = false
        rig.location.joinFinalPosition = { P1 }
        rig.location.onTickStep = { _, _ ->
            if (fired) {
                emptyList()
            } else {
                fired = true
                rig.location.holding = false
                rig.location.paused = true
                rig.location.pausedWithoutFix = true
                listOf(pausedWithoutFix)
            }
        }
        passSeconds(rig, 2)

        assertEquals(listOf(FileCall.CellInfo(earlyRow.row.copy(position = P1)), FileCall.Event(pausedWithoutFix)), rig.files.rowsAndEvents)
        assertEquals(listOf(early), rig.location.joinFinalCalls)
        assertEquals(listOf(true, true, false), rig.radio.cellInfoCalls.map { it.second })
        val snapshot = rig.recorder.snapshot.value
        assertTrue(snapshot.paused)
        assertTrue(snapshot.waitingForLocation)
        stop(rig, run)
    }

    @Test
    fun aMarkerKeptBackForALocationFixIsCountedWhenAPauseDropsIt() = runTest {
        val rig = Rig()
        val run = start(rig)
        rig.location.holding = true
        send(rig, RecorderCommand.Mark("waiting for a fix", START_WALL_MS + 300))
        assertTrue(rig.recorder.snapshot.value.holdingInputs)
        assertEquals(0, rig.recorder.snapshot.value.markersDropped)

        val paused = event(START_WALL_MS + 1_000, EventKind.PRIVACY_ZONE, PrivacyZoneGate.PAUSED_NO_FIX_TITLE)
        var fired = false
        rig.location.onTickStep = { _, _ ->
            if (fired) {
                emptyList()
            } else {
                fired = true
                rig.location.holding = false
                rig.location.paused = true
                rig.location.pausedWithoutFix = true
                listOf(paused)
            }
        }
        passSeconds(rig, 1)

        assertEquals(listOf(FileCall.Event(paused)), rig.files.rowsAndEvents)
        val snapshot = rig.recorder.snapshot.value
        assertEquals(1, snapshot.markersDropped)
        assertFalse(snapshot.holdingInputs)
        stop(rig, run)
    }

    @Test
    fun aMarkerStillKeptBackAtStopIsCountedAsDropped() = runTest {
        val rig = Rig()
        val run = start(rig)
        rig.location.holding = true
        send(rig, RecorderCommand.Mark("just before stop", START_WALL_MS + 300))

        stop(rig, run)

        assertTrue(rig.files.rowsAndEvents.isEmpty())
        assertEquals(1, rig.recorder.snapshot.value.markersDropped)
    }

    @Test
    fun gpsEventsWaitWithTheOtherInputsForAFixThatShowsThePhoneOutside() = runTest {
        val rig = Rig()
        val run = start(rig)
        rig.location.holding = true
        val lost = event(START_WALL_MS + 1_000, EventKind.GPS_LOST, "GPS lost")
        var fired = false
        rig.location.onTickStep = { _, _ ->
            if (fired) {
                emptyList()
            } else {
                fired = true
                listOf(lost)
            }
        }
        passSeconds(rig, 2)
        assertTrue(rig.files.rowsAndEvents.isEmpty())

        val outside = fix(elapsedMs = START_ELAPSED_MS + 2_500, wallMs = START_WALL_MS + 2_500)
        val restored = event(outside.observedWallMs, EventKind.GPS_RESTORED, "GPS restored")
        rig.location.onFixStep = { sample ->
            rig.location.holding = false
            rig.location.lastFixSample = sample
            LocationStep(track = trackRow(sample), events = listOf(restored), pauseChanged = false, confirmsOutside = true)
        }
        send(rig, RecorderCommand.Measurement(outside))

        assertEquals(listOf(FileCall.Event(lost), FileCall.Event(restored), FileCall.Track(trackRow(outside))), rig.files.rowsAndEvents)
        stop(rig, run)
    }

    @Test
    fun eachNewDroppedMarkerCountIsNotedBesideTheSessionAndAFailedNoteStopsNothing() = runTest {
        val rig = Rig()
        rig.files.failWhen = { it == FileCall.MarkersDropped(1) }
        val run = start(rig)
        rig.location.holding = true
        send(rig, RecorderCommand.Mark("before the pause", START_WALL_MS + 300))
        assertTrue(notes(rig).isEmpty())

        val paused = event(START_WALL_MS + 1_000, EventKind.PRIVACY_ZONE, PrivacyZoneGate.PAUSED_NO_FIX_TITLE)
        var fired = false
        rig.location.onTickStep = { _, _ ->
            if (fired) {
                emptyList()
            } else {
                fired = true
                rig.location.holding = false
                rig.location.paused = true
                rig.location.pausedWithoutFix = true
                listOf(paused)
            }
        }
        passSeconds(rig, 1)
        assertEquals(1, rig.recorder.snapshot.value.markersDropped)
        assertEquals(listOf(1), notes(rig))
        passSeconds(rig, 1)
        assertEquals("a note that failed is tried again, and the session records on", listOf(1, 1), notes(rig))
        assertNull(rig.recorder.outcome.value)
        rig.files.failWhen = { false }
        passSeconds(rig, 2)
        assertEquals("once written, the count is noted again only when it grows", listOf(1, 1, 1), notes(rig))

        // Resumed, a marker waits for a fix again, and Stop drops it.
        rig.location.paused = false
        rig.location.pausedWithoutFix = false
        rig.location.holding = true
        send(rig, RecorderCommand.Mark("at stop", START_WALL_MS + 5_000))
        val outcome = stop(rig, run)

        assertEquals(2, outcome.markersDropped)
        assertEquals(listOf(1, 1, 1, 2), notes(rig))
        assertEquals("user", outcome.stoppedBy)
    }

    @Test
    fun locationServicesSwitchedOffWriteThePipelinesGpsLostLikeAnyGpsEvent() = runTest {
        val rig = Rig()
        val run = start(rig)
        val off = LocationAvailability(false, true, emptySet(), START_WALL_MS + 1_200, START_ELAPSED_MS + 1_200)
        val lost = event(START_WALL_MS + 1_200, EventKind.GPS_LOST, "GPS lost")
        rig.location.onAvailabilityStep = { if (it.locationEnabled) emptyList() else listOf(lost) }

        send(rig, RecorderCommand.Measurement(off))

        assertEquals(listOf(off), rig.location.availabilityCalls)
        assertEquals(listOf(FileCall.Event(lost)), rig.files.rowsAndEvents)
        assertFalse(rig.recorder.snapshot.value.locationEnabled)

        // While paused it is dropped with every other input.
        rig.location.paused = true
        send(rig, RecorderCommand.Measurement(off.copy(observedWallMs = START_WALL_MS + 2_000, observedElapsedMs = START_ELAPSED_MS + 2_000)))
        assertEquals(listOf(FileCall.Event(lost)), rig.files.rowsAndEvents)
        stop(rig, run)
    }

    @Test
    fun aGpsLostForLocationSwitchedOffWaitsWithTheOtherInputsForAFix() = runTest {
        val rig = Rig()
        val run = start(rig)
        rig.location.holding = true
        val lost = event(START_WALL_MS + 1_000, EventKind.GPS_LOST, "GPS lost")
        rig.location.onAvailabilityStep = { listOf(lost) }
        send(rig, RecorderCommand.Measurement(LocationAvailability(false, true, emptySet(), START_WALL_MS + 1_000, START_ELAPSED_MS + 1_000)))
        assertTrue(rig.files.rowsAndEvents.isEmpty())

        val outside = fix(elapsedMs = START_ELAPSED_MS + 2_500, wallMs = START_WALL_MS + 2_500)
        rig.location.onFixStep = { sample ->
            rig.location.holding = false
            rig.location.lastFixSample = sample
            LocationStep(track = trackRow(sample), events = emptyList(), pauseChanged = false, confirmsOutside = true)
        }
        send(rig, RecorderCommand.Measurement(outside))

        assertEquals(listOf(FileCall.Event(lost), FileCall.Track(trackRow(outside))), rig.files.rowsAndEvents)
        stop(rig, run)
    }

    private fun notes(rig: Rig): List<Int> = rig.files.calls.filterIsInstance<FileCall.MarkersDropped>().map { it.count }

    @Test
    fun theSnapshotSaysWhenLocationIsOffAndWhenTheSessionWaitsForAFix() = runTest {
        val rig = Rig()
        val run = start(rig)
        assertTrue(rig.recorder.snapshot.value.locationEnabled)
        send(rig, RecorderCommand.Measurement(LocationAvailability(false, true, emptySet(), START_WALL_MS + 100, START_ELAPSED_MS + 100)))
        assertFalse(rig.recorder.snapshot.value.locationEnabled)
        assertTrue(rig.files.rowsAndEvents.isEmpty())

        rig.location.holding = true
        rig.location.holdAge = 5_000
        passSeconds(rig, 1)
        assertFalse("a hold of a few seconds is normal near a zone", rig.recorder.snapshot.value.waitingForLocation)
        rig.location.holdAge = 5_001
        passSeconds(rig, 1)
        assertTrue(rig.recorder.snapshot.value.waitingForLocation)

        send(rig, RecorderCommand.Measurement(LocationAvailability(true, true, setOf(FixProvider.GPS), START_WALL_MS + 2_100, START_ELAPSED_MS + 2_100)))
        assertTrue(rig.recorder.snapshot.value.locationEnabled)
        stop(rig, run)
    }

    @Test
    fun periodicWritesFollowTheSchedule() = runTest {
        val rig = Rig()
        rig.radio.plmnCounts = mapOf("311480" to 54)
        rig.radio.collectionMeta = collection(fresh = 54, repeats = 66)
        rig.radio.cellRows = listOf(cellRow(samples = 54))
        rig.location.zonePauses = 2
        val run = start(rig)
        val setupCalls = rig.files.calls.size

        passSeconds(rig, 5)
        assertEquals(
            listOf(
                FileCall.Flush,
                FileCall.Flush,
                FileCall.Flush,
                FileCall.Flush,
                FileCall.Flush,
                FileCall.Sync,
                FileCall.Heartbeat(HeartbeatRecord(START_WALL_MS + 5_000, START_ELAPSED_MS + 5_000, PID)),
            ),
            rig.files.calls.drop(setupCalls),
        )

        passSeconds(rig, 55)
        val calls = rig.files.calls
        assertEquals(60, calls.count { it == FileCall.Flush })
        assertEquals(12, calls.count { it == FileCall.Sync })
        // One at start, then one every 5 s.
        assertEquals(13, calls.count { it is FileCall.Heartbeat })
        val expectedSnapshot = FileCall.Snapshot(
            SessionMetaFactory.snapshot(identity(), mapOf("311480" to 54), collection(54, 66), 2, null, ExitReasons.RECORDING),
            listOf(cellRow(samples = 54)),
        )
        assertEquals(listOf(expectedSnapshot), calls.filterIsInstance<FileCall.Snapshot>())
        assertEquals(
            listOf(
                FileCall.Flush,
                FileCall.Sync,
                expectedSnapshot,
                FileCall.Heartbeat(HeartbeatRecord(START_WALL_MS + 60_000, START_ELAPSED_MS + 60_000, PID)),
            ),
            calls.takeLast(4),
        )
        assertEquals(1, rig.storageChecks)
        assertEquals((1..60).map { START_ELAPSED_MS + it * 1_000L }, rig.radio.ticks)
        stop(rig, run)
    }

    @Test
    fun stopReleasesPendingRowsAndWritesTheFinalSnapshot() = runTest {
        val rig = Rig()
        val run = start(rig)
        val measured = START_ELAPSED_MS + 300
        val cell = cellInfoCandidate(measured, seenUtcMs = START_WALL_MS + 400, timeEpochMs = START_WALL_MS + 300)
        val kpi = kpiCandidate(measured, timeEpochMs = START_WALL_MS + 300)
        rig.radio.steps.addLast(RadioStep(listOf(cell), listOf(kpi), emptyList()))
        send(rig, RecorderCommand.Measurement(answer(START_WALL_MS + 400, START_ELAPSED_MS + 400)))
        rig.location.joinFinalPosition = { P1 }
        rig.radio.plmnCounts = mapOf("311480" to 1)
        rig.radio.collectionMeta = collection(fresh = 1, repeats = 0)
        rig.radio.cellRows = listOf(cellRow(samples = 1))
        rig.clock.advance(1_500)
        val before = rig.files.calls.size

        val outcome = stop(rig, run)

        val stoppedAt = START_WALL_MS + 1_500
        assertEquals(
            listOf(
                FileCall.CellInfo(cell.row.copy(position = P1)),
                FileCall.Kpi(kpi.row.copy(position = P1)),
                FileCall.Sync,
                FileCall.Snapshot(
                    SessionMetaFactory.snapshot(identity(), mapOf("311480" to 1), collection(1, 0), 0, stoppedAt, "user"),
                    listOf(cellRow(samples = 1)),
                ),
                FileCall.Close,
            ),
            rig.files.calls.drop(before),
        )
        // One final join per pending row: the answer's cellinfo row and its KPI row.
        assertEquals(listOf(measured, measured), rig.location.joinFinalCalls)
        assertEquals(listOf(kpi.copy(row = kpi.row.copy(position = P1))), rig.radio.kpiWritten)
        assertEquals(SessionOutcome(DIR_NAME, START_WALL_MS, stoppedAt, "user", interrupted = false, freshSamples = 1, name = identity().name), outcome)
        assertEquals(outcome, rig.recorder.outcome.value)
        assertTrue(rig.recorder.snapshot.value.stopping)
    }

    @Test
    fun reachingTheStorageLimitStopsWithStorageFull() = runTest {
        val rig = Rig()
        rig.storageStatus = StorageStatus(usedBytes = 2_000_000_000, freeBytes = 1_000_000_000, policy = StoragePolicy())
        val run = start(rig)

        passSeconds(rig, 59)
        assertFalse(run.isCompleted)
        passSeconds(rig, 1)

        val outcome = run.await()
        assertEquals("storage_full", outcome.stoppedBy)
        assertEquals(START_WALL_MS + 60_000, outcome.stoppedUtcMs)
        val finalSnapshot = rig.files.calls.filterIsInstance<FileCall.Snapshot>().last()
        assertEquals("storage_full", finalSnapshot.meta.summary.stoppedBy)
        assertEquals(START_WALL_MS + 60_000, finalSnapshot.meta.stoppedUtcMs)
        assertEquals(FileCall.Close, rig.files.calls.last())
    }

    @Test
    fun commandsAfterStopAreIgnored() = runTest {
        val rig = Rig()
        val run = start(rig)
        rig.recorder.submit(RecorderCommand.Stop(StopCause.USER))
        rig.recorder.submit(RecorderCommand.Mark("queued behind the stop", START_WALL_MS + 100))
        runCurrent()
        run.await()
        val afterStop = rig.files.calls.size

        rig.recorder.submit(RecorderCommand.Mark("after the stop", START_WALL_MS + 200))
        rig.recorder.submit(RecorderCommand.Measurement(answer(START_WALL_MS + 300, START_ELAPSED_MS + 300)))
        rig.recorder.submit(RecorderCommand.Stop(StopCause.STORAGE_FULL))
        passSeconds(rig, 10)

        assertEquals(afterStop, rig.files.calls.size)
        assertEquals(FileCall.Close, rig.files.calls.last())
        assertTrue(rig.files.calls.none { it is FileCall.Event })
        assertTrue(rig.radio.cellInfoCalls.isEmpty())
        assertEquals("user", rig.recorder.outcome.value?.stoppedBy)
    }

    @Test
    fun cancellingTheRunFinalizesWithServiceDestroyed() = runTest {
        val rig = Rig()
        val job = launch { rig.recorder.run() }
        runCurrent()
        rig.clock.advance(2_000)

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        val finalSnapshot = rig.files.calls.filterIsInstance<FileCall.Snapshot>().single()
        assertEquals("service_destroyed", finalSnapshot.meta.summary.stoppedBy)
        assertEquals(START_WALL_MS + 2_000, finalSnapshot.meta.stoppedUtcMs)
        assertEquals(FileCall.Close, rig.files.calls.last())
        assertEquals("service_destroyed", rig.recorder.outcome.value?.stoppedBy)
    }

    @Test
    fun aWriteFailureStopsWithStorageFull() = runTest {
        val rig = Rig()
        val run = start(rig)
        rig.files.failWhen = { it is FileCall.Event }
        val serving = event(START_WALL_MS + 300, EventKind.SERVING_CELL, "Serving cell", EventRat.LTE)
        rig.radio.steps.addLast(RadioStep(emptyList(), emptyList(), listOf(serving)))

        send(rig, RecorderCommand.Measurement(answer(START_WALL_MS + 400, START_ELAPSED_MS + 400)))

        val outcome = run.await()
        assertEquals("storage_full", outcome.stoppedBy)
        assertEquals("storage_full", rig.files.calls.filterIsInstance<FileCall.Snapshot>().single().meta.summary.stoppedBy)
        assertEquals(FileCall.Close, rig.files.calls.last())
    }

    @Test
    fun whenTheFinalSnapshotFailsAHeartbeatIsLeftForRecovery() = runTest {
        val rig = Rig()
        val run = start(rig)
        rig.files.failWhen = { it is FileCall.Snapshot }
        rig.clock.advance(3_000)

        val outcome = stop(rig, run)

        assertEquals("user", outcome.stoppedBy)
        val tail = rig.files.calls.takeLast(3)
        assertTrue(tail[0] is FileCall.Snapshot)
        assertEquals(FileCall.Close, tail[1])
        // The stop token goes with the time, so recovery closes the session as the user's stop, whatever later ends the process.
        assertEquals(FileCall.Heartbeat(HeartbeatRecord(START_WALL_MS + 3_000, START_ELAPSED_MS + 3_000, PID, stoppedBy = "user")), tail[2])
    }

    @Test
    fun theSnapshotShowsTheServingCellItsAgeAndTheCounts() = runTest {
        val rig = Rig()
        val run = start(rig)
        val observed = answer(START_WALL_MS + 400, START_ELAPSED_MS + 400)
        val cell = CellSnapshot(rat = Rat.LTE, registered = true, connectionStatus = 1, timestampMs = START_ELAPSED_MS + 100, rsrp = -97)
        val primary = ClassifiedCell(cell, stale = false, ageMs = 300, measurementWallMs = START_WALL_MS + 100)
        rig.radio.latestAnswer = ClassifiedAnswer(observed, listOf(primary), primary, nsaSecondary = null, fresh = true, repeat = false)
        rig.radio.steps.addLast(
            RadioStep(emptyList(), emptyList(), listOf(event(START_WALL_MS + 100, EventKind.SERVING_CELL, "Serving cell", EventRat.LTE))),
        )
        rig.radio.collectionMeta = collection(fresh = 5, repeats = 3)
        rig.location.lastFixSample = fix(elapsedMs = START_ELAPSED_MS + 900, wallMs = START_WALL_MS + 900)

        send(rig, RecorderCommand.Measurement(observed))
        passSeconds(rig, 2)

        assertEquals(
            RecorderSnapshot(
                dirName = DIR_NAME,
                startedUtcMs = START_WALL_MS,
                elapsedMs = 2_000,
                servingRat = ServingRat.LTE,
                servingRsrpDbm = -97,
                newestSampleAgeMs = 1_900,
                paused = false,
                freshSamples = 5,
                repeatsDropped = 3,
                eventsWritten = 1,
                trackRows = 0,
                hasRecentFix = true,
                stopping = false,
            ),
            rig.recorder.snapshot.value,
        )

        passSeconds(rig, 5)
        val later = rig.recorder.snapshot.value
        // 7 000 ms after the start the fix from 900 ms is 6.1 s old.
        assertFalse(later.hasRecentFix)
        assertEquals(6_900L, later.newestSampleAgeMs)
        stop(rig, run)
    }

    @Test
    fun marksAndTestResultsAreWrittenWhenNotPaused() = runTest {
        val rig = Rig()
        val run = start(rig)
        val failure = SessionEvents.testFailed(START_WALL_MS + 3_000, TrafficTest.PING, "no cellular network")
        val row = pingRow(START_WALL_MS + 3_000)

        send(
            rig,
            RecorderCommand.Traffic(TrafficRecord(row, failure)),
            RecorderCommand.Mark("  North entrance  ", START_WALL_MS + 4_000),
        )

        assertEquals(
            listOf(
                FileCall.Traffic(row),
                FileCall.Event(failure),
                FileCall.Event(SessionEvents.marker(START_WALL_MS + 4_000, "North entrance")),
            ),
            rig.files.rowsAndEvents,
        )
        stop(rig, run)
    }

    @Test
    fun ticksFeedBothPipelinesAndWriteGpsEvents() = runTest {
        val rig = Rig()
        val run = start(rig)
        val gpsLost = event(START_WALL_MS + 1_000, EventKind.GPS_LOST, "GPS lost")
        rig.location.tickEvents = listOf(gpsLost)

        passSeconds(rig, 1)
        rig.location.tickEvents = emptyList()
        passSeconds(rig, 1)

        assertEquals(listOf(START_ELAPSED_MS + 1_000, START_ELAPSED_MS + 2_000), rig.radio.ticks)
        assertEquals(
            listOf(START_WALL_MS + 1_000 to START_ELAPSED_MS + 1_000, START_WALL_MS + 2_000 to START_ELAPSED_MS + 2_000),
            rig.location.tickCalls,
        )
        assertEquals(listOf(FileCall.Event(gpsLost)), rig.files.rowsAndEvents)
        stop(rig, run)
    }

    @Test
    fun aFailedCreatePropagatesAndRecordsNothing() = runTest {
        val rig = Rig()
        rig.files.failWhen = { it is FileCall.Create }

        try {
            rig.recorder.run()
            fail("run() must rethrow the failure to create the files")
        } catch (e: IOException) {
            // Expected: nothing was recorded.
        }
        rig.recorder.submit(RecorderCommand.Mark("ignored", START_WALL_MS))
        runCurrent()

        assertEquals(listOf(FileCall.Create(SessionMetaFactory.open(identity())), FileCall.Close), rig.files.calls)
        assertNull(rig.recorder.outcome.value)
    }

    @Test
    fun aPipelineBugClosesTheSessionAsACrashAndPropagates() = runTest {
        val rig = Rig()
        rig.radio.failure = IllegalStateException("radio pipeline bug")

        supervisorScope {
            val run = async { rig.recorder.run() }
            this@runTest.runCurrent()
            rig.recorder.submit(RecorderCommand.Measurement(answer(START_WALL_MS + 400, START_ELAPSED_MS + 400)))
            this@runTest.runCurrent()
            try {
                run.await()
                fail("run() must rethrow the pipeline failure")
            } catch (e: IllegalStateException) {
                assertEquals("radio pipeline bug", e.message)
            }
        }

        assertEquals(ExitReasons.CRASH, rig.recorder.outcome.value?.stoppedBy)
        assertEquals(ExitReasons.CRASH, rig.files.calls.filterIsInstance<FileCall.Snapshot>().single().meta.summary.stoppedBy)
        assertEquals(FileCall.Close, rig.files.calls.last())
    }

    @Test
    fun aFixOutsideEveryZoneWritesItsEventsAndItsTrackRow() = runTest {
        val rig = Rig()
        val run = start(rig)
        val sample = fix(elapsedMs = START_ELAPSED_MS + 700, wallMs = START_WALL_MS + 700)
        val restored = event(sample.observedWallMs, EventKind.GPS_RESTORED, "GPS restored")
        val track = trackRow(sample)
        rig.location.onFixStep = { LocationStep(track = track, events = listOf(restored), pauseChanged = false) }

        send(rig, RecorderCommand.Measurement(sample))

        assertEquals(listOf(FileCall.Event(restored), FileCall.Track(track)), rig.files.rowsAndEvents)
        assertEquals(listOf(sample), rig.location.fixes)
        assertTrue(rig.radio.resumeCalls.isEmpty())
        assertEquals(1L, rig.recorder.snapshot.value.trackRows)
        assertEquals(1, rig.recorder.snapshot.value.eventsWritten)
        stop(rig, run)
    }

    @Test
    fun aStorageMeasurementThatFailsDoesNotStopTheSession() = runTest {
        val rig = Rig()
        rig.storageFailure = IOException("statfs failed")
        val run = start(rig)

        passSeconds(rig, 60)
        assertFalse(run.isCompleted)
        rig.storageFailure = SecurityException("external storage no longer accessible")
        passSeconds(rig, 60)

        assertFalse(run.isCompleted)
        assertEquals(2, rig.storageChecks)
        assertEquals("user", stop(rig, run).stoppedBy)
    }

    @Test
    fun aFlushThatFailsStopsWithStorageFullAndStillClosesTheFiles() = runTest {
        val rig = Rig()
        val run = start(rig)
        rig.files.failWhen = { it == FileCall.Flush }

        passSeconds(rig, 1)

        val outcome = run.await()
        assertEquals("storage_full", outcome.stoppedBy)
        assertEquals(START_WALL_MS + 1_000, outcome.stoppedUtcMs)
        val tail = rig.files.calls.takeLast(4)
        assertEquals(FileCall.Flush, tail[0])
        assertEquals(FileCall.Sync, tail[1])
        assertEquals("storage_full", (tail[2] as FileCall.Snapshot).meta.summary.stoppedBy)
        assertEquals(FileCall.Close, tail[3])
    }

    @Test
    fun aRowThatCannotBeWrittenAtStopDoesNotKeepTheSessionOpen() = runTest {
        val rig = Rig()
        val run = start(rig)
        val measured = START_ELAPSED_MS + 300
        rig.radio.steps.addLast(RadioStep(emptyList(), listOf(kpiCandidate(measured, timeEpochMs = START_WALL_MS + 300)), emptyList()))
        send(rig, RecorderCommand.Measurement(answer(START_WALL_MS + 400, START_ELAPSED_MS + 400)))
        rig.files.failWhen = { it is FileCall.Kpi }

        val outcome = stop(rig, run)

        assertEquals("user", outcome.stoppedBy)
        assertTrue(rig.radio.kpiWritten.isEmpty())
        val tail = rig.files.calls.takeLast(4)
        assertTrue(tail[0] is FileCall.Kpi)
        assertEquals(FileCall.Sync, tail[1])
        assertEquals("user", (tail[2] as FileCall.Snapshot).meta.summary.stoppedBy)
        assertEquals(FileCall.Close, tail[3])
    }

    @Test
    fun aWallClockSetBackNeverStopsBeforeTheStart() = runTest {
        val rig = Rig()
        val run = start(rig)
        rig.clock.wallMs = START_WALL_MS - 3_600_000

        val outcome = stop(rig, run)

        assertEquals(START_WALL_MS, outcome.stoppedUtcMs)
        assertEquals(START_WALL_MS, rig.files.calls.filterIsInstance<FileCall.Snapshot>().single().meta.stoppedUtcMs)
    }

    @Test
    fun aWallClockSetBackMidSessionKeepsTheTrackInOrderAndNeverStopsBeforeARow() = runTest {
        val rig = Rig()
        val run = start(rig)
        rig.location.onFixStep = { sample -> LocationStep(track = trackRow(sample), events = emptyList(), pauseChanged = false) }
        val before = fix(elapsedMs = START_ELAPSED_MS + 1_000, wallMs = START_WALL_MS + 61_000)
        // Android corrects the clock back by a minute between the two fixes.
        val after = fix(elapsedMs = START_ELAPSED_MS + 2_000, wallMs = START_WALL_MS + 2_000)
        send(rig, RecorderCommand.Measurement(before), RecorderCommand.Measurement(after))
        rig.clock.wallMs = START_WALL_MS + 3_000

        val outcome = stop(rig, run)

        assertEquals(listOf(START_WALL_MS + 61_000, START_WALL_MS + 61_000), rig.files.calls.filterIsInstance<FileCall.Track>().map { it.row.timeUtcMs })
        assertEquals(START_WALL_MS + 61_000, outcome.stoppedUtcMs)
        assertEquals(START_WALL_MS + 61_000, rig.files.calls.filterIsInstance<FileCall.Snapshot>().last().meta.stoppedUtcMs)
    }

    @Test
    fun aZonePauseIsWrittenToSessionJsonAtOnce() = runTest {
        val rig = Rig()
        val run = start(rig)
        val inside = fix(elapsedMs = START_ELAPSED_MS + 500, wallMs = START_WALL_MS + 500)
        val paused = event(inside.observedWallMs, EventKind.PRIVACY_ZONE, PrivacyZoneGate.PAUSED_TITLE)
        rig.location.onFixStep = {
            rig.location.paused = true
            rig.location.zonePauses = 1
            LocationStep(track = null, events = listOf(paused), pauseChanged = true)
        }

        send(rig, RecorderCommand.Measurement(inside))

        val snapshot = rig.files.calls.filterIsInstance<FileCall.Snapshot>().single()
        assertEquals(1, snapshot.meta.privacy.zonePauses)
        assertNull(snapshot.meta.stoppedUtcMs)
        stop(rig, run)
    }

    @Test
    fun runMayBeCalledOnlyOnce() = runTest {
        val rig = Rig()
        val run = start(rig)

        try {
            rig.recorder.run()
            fail("a second run() must be refused")
        } catch (e: IllegalStateException) {
            // Expected: the first run is still recording.
        }

        assertEquals(1, rig.files.calls.count { it is FileCall.Create })
        assertEquals("user", stop(rig, run).stoppedBy)
    }
}
