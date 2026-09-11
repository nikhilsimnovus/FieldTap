package com.fieldtap.core.session

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.input.FixSample
import com.fieldtap.core.input.MeasurementInput
import com.fieldtap.core.location.DefaultLocationPipeline
import com.fieldtap.core.privacy.PrivacyZone
import com.fieldtap.core.privacy.PrivacyZoneGate
import com.fieldtap.core.radio.DefaultRadioPipeline
import com.fieldtap.core.session.SessionFixtures.START_ELAPSED_MS
import com.fieldtap.core.session.SessionFixtures.START_WALL_MS
import com.fieldtap.core.time.ManualClock
import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.EventKind
import com.fieldtap.format.FixProvider
import com.fieldtap.format.Rat
import com.fieldtap.format.SessionMeta
import kotlin.math.PI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder with the production radio and location pipelines and a 100 m privacy zone, on virtual time: what
 * reaches the files around a zone, including the cases that turn on when Android delivers what it measured. The
 * modem refreshes the serving cell every 2 s and the app requests every 1 s, as on a phone with its screen on.
 * Times are milliseconds after the session start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyZoneRecordingTest {
    private val zone = PrivacyZone(id = "zone", label = "Home", lat = ZONE_LAT, lon = ZONE_LON, radiusM = 100.0)

    private class Timed(val atMs: Long, val input: MeasurementInput)

    private class Recorded(val calls: List<FileCall>, val lastSnapshot: RecorderSnapshot, val finalMeta: SessionMeta) {
        val kpiTimes: List<Long> get() = calls.filterIsInstance<FileCall.Kpi>().map { it.row.timeEpochMs - START_WALL_MS }
        val cellInfoTimes: List<Long> get() = calls.filterIsInstance<FileCall.CellInfo>().map { it.row.timeEpochMs - START_WALL_MS }
        val events get() = calls.filterIsInstance<FileCall.Event>().map { it.row }
    }

    @Test
    fun aMeasurementTakenInsideAZoneIsNeverWrittenAfterLoggingResumes() {
        val inputs = fixes(1_000..46_000) { atMs ->
            when {
                atMs < 11_000 -> FAR_NORTH
                atMs < 31_000 -> CENTRE
                else -> FAR_WEST
            }
        } + answers(900..46_000) { measuredMs -> if (measuredMs < 11_000) BEFORE else if (measuredMs < 31_000) INSIDE else AFTER } +
            // Measured at 30.8 s, still inside, and delivered at 31.2 s, after the fix that resumed logging.
            Timed(31_200, answer(atMs = 31_200, measuredMs = 30_800, INSIDE))

        val recorded = record(listOf(zone), inputs, endMs = 46_000)

        val zoneEvents = recorded.events.filter { it.kind == EventKind.PRIVACY_ZONE }
        assertEquals(listOf(PrivacyZoneGate.PAUSED_TITLE, PrivacyZoneGate.RESUMED_TITLE), zoneEvents.map { it.title })
        assertEquals(listOf(11_000L, 31_000L), zoneEvents.map { it.timeUtcMs - START_WALL_MS })
        // Nothing measured after the last fix before the zone and before the fix that resumed logging, stale or fresh.
        assertTrue(recorded.kpiTimes.toString(), recorded.kpiTimes.none { it in 10_001 until 31_000 })
        assertTrue(recorded.cellInfoTimes.toString(), recorded.cellInfoTimes.none { it in 10_001 until 31_000 })
        assertTrue(recorded.calls.filterIsInstance<FileCall.CellInfo>().none { it.row.rsrp == INSIDE.rsrp })
        assertTrue(recorded.events.none { it.kind == EventKind.SERVING_CELL && it.timeUtcMs - START_WALL_MS in 10_001 until 31_000 })
        // After the zone the new cell is written, from its first measurement taken outside.
        assertEquals(32_400L, recorded.kpiTimes.first { it >= 31_000 })
        val changed = recorded.events.single { it.title == "Serving cell changed" }
        assertEquals(32_400L, changed.timeUtcMs - START_WALL_MS)
    }

    @Test
    fun aSessionStartedInsideAZoneWritesOnlyThatLoggingPaused() {
        // Indoors at home: the first fix takes 20 s.
        val inputs = answers(900..30_000) { INSIDE } + fixes(20_000..30_000) { CENTRE }

        val recorded = record(listOf(zone), inputs, endMs = 30_000)

        assertEquals(listOf(FileCall.Event(recorded.events.single())), recorded.calls)
        assertEquals(PrivacyZoneGate.PAUSED_TITLE, recorded.events.single().title)
        assertEquals(20_000L, recorded.events.single().timeUtcMs - START_WALL_MS)
        assertEquals(1, recorded.finalMeta.privacy.zonePauses)
        assertTrue(recorded.finalMeta.summary.plmns.isEmpty())
    }

    @Test
    fun aSessionWithZonesThatStartsOutsideThemLosesNothingWhileItWaitsForItsFirstFix() {
        val inputs = answers(900..20_000) { BEFORE } + fixes(3_000..20_000) { FAR_NORTH }

        val withZones = record(listOf(zone), inputs, endMs = 20_000)
        val withoutZones = record(emptyList(), inputs, endMs = 20_000)

        assertEquals(withoutZones.calls, withZones.calls)
        assertEquals(withoutZones.finalMeta.collection, withZones.finalMeta.collection)
        assertEquals(10, withZones.kpiTimes.size)
    }

    @Test
    fun gpsLostNearAZonePausesAfterAMinuteAndWritesNothingItCouldNotPlace() {
        // 300 m from the zone's centre, 200 m from its edge; the fixes stop at 10 s, as they do inside a building.
        val inputs = fixes(1_000..10_000) { NEAR } + answers(900..90_000) { BEFORE }

        val recorded = record(listOf(zone), inputs, endMs = 90_000)

        assertTrue(recorded.kpiTimes.toString(), recorded.kpiTimes.all { it <= 10_000 })
        assertTrue(recorded.cellInfoTimes.all { it <= 10_000 })
        assertEquals(listOf(EventKind.SERVING_CELL, EventKind.GPS_LOST, EventKind.PRIVACY_ZONE), recorded.events.map { it.kind })
        val paused = recorded.events.last()
        assertEquals(PrivacyZoneGate.PAUSED_NO_FIX_TITLE, paused.title)
        assertEquals(71_000L, paused.timeUtcMs - START_WALL_MS)
        assertTrue(recorded.lastSnapshot.paused)
        assertTrue(recorded.lastSnapshot.waitingForLocation)
        assertEquals("no fix showed the phone inside the zone", 0, recorded.finalMeta.privacy.zonePauses)
    }

    @Test
    fun aCoarseFixWhoseAccuracyReachesIntoTheZoneNeitherResumesLoggingNorPlacesATrackRow() {
        val coarse = fixes(10_000..20_000) { COARSE_NEAR_CENTRE }.map { timed ->
            val fix = timed.input as FixSample
            Timed(timed.atMs, fix.copy(provider = FixProvider.NETWORK, accuracyM = 150.0))
        }
        val inputs = fixes(1_000..5_000) { FAR_NORTH } + fixes(6_000..6_000) { CENTRE } + coarse + answers(900..20_000) { INSIDE }

        val recorded = record(listOf(zone), inputs, endMs = 20_000)

        assertEquals(listOf(PrivacyZoneGate.PAUSED_TITLE), recorded.events.filter { it.kind == EventKind.PRIVACY_ZONE }.map { it.title })
        assertTrue(recorded.calls.filterIsInstance<FileCall.Track>().all { it.row.timeUtcMs - START_WALL_MS <= 5_000 })
        assertTrue(recorded.lastSnapshot.paused)
        assertFalse(recorded.lastSnapshot.waitingForLocation)
    }

    /** Records from 0 to [endMs], feeding [inputs] at their times, then stops. */
    private fun record(zones: List<PrivacyZone>, inputs: List<Timed>, endMs: Long): Recorded {
        lateinit var recorded: Recorded
        runTest {
            val clock = ManualClock(wallMs = START_WALL_MS, elapsedMs = START_ELAPSED_MS)
            val files = FakeSessionFiles()
            val recorder = SessionRecorder(
                SessionFixtures.config(),
                clock,
                files,
                DefaultRadioPipeline(sessionStartElapsedMs = START_ELAPSED_MS),
                DefaultLocationPipeline(zones, allowMockFixes = false),
            ) { StorageStatus(usedBytes = 0, freeBytes = 10_000_000_000, policy = StoragePolicy()) }
            val run = async { recorder.run() }
            runCurrent()
            val timeline = inputs.sortedBy { it.atMs }
            var next = 0
            var nowMs = 0L
            while (nowMs < endMs) {
                clock.advance(100)
                advanceTimeBy(100)
                nowMs += 100
                while (next < timeline.size && timeline[next].atMs <= nowMs) {
                    recorder.submit(RecorderCommand.Measurement(timeline[next].input))
                    next++
                }
                runCurrent()
            }
            val snapshot = recorder.snapshot.value
            recorder.submit(RecorderCommand.Stop(StopCause.USER))
            runCurrent()
            run.await()
            recorded = Recorded(files.rowsAndEvents, snapshot, files.calls.filterIsInstance<FileCall.Snapshot>().last().meta)
        }
        return recorded
    }

    private data class Cell(val pci: Int, val rsrp: Int)

    private data class Position(val lat: Double, val lon: Double)

    /** A request answer every second in [range]; each carries the newest 2 s modem refresh at or before it. */
    private fun answers(range: IntRange, cellAt: (Long) -> Cell): List<Timed> =
        (range step 1_000).mapNotNull { at ->
            val atMs = at.toLong()
            val measuredMs = Math.floorDiv(atMs - 400, 2_000L) * 2_000 + 400
            if (measuredMs < 0) null else Timed(atMs, answer(atMs, measuredMs, cellAt(measuredMs)))
        }

    private fun answer(atMs: Long, measuredMs: Long, cell: Cell): CellInfoAnswer = CellInfoAnswer(
        source = CellInfoSource.REQUEST,
        cells = listOf(
            CellSnapshot(
                rat = Rat.LTE,
                registered = true,
                connectionStatus = CellSnapshot.CONNECTION_PRIMARY_SERVING,
                timestampMs = START_ELAPSED_MS + measuredMs,
                mcc = "311",
                mnc = "480",
                pci = cell.pci,
                arfcn = 66_786,
                bands = listOf(66),
                tac = 18_704,
                cellId = 21_640_000L + cell.pci,
                rsrp = cell.rsrp,
                rsrq = -10,
                sinr = 12,
            ),
        ),
        subId = 1,
        conditions = DeviceConditions(screenOn = true, charging = false, wifiConnected = false),
        observedWallMs = START_WALL_MS + atMs,
        observedElapsedMs = START_ELAPSED_MS + atMs,
    )

    /** A GPS fix every second in [range]. */
    private fun fixes(range: IntRange, positionAt: (Long) -> Position): List<Timed> =
        (range step 1_000).map { at ->
            val atMs = at.toLong()
            val position = positionAt(atMs)
            Timed(atMs, SessionFixtures.fix(START_ELAPSED_MS + atMs, START_WALL_MS + atMs, position.lat, position.lon))
        }

    private companion object {
        const val ZONE_LAT = 38.8895123
        const val ZONE_LON = -77.0352671
        val METRES_PER_DEGREE: Double = 6_371_000.0 * PI / 180.0

        val CENTRE = Position(ZONE_LAT, ZONE_LON)
        val FAR_NORTH = Position(ZONE_LAT + 0.01, ZONE_LON)
        val FAR_WEST = Position(ZONE_LAT, ZONE_LON - 0.013)
        val NEAR = Position(ZONE_LAT + 300.0 / METRES_PER_DEGREE, ZONE_LON)
        val COARSE_NEAR_CENTRE = Position(ZONE_LAT + 180.0 / METRES_PER_DEGREE, ZONE_LON)

        val BEFORE = Cell(pci = 212, rsrp = -88)
        val INSIDE = Cell(pci = 213, rsrp = -71)
        val AFTER = Cell(pci = 213, rsrp = -97)
    }
}
