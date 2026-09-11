package com.fieldtap.core.probe

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.CellInfoRequestFailed
import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.input.DisplayInfoSnapshot
import com.fieldtap.core.input.FixSample
import com.fieldtap.core.input.GnssSnapshot
import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.ListenerReport
import com.fieldtap.core.input.LocationAvailability
import com.fieldtap.core.input.RadioListener
import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.core.input.SignalSnapshot
import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.FixProvider
import com.fieldtap.format.HandsetMeta
import com.fieldtap.format.Rat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeRecorderTest {

    @Test
    fun aRecorderThatSawNothingNamesEveryListenerAsUnregistered() {
        val report = ProbeRecorder().report(REPORT_ARGS)

        assertEquals(RadioListener.entries.toList(), report.listeners.keys.toList())
        assertTrue(report.listeners.values.all { it == ListenerOutcome.UNREGISTERED })
        assertEquals(0, report.cellInfo.requests)
        assertEquals(0, report.serviceState.snapshots)
        assertTrue(report.displayOverridesSeen.isEmpty())
    }

    @Test
    fun theReportCarriesTheRunsMetadataUnchanged() {
        val handset = HandsetMeta(manufacturer = "Google", model = "sdk_gphone64_x86_64", networkType = "NR")
        val permissions = linkedMapOf(
            "android.permission.ACCESS_FINE_LOCATION" to true,
            "android.permission.READ_PHONE_STATE" to false,
        )

        val report = ProbeRecorder().report(
            createdUtcMs = 1_789_050_630_000L,
            durationMs = 30_004L,
            appVersion = "0.1.0",
            versionCode = 1L,
            sdkInt = 36,
            handset = handset,
            permissions = permissions,
        )

        assertEquals(1_789_050_630_000L, report.createdUtcMs)
        assertEquals(30_004L, report.durationMs)
        assertEquals("0.1.0", report.appVersion)
        assertEquals(1L, report.versionCode)
        assertEquals(36, report.sdkInt)
        assertEquals(handset, report.handset)
        assertEquals(permissions.toList(), report.permissions.toList())
    }

    @Test
    fun theNewestOutcomeOfAListenerWins() {
        val recorder = ProbeRecorder()
        recorder.record(listenerReport(RadioListener.CELL_INFO_PUSH, ListenerOutcome.MISSING_PERMISSION))
        recorder.record(listenerReport(RadioListener.CELL_INFO_PUSH, ListenerOutcome.REGISTERED))
        recorder.record(listenerReport(RadioListener.BARRING_INFO, ListenerOutcome.REFUSED_BY_PLATFORM))

        val listeners = recorder.report(REPORT_ARGS).listeners
        assertEquals(ListenerOutcome.REGISTERED, listeners[RadioListener.CELL_INFO_PUSH])
        assertEquals(ListenerOutcome.REFUSED_BY_PLATFORM, listeners[RadioListener.BARRING_INFO])
        assertEquals(ListenerOutcome.UNREGISTERED, listeners[RadioListener.SERVICE_STATE])
    }

    @Test
    fun requestsAnswersAndFailuresReachTheCellInfoProbe() {
        val recorder = ProbeRecorder()
        repeat(3) { recorder.onRequest() }
        recorder.record(answer(lte(timestampMs = 56_019)))
        recorder.record(answer(lte(timestampMs = 66_013)))
        recorder.record(CellInfoRequestFailed(errorCode = 1, detail = "timeout", observedWallMs = WALL, observedElapsedMs = ELAPSED))

        val cellInfo = recorder.report(REPORT_ARGS).cellInfo
        assertEquals(3, cellInfo.requests)
        assertEquals(2, cellInfo.answers)
        assertEquals(1, cellInfo.errors)
        assertEquals(true, cellInfo.timestampsAdvance)
        assertEquals(9_994L, cellInfo.minFreshIntervalMs)
        assertEquals(2, recorder.answers())
    }

    @Test
    fun serviceStatesReachTheServiceStateProbe() {
        val recorder = ProbeRecorder()
        recorder.record(serviceState(ServiceRegState.IN_SERVICE, operatorNumeric = "310260"))
        recorder.record(serviceState(ServiceRegState.OUT_OF_SERVICE, operatorNumeric = null, emergencyOnly = true))

        val probe = recorder.report(REPORT_ARGS).serviceState
        assertEquals(ServiceStateProbe(2, true, true, listOf("IN_SERVICE", "OUT_OF_SERVICE")), probe)
    }

    @Test
    fun displayOverridesAreDistinctInFirstSeenOrderAndTheNewestNetworkTypeIsKept() {
        val recorder = ProbeRecorder()
        assertNull(recorder.lastDisplayNetworkType())

        recorder.record(DisplayInfoSnapshot(networkType = 13, overrideNetworkType = 0, observedWallMs = WALL, observedElapsedMs = ELAPSED))
        recorder.record(DisplayInfoSnapshot(networkType = 13, overrideNetworkType = 3, observedWallMs = WALL, observedElapsedMs = ELAPSED))
        recorder.record(DisplayInfoSnapshot(networkType = 20, overrideNetworkType = 0, observedWallMs = WALL, observedElapsedMs = ELAPSED))

        assertEquals(listOf("NONE", "NR_NSA"), recorder.report(REPORT_ARGS).displayOverridesSeen)
        assertEquals(20, recorder.lastDisplayNetworkType())
    }

    @Test
    fun signalAndLocationInputsChangeNothing() {
        val recorder = ProbeRecorder()
        val before = recorder.report(REPORT_ARGS)

        recorder.record(SignalSnapshot(-90, -10, 12, null, null, null, 3, 56_019L, WALL, ELAPSED))
        recorder.record(
            FixSample(ELAPSED, WALL, 47.62, -122.35, 4.0, null, null, FixProvider.GPS, false, WALL, ELAPSED),
        )
        recorder.record(GnssSnapshot(12, 8, WALL, ELAPSED))
        recorder.record(LocationAvailability(true, true, setOf(FixProvider.GPS), WALL, ELAPSED))

        assertEquals(before, recorder.report(REPORT_ARGS))
        assertEquals(0, recorder.answers())
    }

    @Test
    fun notesComeFromCellInfoThenServiceStateThenListeners() {
        val recorder = ProbeRecorder()
        for (listener in RadioListener.entries) {
            val outcome = when {
                listener in ProbeNotes.RESTRICTED -> ListenerOutcome.REFUSED_BY_PLATFORM
                listener == RadioListener.CELL_INFO_PUSH -> ListenerOutcome.MISSING_PERMISSION
                else -> ListenerOutcome.REGISTERED
            }
            recorder.record(listenerReport(listener, outcome))
        }
        recorder.onRequest()
        recorder.record(answer(lte(timestampMs = 56_019)))

        assertEquals(
            listOf(
                "No neighbour cells were reported.",
                "No service-state callback arrived.",
                "CellInfoListener is off: it needs the Phone permission (Instant cell updates) and precise location.",
            ),
            recorder.report(REPORT_ARGS).notes,
        )
    }

    @Test
    fun anOrdinaryPhoneOnTheEmulatorGivesOnlyTheNeighbourNote() {
        val recorder = ProbeRecorder()
        for (listener in RadioListener.entries) {
            val outcome = if (listener in ProbeNotes.RESTRICTED) ListenerOutcome.REFUSED_BY_PLATFORM else ListenerOutcome.REGISTERED
            recorder.record(listenerReport(listener, outcome))
        }
        recorder.record(serviceState(ServiceRegState.IN_SERVICE, operatorNumeric = "310260"))
        for (timestamp in listOf(56_019L, 56_019L, 66_013L)) {
            recorder.onRequest()
            recorder.record(answer(lte(timestampMs = timestamp)))
        }

        val report = recorder.report(REPORT_ARGS)
        assertEquals(listOf("No neighbour cells were reported."), report.notes)
        assertEquals(listOf("lte"), report.cellInfo.servingRats)
    }

    @Test
    fun reportingTwiceGivesTheSameReport() {
        val recorder = ProbeRecorder()
        recorder.onRequest()
        recorder.record(answer(lte(timestampMs = 56_019)))

        assertEquals(recorder.report(REPORT_ARGS), recorder.report(REPORT_ARGS))
    }

    @Test
    fun feedsFromTwoThreadsAreAllCounted() {
        val recorder = ProbeRecorder()
        val cell = lte(timestampMs = 56_019)
        val requester = Thread { repeat(COUNT) { recorder.onRequest() } }
        val collector = Thread { repeat(COUNT) { recorder.record(answer(cell)) } }

        requester.start()
        collector.start()
        requester.join()
        collector.join()

        val cellInfo = recorder.report(REPORT_ARGS).cellInfo
        assertEquals(COUNT, cellInfo.requests)
        assertEquals(COUNT, cellInfo.answers)
        assertEquals(COUNT, recorder.answers())
    }

    private fun ProbeRecorder.report(args: ReportArgs): ProbeReport = report(
        createdUtcMs = args.createdUtcMs,
        durationMs = args.durationMs,
        appVersion = args.appVersion,
        versionCode = args.versionCode,
        sdkInt = args.sdkInt,
        handset = args.handset,
        permissions = args.permissions,
    )

    private data class ReportArgs(
        val createdUtcMs: Long,
        val durationMs: Long,
        val appVersion: String,
        val versionCode: Long,
        val sdkInt: Int,
        val handset: HandsetMeta,
        val permissions: Map<String, Boolean>,
    )

    private fun answer(vararg cells: CellSnapshot): CellInfoAnswer = CellInfoAnswer(
        source = CellInfoSource.REQUEST,
        cells = cells.toList(),
        subId = 1,
        conditions = DeviceConditions(screenOn = true, charging = false, wifiConnected = true),
        observedWallMs = WALL,
        observedElapsedMs = ELAPSED,
    )

    private fun lte(timestampMs: Long): CellSnapshot = CellSnapshot(
        rat = Rat.LTE,
        registered = true,
        connectionStatus = CellSnapshot.CONNECTION_PRIMARY_SERVING,
        timestampMs = timestampMs,
        mcc = "310",
        mnc = "260",
        pci = 0,
        arfcn = 103,
        bands = listOf(42),
        tac = 8514,
        cellId = 47_108L,
        rsrp = -64,
    )

    private fun serviceState(state: ServiceRegState, operatorNumeric: String?, emergencyOnly: Boolean = false) =
        ServiceStateSnapshot(
            state = state,
            emergencyOnly = emergencyOnly,
            operatorNumeric = operatorNumeric,
            operatorAlphaLong = null,
            roaming = null,
            observedWallMs = WALL,
            observedElapsedMs = ELAPSED,
        )

    private fun listenerReport(listener: RadioListener, outcome: ListenerOutcome) =
        ListenerReport(listener, outcome, detail = null, observedWallMs = WALL, observedElapsedMs = ELAPSED)

    private companion object {
        const val WALL: Long = 1_789_050_600_000L
        const val ELAPSED: Long = 80_000L
        const val COUNT: Int = 10_000

        private val REPORT_ARGS = ReportArgs(
            createdUtcMs = WALL,
            durationMs = 30_000L,
            appVersion = "0.1.0",
            versionCode = 1L,
            sdkInt = 36,
            handset = HandsetMeta(manufacturer = "Google", model = "Pixel 8"),
            permissions = mapOf("android.permission.ACCESS_FINE_LOCATION" to true),
        )
    }
}
