package com.fieldtap.core.radio

import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.DataConnState
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.radio.RadioFixtures.SHORT
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
import com.fieldtap.format.ServingRat
import com.fieldtap.format.Severity
import org.junit.Assert.assertEquals
import org.junit.Test

class RadioEventDeriverTest {
    private val engine = FreshnessEngine()
    private val deriver = RadioEventDeriver()
    private val none = emptyList<EventRow>()

    private val sector1 = "PLMN 311480 TAC 18704 eNB 84532 sector 1 PCI 212 EARFCN 66786 band 66"
    private val sector2 = "PLMN 311480 TAC 18704 eNB 84532 sector 2 PCI 213 EARFCN 66786 band 66"

    /** One answer through the freshness engine and the KPI rules, then its accepted candidates to the deriver. */
    private fun sample(atMs: Long, vararg cells: CellSnapshot, conditions: DeviceConditions = SHORT): List<EventRow> {
        val classified = engine.classify(answer(atMs, cells.toList(), conditions = conditions))
        val accepted = RadioRows.kpi(classified)
        return if (accepted.isEmpty()) emptyList() else deriver.onAccepted(classified, accepted)
    }

    private fun saCell(measuredAtMs: Long): CellSnapshot = nr(
        measuredAtMs,
        status = CellSnapshot.CONNECTION_PRIMARY_SERVING,
        cellId = 123_456_789L,
        mcc = "311",
        mnc = "480",
        tac = 18_704,
    )

    @Test
    fun theFirstServingCellAndThenOnlyAnchorChangesAreEvents() {
        assertEquals(
            listOf(EventRow(WALL0 + 400, EventRat.LTE, EventKind.SERVING_CELL, Severity.INFO, "Serving cell", sector1, 212, 66_786)),
            sample(900, lte(400)),
        )
        assertEquals(none, sample(2_900, lte(2_400)))
        assertEquals(
            listOf(EventRow(WALL0 + 4_400, EventRat.LTE, EventKind.SERVING_CELL, Severity.INFO, "Serving cell changed", sector2, 213, 66_786)),
            sample(4_900, lte(4_400, pci = 213, cellId = 21_640_194L)),
        )
    }

    @Test
    fun nsaLegChangesAndDropsAreNotEvents() {
        assertEquals(1, sample(900, lte(400), nr(400)).size)
        assertEquals(none, sample(2_900, lte(2_400)))
        assertEquals(none, sample(4_900, lte(4_400), nr(4_400, pci = 394)))
        assertEquals(none, sample(6_900, lte(6_400), nr(6_400)))
    }

    @Test
    fun aSampleThatIsNotAcceptedDoesNotMoveTheBaseline() {
        sample(900, lte(400))
        // The new cell arrives 3 s old on the 2 s interval: not a KPI row, so not yet a change.
        assertEquals(none, sample(5_400, lte(2_400, pci = 213, cellId = 21_640_194L)))
        assertEquals(
            listOf(EventRow(WALL0 + 4_400, EventRat.LTE, EventKind.SERVING_CELL, Severity.INFO, "Serving cell changed", sector2, 213, 66_786)),
            sample(4_900, lte(4_400, pci = 213, cellId = 21_640_194L)),
        )
    }

    @Test
    fun anSaToLteFallbackWarnsThenNamesTheNewCell() {
        assertEquals(
            listOf(
                EventRow(
                    WALL0 + 400,
                    EventRat.NR,
                    EventKind.SERVING_CELL,
                    Severity.INFO,
                    "Serving cell",
                    "PLMN 311480 TAC 18704 NCI 123456789 PCI 393 NR-ARFCN 650000 band 77",
                    393,
                    650_000,
                ),
            ),
            sample(900, saCell(400)),
        )
        assertEquals(
            listOf(
                EventRow(WALL0 + 2_400, EventRat.LTE, EventKind.RAT_CHANGE, Severity.WARN, "RAT changed", "NR to LTE", 212, 66_786),
                EventRow(WALL0 + 2_400, EventRat.LTE, EventKind.SERVING_CELL, Severity.INFO, "Serving cell changed", sector1, 212, 66_786),
            ),
            sample(2_900, lte(2_400)),
        )

        val back = sample(4_900, saCell(4_400))
        assertEquals(listOf(EventKind.RAT_CHANGE, EventKind.SERVING_CELL), back.map { it.kind })
        assertEquals(Severity.INFO, back[0].severity)
        assertEquals("LTE to NR", back[0].detail)
        assertEquals(EventRat.NR, back[0].rat)
    }

    @Test
    fun aSimLessPhoneStartsEmergencyOnlyOnItsCampedCell() {
        assertEquals(none, deriver.onServiceState(serviceState(0, ServiceRegState.OUT_OF_SERVICE, emergencyOnly = true)))

        val camped = nr(900, pci = 55, arfcn = 520_110, bands = listOf(41), status = CellSnapshot.CONNECTION_PRIMARY_SERVING)
        assertEquals(
            listOf(
                EventRow(WALL0 + 900, EventRat.NR, EventKind.SERVING_CELL, Severity.INFO, "Serving cell", "PCI 55 NR-ARFCN 520110 band 41", 55, 520_110),
                EventRow(WALL0 + 900, EventRat.NR, EventKind.EMERGENCY_ONLY, Severity.ERROR, "Emergency calls only", null, 55, 520_110),
            ),
            sample(1_400, camped),
        )
        assertEquals(none, deriver.onServiceState(serviceState(5_000, ServiceRegState.EMERGENCY_ONLY)))
        assertEquals(
            none,
            sample(3_400, nr(2_900, pci = 55, arfcn = 520_110, bands = listOf(41), status = CellSnapshot.CONNECTION_PRIMARY_SERVING)),
        )
    }

    @Test
    fun emergencyOnlyWithACellMeasuredWithin11SecondsIsWrittenAtOnce() {
        sample(900, lte(400))
        assertEquals(none, deriver.onServiceState(serviceState(1_000, ServiceRegState.IN_SERVICE)))
        assertEquals(
            listOf(EventRow(WALL0 + 11_400, EventRat.LTE, EventKind.EMERGENCY_ONLY, Severity.ERROR, "Emergency calls only", null, 212, 66_786)),
            deriver.onServiceState(serviceState(11_400, ServiceRegState.EMERGENCY_ONLY)),
        )
        assertEquals(none, sample(12_900, lte(12_400)))
    }

    @Test
    fun emergencyOnlyWithAnOlderCellWaitsForTheNextSample() {
        sample(900, lte(400))
        deriver.onServiceState(serviceState(1_000, ServiceRegState.IN_SERVICE))
        assertEquals(none, deriver.onServiceState(serviceState(11_401, ServiceRegState.EMERGENCY_ONLY)))
        assertEquals(
            listOf(EventRow(WALL0 + 12_400, EventRat.LTE, EventKind.EMERGENCY_ONLY, Severity.ERROR, "Emergency calls only", null, 212, 66_786)),
            sample(12_900, lte(12_400)),
        )
    }

    @Test
    fun aPendingServiceRestoredIsResolvedByTheNextSample() {
        sample(900, lte(400))
        assertEquals(none, deriver.onServiceState(serviceState(1_000, ServiceRegState.IN_SERVICE)))
        assertEquals(
            listOf(EventRow(WALL0 + 2_000, EventRat.LTE, EventKind.SERVICE_LOST, Severity.ERROR, "Service lost", "out of service")),
            deriver.onServiceState(serviceState(2_000, ServiceRegState.OUT_OF_SERVICE)),
        )
        // Back in service 28 s later: the last cell is too old to name, so the event waits.
        assertEquals(none, deriver.onServiceState(serviceState(30_000, ServiceRegState.IN_SERVICE)))
        assertEquals(
            listOf(EventRow(WALL0 + 30_600, EventRat.LTE, EventKind.SERVICE_RESTORED, Severity.OK, "Service restored", null, 212, 66_786)),
            sample(31_000, lte(30_600)),
        )
        assertEquals(none, sample(33_000, lte(32_600)))
        assertEquals(none, deriver.onServiceState(serviceState(34_000, ServiceRegState.IN_SERVICE)))
    }

    @Test
    fun aPendingEventIsNeverTimedBeforeTheStateWasObserved() {
        sample(900, lte(400))
        deriver.onServiceState(serviceState(1_000, ServiceRegState.IN_SERVICE))
        deriver.onServiceState(serviceState(2_000, ServiceRegState.OUT_OF_SERVICE))
        deriver.onServiceState(serviceState(30_000, ServiceRegState.IN_SERVICE))
        // Measured at 29.9 s, just before the restore was observed at 30.0 s.
        val events = sample(30_400, lte(29_900))
        assertEquals(EventKind.SERVICE_RESTORED, events.single().kind)
        assertEquals(WALL0 + 30_000, events.single().timeUtcMs)
    }

    @Test
    fun aRestoreThatNeverFoundACellIsDroppedWhenServiceIsLostAgain() {
        sample(900, lte(400))
        deriver.onServiceState(serviceState(1_000, ServiceRegState.IN_SERVICE))
        assertEquals(1, deriver.onServiceState(serviceState(20_000, ServiceRegState.OUT_OF_SERVICE)).size)
        assertEquals(none, deriver.onServiceState(serviceState(40_000, ServiceRegState.IN_SERVICE)))
        // Lost again before any sample: the files still say lost, so nothing is written.
        assertEquals(none, deriver.onServiceState(serviceState(41_000, ServiceRegState.OUT_OF_SERVICE)))
        assertEquals(none, sample(43_000, lte(42_500)))
    }

    @Test
    fun anEmergencyBlipThatNeverFoundACellLeavesNoTrace() {
        sample(900, lte(400))
        deriver.onServiceState(serviceState(1_000, ServiceRegState.IN_SERVICE))
        assertEquals(none, deriver.onServiceState(serviceState(40_000, ServiceRegState.EMERGENCY_ONLY)))
        assertEquals(none, deriver.onServiceState(serviceState(41_000, ServiceRegState.IN_SERVICE)))
        assertEquals(none, sample(43_000, lte(42_500)))
    }

    @Test
    fun anEmergencyStartThatEndsBeforeTheFirstCellLeavesNoTrace() {
        assertEquals(none, deriver.onServiceState(serviceState(0, ServiceRegState.EMERGENCY_ONLY)))
        assertEquals(none, deriver.onServiceState(serviceState(500, ServiceRegState.IN_SERVICE)))
        assertEquals(listOf(EventKind.SERVING_CELL), sample(1_400, lte(900)).map { it.kind })
    }

    @Test
    fun aNewerTransitionReplacesAPendingOne() {
        assertEquals(
            listOf(EventRow(WALL0, EventRat.NONE, EventKind.SERVICE_LOST, Severity.ERROR, "Service lost", "out of service")),
            deriver.onServiceState(serviceState(0, ServiceRegState.OUT_OF_SERVICE)),
        )
        assertEquals(none, deriver.onServiceState(serviceState(5_000, ServiceRegState.EMERGENCY_ONLY)))
        assertEquals(none, deriver.onServiceState(serviceState(6_000, ServiceRegState.IN_SERVICE)))
        assertEquals(listOf(EventKind.SERVING_CELL, EventKind.SERVICE_RESTORED), sample(8_000, lte(7_500)).map { it.kind })
    }

    @Test
    fun aFirstSnapshotAlreadyOutOfServiceOrRadioOffIsWritten() {
        assertEquals(
            listOf(EventRow(WALL0, EventRat.NONE, EventKind.SERVICE_LOST, Severity.ERROR, "Service lost", "radio off")),
            deriver.onServiceState(serviceState(0, ServiceRegState.POWER_OFF)),
        )
        assertEquals(none, deriver.onServiceState(serviceState(1_000, ServiceRegState.POWER_OFF)))
        assertEquals(none, deriver.onServiceState(serviceState(2_000, ServiceRegState.OUT_OF_SERVICE)))
    }

    @Test
    fun anInServiceBaselineAndUnknownStatesAreSilent() {
        assertEquals(none, deriver.onServiceState(serviceState(0, ServiceRegState.UNKNOWN)))
        assertEquals(none, deriver.onServiceState(serviceState(1_000, ServiceRegState.IN_SERVICE)))
        assertEquals(none, deriver.onServiceState(serviceState(2_000, ServiceRegState.UNKNOWN)))
        assertEquals(none, deriver.onServiceState(serviceState(3_000, ServiceRegState.IN_SERVICE)))
        assertEquals(1, deriver.onServiceState(serviceState(4_000, ServiceRegState.OUT_OF_SERVICE)).size)
    }

    @Test
    fun serviceLostNamesTheLastPrimaryRat() {
        sample(900, saCell(400))
        deriver.onServiceState(serviceState(1_000, ServiceRegState.IN_SERVICE))
        assertEquals(EventRat.NR, deriver.onServiceState(serviceState(2_000, ServiceRegState.OUT_OF_SERVICE)).single().rat)
    }

    @Test
    fun mobileDataEventsFollowStateAndNetworkTypeChanges() {
        assertEquals(none, deriver.onDataState(dataState(0, DataConnState.CONNECTED, 13)))
        assertEquals(none, deriver.onDataState(dataState(1_000, DataConnState.CONNECTED, 13)))
        assertEquals(
            listOf(EventRow(WALL0 + 2_000, EventRat.NONE, EventKind.DATA_STATE, Severity.WARN, "Mobile data disconnected", "disconnected")),
            deriver.onDataState(dataState(2_000, DataConnState.DISCONNECTED, 0)),
        )
        assertEquals(
            listOf(EventRow(WALL0 + 3_000, EventRat.NONE, EventKind.DATA_STATE, Severity.INFO, "Mobile data connecting", "connecting, NR")),
            deriver.onDataState(dataState(3_000, DataConnState.CONNECTING, 20)),
        )
        assertEquals(
            listOf(EventRow(WALL0 + 4_000, EventRat.NONE, EventKind.DATA_STATE, Severity.INFO, "Mobile data connected", "connected, NR")),
            deriver.onDataState(dataState(4_000, DataConnState.CONNECTED, 20)),
        )
        assertEquals(
            listOf(EventRow(WALL0 + 5_000, EventRat.NONE, EventKind.DATA_STATE, Severity.INFO, "Mobile data connected", "connected, LTE")),
            deriver.onDataState(dataState(5_000, DataConnState.CONNECTED, 13)),
        )
        assertEquals(Severity.WARN, deriver.onDataState(dataState(6_000, DataConnState.DISCONNECTED, 13)).single().severity)
        // Still disconnected and only the network type changed: not a new disconnection.
        assertEquals(Severity.INFO, deriver.onDataState(dataState(7_000, DataConnState.DISCONNECTED, 0)).single().severity)
    }

    @Test
    fun theFiveGIconIsAnEventOnlyWhenItChanges() {
        assertEquals(none, deriver.onDisplayInfo(displayInfo(0, 13, 0)))
        assertEquals(
            listOf(EventRow(WALL0 + 8_900, EventRat.NR, EventKind.NR_DISPLAY, Severity.INFO, "5G icon on", "override NR_NSA, network LTE")),
            deriver.onDisplayInfo(displayInfo(8_900, 13, 3)),
        )
        assertEquals(none, deriver.onDisplayInfo(displayInfo(9_000, 13, 5)))
        assertEquals(
            listOf(EventRow(WALL0 + 62_900, EventRat.NR, EventKind.NR_DISPLAY, Severity.INFO, "5G icon off", "override NONE, network LTE")),
            deriver.onDisplayInfo(displayInfo(62_900, 13, 0)),
        )
        assertEquals(
            listOf(EventRow(WALL0 + 70_000, EventRat.NR, EventKind.NR_DISPLAY, Severity.INFO, "5G icon on", "override NONE, network NR")),
            deriver.onDisplayInfo(displayInfo(70_000, 20, 0)),
        )
    }

    @Test
    fun aFirstDisplayInfoThatShowsTheIconIsAnEvent() {
        assertEquals("5G icon on", deriver.onDisplayInfo(displayInfo(0, 13, 4)).single().title)
    }

    @Test
    fun eventTextLeavesOutUnknownParts() {
        val lteIdentity = RadioRows.identity(lte(0))!!
        assertEquals(sector1, RadioEventText.servingDetail(lteIdentity))
        val saIdentity = RadioRows.identity(saCell(0))!!
        assertEquals("PLMN 311480 TAC 18704 NCI 123456789 PCI 393 NR-ARFCN 650000 band 77", RadioEventText.servingDetail(saIdentity))
        assertEquals("PCI 393 NR-ARFCN 650000 band 77", RadioEventText.servingDetail(RadioRows.identity(nr(0))!!))
        val blank = ServingCellIdentity(ServingRat.LTE, null, null, null, null, null, null, null, null, null, emptyList())
        assertEquals("", RadioEventText.servingDetail(blank))

        assertEquals("connected, LTE", RadioEventText.dataDetail(dataState(0, DataConnState.CONNECTED, 13)))
        assertEquals("disconnected", RadioEventText.dataDetail(dataState(0, DataConnState.DISCONNECTED, 0)))
        assertEquals("suspended, LTE", RadioEventText.dataDetail(dataState(0, DataConnState.SUSPENDED, 13)))
        assertEquals("disconnecting, NR", RadioEventText.dataDetail(dataState(0, DataConnState.DISCONNECTING, 20)))
        assertEquals("moving between networks, IWLAN", RadioEventText.dataDetail(dataState(0, DataConnState.HANDOVER_IN_PROGRESS, 18)))
        assertEquals("state unknown", RadioEventText.dataDetail(dataState(0, DataConnState.UNKNOWN, 99)))

        assertEquals("override NR_NSA, network LTE", RadioEventText.displayDetail(displayInfo(0, 13, 3)))
        assertEquals("override NONE", RadioEventText.displayDetail(displayInfo(0, 0, 0)))
        assertEquals("network NR", RadioEventText.displayDetail(displayInfo(0, 20, 42)))
        assertEquals("", RadioEventText.displayDetail(displayInfo(0, 19, 42)))
    }
}
