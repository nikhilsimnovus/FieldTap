package com.fieldtap.core.radio

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.DataConnState
import com.fieldtap.core.input.DataStateSnapshot
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.input.DisplayInfoSnapshot
import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.Rat

/**
 * Builders for radio inputs in tests. Every time is an offset in milliseconds: an answer or snapshot
 * at `atMs` is observed at [WALL0] + atMs on the wall clock and [BOOT0] + atMs on elapsedRealtime, and a
 * cell measured at `measuredAtMs` has `timestampMs` [BOOT0] + measuredAtMs. Both clocks move together,
 * so a cell's age is `atMs - measuredAtMs` and its measurement time [WALL0] + measuredAtMs.
 * The default cells are the golden session's: LTE sector 1 of eNB 84532 and its NSA NR leg.
 */
internal object RadioFixtures {
    const val WALL0: Long = 1_789_050_600_000L
    const val BOOT0: Long = 25_323_456L

    /** Screen on, on battery, Wi-Fi off: Android's 2 s interval. */
    val SHORT: DeviceConditions = DeviceConditions(screenOn = true, charging = false, wifiConnected = false)

    /** Screen off, on battery: Android's 10 s interval. */
    val POCKET: DeviceConditions = DeviceConditions(screenOn = false, charging = false, wifiConnected = false)

    fun lte(
        measuredAtMs: Long,
        pci: Int? = 212,
        earfcn: Int? = 66_786,
        status: Int? = CellSnapshot.CONNECTION_PRIMARY_SERVING,
        registered: Boolean = status == CellSnapshot.CONNECTION_PRIMARY_SERVING,
        cellId: Long? = 21_640_193L,
        mcc: String? = "311",
        mnc: String? = "480",
        tac: Int? = 18_704,
        bands: List<Int> = listOf(66),
        bandwidthKhz: Int? = 20_000,
        operator: String? = "Verizon",
        rsrp: Int? = -90,
        rsrq: Int? = -9,
        sinr: Int? = 12,
    ): CellSnapshot = CellSnapshot(
        rat = Rat.LTE,
        registered = registered,
        connectionStatus = status,
        timestampMs = BOOT0 + measuredAtMs,
        mcc = mcc,
        mnc = mnc,
        operatorLong = operator,
        pci = pci,
        arfcn = earfcn,
        bands = bands,
        tac = tac,
        cellId = cellId,
        bandwidthKhz = bandwidthKhz,
        rsrp = rsrp,
        rsrq = rsrq,
        sinr = sinr,
    )

    fun nr(
        measuredAtMs: Long,
        pci: Int? = 393,
        arfcn: Int? = 650_000,
        status: Int? = CellSnapshot.CONNECTION_SECONDARY_SERVING,
        registered: Boolean = status == CellSnapshot.CONNECTION_PRIMARY_SERVING,
        cellId: Long? = null,
        mcc: String? = null,
        mnc: String? = null,
        tac: Int? = null,
        bands: List<Int> = listOf(77),
        rsrp: Int? = -95,
        rsrq: Int? = -10,
        sinr: Int? = 9,
        csiRsrp: Int? = null,
        csiRsrq: Int? = null,
        csiSinr: Int? = null,
    ): CellSnapshot = CellSnapshot(
        rat = Rat.NR,
        registered = registered,
        connectionStatus = status,
        timestampMs = BOOT0 + measuredAtMs,
        mcc = mcc,
        mnc = mnc,
        pci = pci,
        arfcn = arfcn,
        bands = bands,
        tac = tac,
        cellId = cellId,
        rsrp = rsrp,
        rsrq = rsrq,
        sinr = sinr,
        csiRsrp = csiRsrp,
        csiRsrq = csiRsrq,
        csiSinr = csiSinr,
    )

    fun gsm(measuredAtMs: Long, status: Int?, registered: Boolean = true): CellSnapshot = CellSnapshot(
        rat = Rat.GSM,
        registered = registered,
        connectionStatus = status,
        timestampMs = BOOT0 + measuredAtMs,
        mcc = "311",
        mnc = "480",
        arfcn = 128,
        tac = 4_101,
        cellId = 30_211L,
        rssi = -81,
    )

    fun answer(
        atMs: Long,
        cells: List<CellSnapshot>,
        conditions: DeviceConditions = SHORT,
        source: CellInfoSource = CellInfoSource.REQUEST,
        subId: Int? = 1,
    ): CellInfoAnswer = CellInfoAnswer(
        source = source,
        cells = cells,
        subId = subId,
        conditions = conditions,
        observedWallMs = WALL0 + atMs,
        observedElapsedMs = BOOT0 + atMs,
    )

    fun serviceState(atMs: Long, state: ServiceRegState, emergencyOnly: Boolean = false): ServiceStateSnapshot =
        ServiceStateSnapshot(
            state = state,
            emergencyOnly = emergencyOnly,
            operatorNumeric = null,
            operatorAlphaLong = null,
            roaming = null,
            observedWallMs = WALL0 + atMs,
            observedElapsedMs = BOOT0 + atMs,
        )

    fun dataState(atMs: Long, state: DataConnState, networkType: Int): DataStateSnapshot =
        DataStateSnapshot(state, networkType, WALL0 + atMs, BOOT0 + atMs)

    fun displayInfo(atMs: Long, networkType: Int, overrideNetworkType: Int): DisplayInfoSnapshot =
        DisplayInfoSnapshot(networkType, overrideNetworkType, WALL0 + atMs, BOOT0 + atMs)
}
