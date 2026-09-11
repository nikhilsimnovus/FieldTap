package com.fieldtap.ui.live

import com.fieldtap.app.SessionStatus
import com.fieldtap.core.input.DataConnState
import com.fieldtap.core.input.DataStateSnapshot
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.input.DisplayInfoSnapshot
import com.fieldtap.core.input.FixSample
import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.RadioListener
import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.core.input.SignalSnapshot
import com.fieldtap.core.live.LiveCell
import com.fieldtap.core.session.StartRequest
import com.fieldtap.format.FixProvider
import com.fieldtap.format.Rat
import com.fieldtap.ui.common.TestData
import com.fieldtap.ui.components.SessionButtonState
import com.fieldtap.ui.theme.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePresentationTest {

    @Test
    fun cadenceReasonNamesWhatSetsTheInterval() {
        assertNull(LivePresentation.cadenceReason(null))
        for (charging in listOf(false, true)) {
            for (wifi in listOf(false, true)) {
                assertEquals(CadenceReason.SCREEN_OFF, LivePresentation.cadenceReason(DeviceConditions(screenOn = false, charging = charging, wifiConnected = wifi)))
            }
        }
        assertEquals(CadenceReason.SCREEN_ON_WIFI_OFF, LivePresentation.cadenceReason(DeviceConditions(screenOn = true, charging = false, wifiConnected = false)))
        assertEquals(CadenceReason.SCREEN_ON_WIFI_OFF, LivePresentation.cadenceReason(DeviceConditions(screenOn = true, charging = true, wifiConnected = false)))
        assertEquals(CadenceReason.CHARGING_WITH_WIFI, LivePresentation.cadenceReason(DeviceConditions(screenOn = true, charging = true, wifiConnected = true)))
        assertEquals(CadenceReason.WIFI_ON_BATTERY, LivePresentation.cadenceReason(DeviceConditions(screenOn = true, charging = false, wifiConnected = true)))
    }

    @Test
    fun walkModePromptsOnlyWhenWifiForcesTheLongInterval() {
        val wifiOnBattery = DeviceConditions(screenOn = true, charging = false, wifiConnected = true)
        assertTrue(LivePresentation.showWalkModeWifiPrompt(walkMode = true, conditions = wifiOnBattery))
        assertFalse(LivePresentation.showWalkModeWifiPrompt(walkMode = false, conditions = wifiOnBattery))
        assertFalse(LivePresentation.showWalkModeWifiPrompt(walkMode = true, conditions = wifiOnBattery.copy(charging = true)))
        assertFalse(LivePresentation.showWalkModeWifiPrompt(walkMode = true, conditions = wifiOnBattery.copy(wifiConnected = false)))
        assertFalse(LivePresentation.showWalkModeWifiPrompt(walkMode = true, conditions = null))
    }

    @Test
    fun serviceChipTakesEmergencyOnlyFromServiceState() {
        assertEquals(ServiceChip.WAITING, LivePresentation.serviceChip(null))
        assertEquals(ServiceChip.IN_SERVICE, LivePresentation.serviceChip(service(ServiceRegState.IN_SERVICE)))
        assertEquals(ServiceChip.ROAMING, LivePresentation.serviceChip(service(ServiceRegState.IN_SERVICE, roaming = true)))
        assertEquals(ServiceChip.EMERGENCY_ONLY, LivePresentation.serviceChip(service(ServiceRegState.OUT_OF_SERVICE, emergencyOnly = true)))
        assertEquals(ServiceChip.EMERGENCY_ONLY, LivePresentation.serviceChip(service(ServiceRegState.EMERGENCY_ONLY)))
        assertEquals(ServiceChip.NO_SERVICE, LivePresentation.serviceChip(service(ServiceRegState.OUT_OF_SERVICE)))
        assertEquals(ServiceChip.RADIO_OFF, LivePresentation.serviceChip(service(ServiceRegState.POWER_OFF)))
        assertEquals(ServiceChip.UNKNOWN, LivePresentation.serviceChip(service(ServiceRegState.UNKNOWN)))
        assertEquals(StatusTone.ERROR, ServiceChip.NO_SERVICE.tone)
        assertEquals(StatusTone.WARNING, ServiceChip.EMERGENCY_ONLY.tone)
    }

    @Test
    fun dataChipAndNetworkName() {
        assertEquals(DataChip.WAITING, LivePresentation.dataChip(null))
        assertEquals(DataChip.CONNECTED, LivePresentation.dataChip(data(DataConnState.CONNECTED)))
        assertEquals(DataChip.CONNECTING, LivePresentation.dataChip(data(DataConnState.HANDOVER_IN_PROGRESS)))
        assertEquals(DataChip.DISCONNECTED, LivePresentation.dataChip(data(DataConnState.DISCONNECTING)))
        assertEquals(DataChip.SUSPENDED, LivePresentation.dataChip(data(DataConnState.SUSPENDED)))
        assertEquals(DataChip.UNKNOWN, LivePresentation.dataChip(data(DataConnState.UNKNOWN)))

        assertEquals("LTE", LivePresentation.dataNetworkName(data(DataConnState.CONNECTED, networkType = 13)))
        assertEquals("NR", LivePresentation.dataNetworkName(data(DataConnState.CONNECTED, networkType = 20)))
        assertEquals("TD SCDMA", LivePresentation.dataNetworkName(data(DataConnState.CONNECTED, networkType = 17)))
        assertNull(LivePresentation.dataNetworkName(data(DataConnState.CONNECTED, networkType = 0)))
        assertNull(LivePresentation.dataNetworkName(data(DataConnState.CONNECTED, networkType = 99)))
        assertNull(LivePresentation.dataNetworkName(null))
    }

    @Test
    fun theFiveGIconIsAnIndicator() {
        assertNull(LivePresentation.fiveGIcon(null))
        assertEquals(true, LivePresentation.fiveGIcon(DisplayInfoSnapshot(13, 3, 0, 0)))
        assertEquals(true, LivePresentation.fiveGIcon(DisplayInfoSnapshot(20, 0, 0, 0)))
        assertEquals(false, LivePresentation.fiveGIcon(DisplayInfoSnapshot(13, 1, 0, 0)))
    }

    @Test
    fun gpsIsLostAfterFiveSecondsWithoutAFix() {
        assertEquals(GpsChip.Waiting, LivePresentation.gpsChip(null, 10_000))
        assertEquals(GpsChip.Fix(4.0), LivePresentation.gpsChip(fix(elapsedMs = 5_000), 10_000))
        assertEquals(GpsChip.Lost(5_001), LivePresentation.gpsChip(fix(elapsedMs = 4_999), 10_000))
        assertEquals("a fix newer than now is fresh", GpsChip.Fix(4.0), LivePresentation.gpsChip(fix(elapsedMs = 12_000), 10_000))
        assertEquals(StatusTone.ERROR, GpsChip.Lost(6_000).tone)
    }

    @Test
    fun listenerNotesSkipRegisteredAndProbeOnlyListeners() {
        val notes = LivePresentation.listenerNotes(
            mapOf(
                RadioListener.SIGNAL_STRENGTHS to ListenerOutcome.FAILED,
                RadioListener.CELL_INFO_PUSH to ListenerOutcome.MISSING_PERMISSION,
                RadioListener.SERVICE_STATE to ListenerOutcome.REGISTERED,
                RadioListener.DISPLAY_INFO to ListenerOutcome.UNREGISTERED,
                RadioListener.BARRING_INFO to ListenerOutcome.REFUSED_BY_PLATFORM,
                RadioListener.CELL_INFO_REQUEST to ListenerOutcome.MISSING_PERMISSION,
                RadioListener.DATA_CONNECTION_STATE to ListenerOutcome.REFUSED_BY_PLATFORM,
            ),
        )

        assertEquals(
            listOf(
                ListenerNote(RadioListener.CELL_INFO_REQUEST, ListenerOutcome.MISSING_PERMISSION, StatusTone.ERROR),
                ListenerNote(RadioListener.CELL_INFO_PUSH, ListenerOutcome.MISSING_PERMISSION, StatusTone.INFO),
                ListenerNote(RadioListener.SIGNAL_STRENGTHS, ListenerOutcome.FAILED, StatusTone.WARNING),
                ListenerNote(RadioListener.DATA_CONNECTION_STATE, ListenerOutcome.REFUSED_BY_PLATFORM, StatusTone.WARNING),
            ),
            notes,
        )
    }

    @Test
    fun servingNetworkDistinguishesTheNsaLeg() {
        assertNull(LivePresentation.servingNetwork(null, null))
        assertEquals(ServingNetwork.LTE, LivePresentation.servingNetwork(cell(Rat.LTE), null))
        assertEquals(ServingNetwork.LTE_WITH_NR_LEG, LivePresentation.servingNetwork(cell(Rat.LTE), cell(Rat.NR)))
        assertEquals(ServingNetwork.NR_STANDALONE, LivePresentation.servingNetwork(cell(Rat.NR), null))
        assertEquals(ServingNetwork.OTHER, LivePresentation.servingNetwork(cell(Rat.WCDMA), null))
    }

    @Test
    fun aSignalReportShowsOnlyWhenNewerThanTheServingMeasurement() {
        val lte = cell(Rat.LTE, timestampMs = 1_000)
        assertEquals(SignalReport(-91, 500), LivePresentation.newerSignalReport(lte, signal(lteRsrp = -91, modemTimestampMs = 1_500), nowElapsedMs = 2_000))
        assertNull(LivePresentation.newerSignalReport(lte, signal(lteRsrp = -91, modemTimestampMs = 1_000), nowElapsedMs = 2_000))
        assertNull(LivePresentation.newerSignalReport(lte, signal(lteRsrp = null, modemTimestampMs = 1_500), nowElapsedMs = 2_000))
        assertEquals(
            "without a modem timestamp the observation time counts",
            SignalReport(-91, 200),
            LivePresentation.newerSignalReport(lte, signal(lteRsrp = -91, modemTimestampMs = null, observedElapsedMs = 1_800), nowElapsedMs = 2_000),
        )
        assertEquals(SignalReport(-70, 0), LivePresentation.newerSignalReport(cell(Rat.NR, timestampMs = 1_000), signal(nrSsRsrp = -70, modemTimestampMs = 2_500), nowElapsedMs = 2_000))
        assertNull(LivePresentation.newerSignalReport(cell(Rat.GSM, timestampMs = 1_000), signal(lteRsrp = -91, modemTimestampMs = 1_500), nowElapsedMs = 2_000))
        assertNull(LivePresentation.newerSignalReport(null, signal(lteRsrp = -91, modemTimestampMs = 1_500), nowElapsedMs = 2_000))
    }

    @Test
    fun markNeedsARecordingSessionOutsideAZone() {
        assertFalse(LivePresentation.markAllowed(SessionStatus.Idle))
        assertFalse(LivePresentation.markAllowed(SessionStatus.Starting(StartRequest("Walk"))))
        assertTrue(LivePresentation.markAllowed(SessionStatus.Recording(TestData.snapshot(paused = false))))
        assertFalse(LivePresentation.markAllowed(SessionStatus.Recording(TestData.snapshot(paused = true))))
        assertTrue(LivePresentation.pausedInZone(SessionStatus.Recording(TestData.snapshot(paused = true))))
        assertFalse(LivePresentation.markAllowed(SessionStatus.Stopping(TestData.snapshot())))
    }

    @Test
    fun theButtonIsBusyWhileChecksOrTheStartCallRun() {
        val request = StartRequest("Walk")
        assertEquals(SessionButtonState.IDLE, LivePresentation.buttonState(SessionStatus.Idle, PrestartState.None))
        assertEquals(SessionButtonState.IDLE, LivePresentation.buttonState(SessionStatus.Idle, PrestartState.Review(request, emptyList())))
        assertEquals(SessionButtonState.STARTING, LivePresentation.buttonState(SessionStatus.Idle, PrestartState.Checking(request)))
        assertEquals(SessionButtonState.STARTING, LivePresentation.buttonState(SessionStatus.Idle, PrestartState.Starting(request)))
        assertEquals(SessionButtonState.STARTING, LivePresentation.buttonState(SessionStatus.Starting(request), PrestartState.None))
        assertEquals(SessionButtonState.RECORDING, LivePresentation.buttonState(SessionStatus.Recording(TestData.snapshot()), PrestartState.None))
        assertEquals(SessionButtonState.STOPPING, LivePresentation.buttonState(SessionStatus.Stopping(TestData.snapshot()), PrestartState.None))
    }

    private fun service(state: ServiceRegState, emergencyOnly: Boolean = false, roaming: Boolean? = false) =
        ServiceStateSnapshot(state, emergencyOnly, "311480", "Verizon", roaming, 0, 0)

    private fun data(state: DataConnState, networkType: Int = 13) = DataStateSnapshot(state, networkType, 0, 0)

    private fun fix(elapsedMs: Long) = FixSample(elapsedMs, elapsedMs, 40.0, -74.0, 4.0, null, null, FixProvider.GPS, false, elapsedMs, elapsedMs)

    private fun cell(rat: Rat, timestampMs: Long = 0) = LiveCell(rat, 1, 100, 3, -90, -10, 10, null, null, 1, timestampMs)

    private fun signal(lteRsrp: Int? = null, nrSsRsrp: Int? = null, modemTimestampMs: Long?, observedElapsedMs: Long = 0) =
        SignalSnapshot(lteRsrp, null, null, nrSsRsrp, null, null, 3, modemTimestampMs, 0, observedElapsedMs)
}
