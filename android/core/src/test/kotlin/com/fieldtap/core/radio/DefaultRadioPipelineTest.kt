package com.fieldtap.core.radio

import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.DataConnState
import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.radio.RadioFixtures.BOOT0
import com.fieldtap.core.radio.RadioFixtures.WALL0
import com.fieldtap.core.radio.RadioFixtures.answer
import com.fieldtap.core.radio.RadioFixtures.dataState
import com.fieldtap.core.radio.RadioFixtures.displayInfo
import com.fieldtap.core.radio.RadioFixtures.lte
import com.fieldtap.core.radio.RadioFixtures.nr
import com.fieldtap.core.radio.RadioFixtures.serviceState
import com.fieldtap.format.EventKind
import com.fieldtap.format.EventRat
import com.fieldtap.format.EventRow
import com.fieldtap.format.GapMeta
import com.fieldtap.format.ServingRat
import com.fieldtap.format.Severity
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRadioPipelineTest {
    private val pipeline = DefaultRadioPipeline()
    private val none = emptyList<EventRow>()

    private class Timed(val atMs: Long, val action: () -> Unit)

    /** What the golden session's inputs produced, step by step and as one event stream. */
    private class GoldenRun(val steps: List<RadioStep>, val events: List<EventRow>)

    /**
     * The golden session's inputs, in the order a phone delivers them: service, data and display
     * baselines at the start, the 120 answers, the three 5G icon changes of its events.csv, and the
     * session ticker every second. Everything is written: the golden walk crosses no privacy zone.
     */
    private fun feedGolden(): GoldenRun {
        val steps = ArrayList<RadioStep>()
        val events = ArrayList<EventRow>()
        val timeline = ArrayList<Timed>()
        timeline.add(Timed(0) { events.addAll(pipeline.onServiceState(serviceState(0, ServiceRegState.IN_SERVICE), writing = true)) })
        timeline.add(Timed(0) { events.addAll(pipeline.onDataState(dataState(0, DataConnState.CONNECTED, NETWORK_LTE), writing = true)) })
        timeline.add(Timed(0) { events.addAll(pipeline.onDisplayInfo(displayInfo(0, NETWORK_LTE, OVERRIDE_NONE), writing = true)) })
        for ((atMs, overrideType) in listOf(8_900L to OVERRIDE_NR_NSA, 62_900L to OVERRIDE_NONE, 70_900L to OVERRIDE_NR_NSA)) {
            timeline.add(Timed(atMs) { events.addAll(pipeline.onDisplayInfo(displayInfo(atMs, NETWORK_LTE, overrideType), writing = true)) })
        }
        for (observed in GoldenSession.answers()) {
            timeline.add(
                Timed(observed.observedElapsedMs - BOOT0) {
                    val step = pipeline.onCellInfo(observed, writing = true)
                    steps.add(step)
                    events.addAll(step.events)
                },
            )
        }
        for (second in 0 until 120) {
            val atMs = 500L + second * 1_000L
            timeline.add(Timed(atMs) { pipeline.onTick(BOOT0 + atMs) })
        }
        for (timed in timeline.sortedBy { it.atMs }) timed.action()
        return GoldenRun(steps, events)
    }

    @Test
    fun theGoldenAnswersGiveTheGoldenKpiAndCellInfoRows() {
        val run = feedGolden()
        assertEquals(120, run.steps.size)

        val kpi = run.steps.flatMap { it.kpi }
        assertRows("kpi.csv", GoldenSession.kpiRows(), kpi.map { it.row })
        assertEquals(100, kpi.size)
        for (candidate in kpi) {
            assertNull(candidate.row.position)
            assertEquals(BOOT0 + (candidate.row.timeEpochMs - WALL0), candidate.measurementElapsedMs)
        }

        val cellInfo = run.steps.flatMap { it.cellInfo }
        assertRows("cellinfo.csv", GoldenSession.cellInfoRows(), cellInfo.map { it.row })
        assertEquals(344, cellInfo.size)
        for (candidate in cellInfo) {
            assertNull(candidate.row.position)
            assertEquals(candidate.row.timestampMs, candidate.measurementElapsedMs)
        }
    }

    @Test
    fun theGoldenAnswersGiveTheGoldenRadioEventsInDerivationOrder() {
        val events = feedGolden().events
        assertRows("events.csv", GoldenSession.eventRows().filter { it.kind != EventKind.MARKER }, events)
        assertEquals(
            listOf(
                EventKind.SERVING_CELL,
                EventKind.NR_DISPLAY,
                EventKind.NR_DISPLAY,
                EventKind.SERVING_CELL,
                EventKind.NR_DISPLAY,
                EventKind.SAMPLING_GAP,
            ),
            events.map { it.kind },
        )
        val gap = events.last()
        assertEquals(GoldenSession.utcMs("2026-09-10T14:31:44.900+00:00"), gap.timeUtcMs)
        assertEquals("no fresh cell info for 14.0 s", gap.detail)
        assertEquals(GapReasons.SCREEN_OFF, gap.cause)
    }

    @Test
    fun theGoldenAnswersGiveTheGoldenCollection() {
        feedGolden()
        val collection = pipeline.collection()
        assertEquals(54L, collection.freshSamples)
        assertEquals(66L, collection.repeatsDropped)
        assertEquals(2_000L, collection.medianFreshIntervalMs)
        assertEquals(88.3, oneDecimal(collection.shortIntervalPct))
        assertEquals(88.3, oneDecimal(collection.screenOnPct))
        assertEquals(0.0, oneDecimal(collection.wifiConnectedPct))
        assertEquals(0.0, oneDecimal(collection.chargingPct))
        assertEquals(listOf(GapMeta(WALL0 + 90_400, WALL0 + 104_400, GapReasons.SCREEN_OFF)), collection.gaps)
        assertEquals(
            GoldenSession.collection(),
            collection.copy(
                shortIntervalPct = oneDecimal(collection.shortIntervalPct),
                screenOnPct = oneDecimal(collection.screenOnPct),
                wifiConnectedPct = oneDecimal(collection.wifiConnectedPct),
                chargingPct = oneDecimal(collection.chargingPct),
            ),
        )
    }

    @Test
    fun theWrittenGoldenKpiRowsGiveTheGoldenCellsAndPlmns() {
        val kpi = feedGolden().steps.flatMap { it.kpi }
        assertTrue(pipeline.cells().isEmpty())
        assertTrue(pipeline.plmns().isEmpty())
        for (candidate in kpi) pipeline.onKpiWritten(candidate)
        assertRows("cells.csv", GoldenSession.cellRows(), pipeline.cells())
        assertEquals(listOf(32, 46, 22), pipeline.cells().map { it.samples })
        assertEquals(mapOf("311480" to 54), pipeline.plmns())
        assertEquals(GoldenSession.plmns(), pipeline.plmns())
    }

    @Test
    fun onlyRowsReportedAsWrittenCountTowardCellsAndPlmns() {
        val lteRows = feedGolden().steps.flatMap { it.kpi }.filter { it.row.rat == ServingRat.LTE }
        for (candidate in lteRows.take(10)) pipeline.onKpiWritten(candidate)
        assertEquals(mapOf("311480" to 10), pipeline.plmns())
        assertEquals(listOf(10), pipeline.cells().map { it.samples })
    }

    @Test
    fun latestIsTheNewestClassifiedAnswerPausedOrNot() {
        assertNull(pipeline.latest())
        val first = answer(900, listOf(lte(400)))
        pipeline.onCellInfo(first, writing = true)
        assertSame(first, pipeline.latest()!!.answer)
        val paused = answer(1_900, listOf(lte(1_400)))
        pipeline.onCellInfo(paused, writing = false)
        assertSame(paused, pipeline.latest()!!.answer)
        assertTrue(pipeline.latest()!!.fresh)
    }

    @Test
    fun aPausedAnswerWritesNothingButItsMeasurementIsStillRecognised() {
        val first = pipeline.onCellInfo(answer(900, listOf(lte(400), nr(400))), writing = true)
        assertEquals(listOf(ServingRat.LTE, ServingRat.NR), first.kpi.map { it.row.rat })
        assertEquals(2, first.cellInfo.size)
        assertEquals(listOf(EventKind.SERVING_CELL), first.events.map { it.kind })
        val before = pipeline.collection()

        val sector2 = lte(20_400, pci = 213, cellId = 21_640_194L)
        assertSame(RadioStep.EMPTY, pipeline.onCellInfo(answer(20_900, listOf(sector2)), writing = false))
        assertEquals(before, pipeline.collection())
        assertEquals(none, pipeline.onResume(WALL0 + 22_000, BOOT0 + 22_000))

        // The measurement taken inside the zone arrives again after it: a repeat, never a KPI row.
        val again = pipeline.onCellInfo(answer(22_900, listOf(sector2)), writing = true)
        assertTrue(again.kpi.isEmpty())
        assertEquals(listOf(true), again.cellInfo.map { it.row.stale })
        assertEquals(none, again.events)

        // The first fresh sample after the zone names the new cell, and no gap straddles the pause.
        val next = pipeline.onCellInfo(answer(24_900, listOf(lte(24_400, pci = 213, cellId = 21_640_194L))), writing = true)
        assertEquals(1, next.kpi.size)
        assertEquals(listOf("Serving cell changed"), next.events.map { it.title })
        assertEquals(WALL0 + 24_400, next.events.single().timeUtcMs)

        val collection = pipeline.collection()
        assertEquals(2L, collection.freshSamples)
        assertEquals(1L, collection.repeatsDropped)
        assertNull(collection.medianFreshIntervalMs)
        assertTrue(collection.gaps.isEmpty())
    }

    @Test
    fun resumeWritesWhatChangedInsideTheZoneStampedWithTheResumeTime() {
        assertEquals(none, pipeline.onServiceState(serviceState(0, ServiceRegState.IN_SERVICE), writing = true))
        assertEquals(none, pipeline.onDataState(dataState(0, DataConnState.CONNECTED, NETWORK_LTE), writing = true))
        assertEquals(none, pipeline.onDisplayInfo(displayInfo(0, NETWORK_LTE, OVERRIDE_NONE), writing = true))
        assertEquals(1, pipeline.onCellInfo(answer(900, listOf(lte(400))), writing = true).events.size)

        assertEquals(none, pipeline.onServiceState(serviceState(5_000, ServiceRegState.OUT_OF_SERVICE), writing = false))
        assertEquals(none, pipeline.onDataState(dataState(5_000, DataConnState.DISCONNECTED, 0), writing = false))
        assertEquals(none, pipeline.onDisplayInfo(displayInfo(6_000, NETWORK_LTE, OVERRIDE_NR_NSA), writing = false))

        assertEquals(
            listOf(
                EventRow(WALL0 + 30_000, EventRat.LTE, EventKind.SERVICE_LOST, Severity.ERROR, "Service lost", "out of service"),
                EventRow(WALL0 + 30_000, EventRat.NONE, EventKind.DATA_STATE, Severity.WARN, "Mobile data disconnected", "disconnected"),
                EventRow(WALL0 + 30_000, EventRat.NR, EventKind.NR_DISPLAY, Severity.INFO, "5G icon on", "override NR_NSA, network LTE"),
            ),
            pipeline.onResume(WALL0 + 30_000, BOOT0 + 30_000),
        )
        // The files now say what the phone says: another resume has nothing to add.
        assertEquals(none, pipeline.onResume(WALL0 + 40_000, BOOT0 + 40_000))
    }

    @Test
    fun aChangeUndoneInsideTheZoneLeavesNoTrace() {
        pipeline.onServiceState(serviceState(0, ServiceRegState.IN_SERVICE), writing = true)
        pipeline.onDataState(dataState(0, DataConnState.CONNECTED, NETWORK_LTE), writing = true)
        pipeline.onDisplayInfo(displayInfo(0, NETWORK_LTE, OVERRIDE_NONE), writing = true)
        pipeline.onCellInfo(answer(900, listOf(lte(400))), writing = true)

        pipeline.onServiceState(serviceState(5_000, ServiceRegState.OUT_OF_SERVICE), writing = false)
        pipeline.onDataState(dataState(5_000, DataConnState.DISCONNECTED, 0), writing = false)
        pipeline.onDisplayInfo(displayInfo(6_000, NETWORK_LTE, OVERRIDE_NR_NSA), writing = false)
        pipeline.onDisplayInfo(displayInfo(7_000, NETWORK_LTE, OVERRIDE_NONE), writing = false)
        pipeline.onDataState(dataState(8_000, DataConnState.CONNECTED, NETWORK_LTE), writing = false)
        pipeline.onServiceState(serviceState(8_000, ServiceRegState.IN_SERVICE), writing = false)

        assertEquals(none, pipeline.onResume(WALL0 + 30_000, BOOT0 + 30_000))
    }

    @Test
    fun noGapOrIntervalStraddlesAPause() {
        pipeline.onCellInfo(answer(900, listOf(lte(400))), writing = true)
        var atMs = 2_900L
        while (atMs <= 60_900L) {
            assertSame(RadioStep.EMPTY, pipeline.onCellInfo(answer(atMs, listOf(lte(atMs - 500))), writing = false))
            atMs += 2_000
        }
        assertEquals(none, pipeline.onResume(WALL0 + 61_000, BOOT0 + 61_000))
        assertEquals(none, pipeline.onCellInfo(answer(62_900, listOf(lte(62_400))), writing = true).events)
        assertEquals(none, pipeline.onCellInfo(answer(64_900, listOf(lte(64_400))), writing = true).events)

        val collection = pipeline.collection()
        assertEquals(3L, collection.freshSamples)
        assertEquals(0L, collection.repeatsDropped)
        assertEquals(2_000L, collection.medianFreshIntervalMs)
        assertTrue(collection.gaps.isEmpty())
    }

    @Test
    fun serviceLostInsideAZoneStillExplainsAGapAfterIt() {
        pipeline.onCellInfo(answer(900, listOf(lte(400))), writing = true)
        pipeline.onServiceState(serviceState(1_000, ServiceRegState.IN_SERVICE), writing = true)
        assertEquals(none, pipeline.onServiceState(serviceState(5_000, ServiceRegState.OUT_OF_SERVICE), writing = false))
        assertEquals(listOf(EventKind.SERVICE_LOST), pipeline.onResume(WALL0 + 10_000, BOOT0 + 10_000).map { it.kind })
        assertEquals(none, pipeline.onCellInfo(answer(10_900, listOf(lte(10_400))), writing = true).events)

        val step = pipeline.onCellInfo(answer(30_900, listOf(lte(30_400))), writing = true)
        assertEquals(
            listOf(SamplingGap(WALL0 + 10_400, WALL0 + 30_400, GapReasons.NO_SERVICE, WALL0 + 30_900).toEvent()),
            step.events,
        )
        assertEquals(listOf(GapMeta(WALL0 + 10_400, WALL0 + 30_400, GapReasons.NO_SERVICE)), pipeline.collection().gaps)
    }

    @Test
    fun ticksReachTheGapDetector() {
        pipeline.onTick(BOOT0 + 500)
        pipeline.onCellInfo(answer(900, listOf(lte(400))), writing = true)
        pipeline.onTick(BOOT0 + 1_500)
        pipeline.onTick(BOOT0 + 12_500)
        pipeline.onTick(BOOT0 + 13_500)
        pipeline.onTick(BOOT0 + 14_500)
        val step = pipeline.onCellInfo(answer(14_900, listOf(lte(14_400))), writing = true)
        assertEquals(listOf(GapReasons.APP_PAUSED), step.events.map { it.cause })
    }

    @Test
    fun aGapEventFollowsTheCellEventsOfTheAnswerThatEndsIt() {
        assertEquals(listOf(EventKind.SERVING_CELL), pipeline.onCellInfo(answer(900, listOf(lte(400))), writing = true).events.map { it.kind })
        val step = pipeline.onCellInfo(answer(10_900, listOf(lte(10_400, pci = 213, cellId = 21_640_194L))), writing = true)
        assertEquals(listOf(EventKind.SERVING_CELL, EventKind.SAMPLING_GAP), step.events.map { it.kind })
        assertEquals("Serving cell changed", step.events[0].title)
        assertEquals(WALL0 + 10_400, step.events[0].timeUtcMs)
        assertEquals(SamplingGap(WALL0 + 400, WALL0 + 10_400, GapReasons.UNKNOWN, WALL0 + 10_900).toEvent(), step.events[1])
        assertEquals(listOf(GapMeta(WALL0 + 400, WALL0 + 10_400, GapReasons.UNKNOWN)), pipeline.collection().gaps)
    }

    @Test
    fun aSimLessPhoneIsEmergencyOnlyFromTheStartAndItsCampedCellIsStillMeasured() {
        assertEquals(none, pipeline.onServiceState(serviceState(0, ServiceRegState.OUT_OF_SERVICE, emergencyOnly = true), writing = true))
        val camped = nr(900, pci = 55, arfcn = 520_110, bands = listOf(41), status = CellSnapshot.CONNECTION_PRIMARY_SERVING)
        val step = pipeline.onCellInfo(answer(1_400, listOf(camped)), writing = true)
        assertEquals(listOf(ServingRat.NR), step.kpi.map { it.row.rat })
        assertEquals(55, step.kpi.single().row.pci)
        assertEquals(
            listOf(
                EventRow(WALL0 + 900, EventRat.NR, EventKind.SERVING_CELL, Severity.INFO, "Serving cell", "PCI 55 NR-ARFCN 520110 band 41", 55, 520_110),
                EventRow(WALL0 + 900, EventRat.NR, EventKind.EMERGENCY_ONLY, Severity.ERROR, "Emergency calls only", null, 55, 520_110),
            ),
            step.events,
        )
    }

    @Test
    fun anSaToLteFallbackWarnsThenNamesTheNewCell() {
        val sa = nr(400, status = CellSnapshot.CONNECTION_PRIMARY_SERVING, cellId = 123_456_789L, mcc = "311", mnc = "480", tac = 18_704)
        val first = pipeline.onCellInfo(answer(900, listOf(sa)), writing = true)
        assertEquals(listOf(ServingRat.NR), first.kpi.map { it.row.rat })
        assertEquals(listOf(EventKind.SERVING_CELL), first.events.map { it.kind })

        val fallback = pipeline.onCellInfo(answer(2_900, listOf(lte(2_400))), writing = true)
        assertEquals(listOf(ServingRat.LTE), fallback.kpi.map { it.row.rat })
        assertEquals(listOf(EventKind.RAT_CHANGE, EventKind.SERVING_CELL), fallback.events.map { it.kind })
        assertEquals(Severity.WARN, fallback.events[0].severity)
        assertEquals(EventRat.LTE, fallback.events[0].rat)
        assertEquals("NR to LTE", fallback.events[0].detail)
        assertEquals("Serving cell changed", fallback.events[1].title)
    }

    @Test
    fun anNsaLegThatDropsOrChangesIsNotAnEvent() {
        val first = pipeline.onCellInfo(answer(900, listOf(lte(400), nr(400))), writing = true)
        assertEquals(listOf(ServingRat.LTE, ServingRat.NR), first.kpi.map { it.row.rat })
        assertEquals(listOf(EventKind.SERVING_CELL), first.events.map { it.kind })

        val dropped = pipeline.onCellInfo(answer(2_900, listOf(lte(2_400))), writing = true)
        assertEquals(listOf(ServingRat.LTE), dropped.kpi.map { it.row.rat })
        assertEquals(none, dropped.events)

        val changed = pipeline.onCellInfo(answer(4_900, listOf(lte(4_400), nr(4_400, pci = 394))), writing = true)
        assertEquals(listOf(212, 394), changed.kpi.map { it.row.pci })
        assertEquals(none, changed.events)
    }

    /** Compares two row lists one row at a time, so a failure names the first differing row. */
    private fun <T> assertRows(file: String, expected: List<T>, actual: List<T>) {
        for (index in 0 until minOf(expected.size, actual.size)) {
            assertEquals("$file data row ${index + 1}", expected[index], actual[index])
        }
        assertEquals("$file row count", expected.size, actual.size)
    }

    private companion object {
        const val NETWORK_LTE: Int = NetworkTypeNames.NETWORK_TYPE_LTE
        const val OVERRIDE_NONE: Int = 0
        const val OVERRIDE_NR_NSA: Int = NetworkTypeNames.OVERRIDE_NR_NSA
    }
}

/** A share as session.json writes it: one decimal, half-even on the exact binary value. */
private fun oneDecimal(value: Double?): Double? =
    value?.let { BigDecimal(it).setScale(1, RoundingMode.HALF_EVEN).toDouble() }
