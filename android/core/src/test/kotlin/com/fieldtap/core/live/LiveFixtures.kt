package com.fieldtap.core.live

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.Rat

/**
 * Builders for the Live reducer tests. Times are offsets in milliseconds: an input at `atMs` is observed at
 * [WALL0] + atMs and [BOOT0] + atMs, and a cell measured at `measuredAtMs` has `timestampMs` [BOOT0] +
 * measuredAtMs.
 */
internal object LiveFixtures {
    const val WALL0: Long = 1_789_050_600_000L
    const val BOOT0: Long = 25_323_456L

    val SHORT: DeviceConditions = DeviceConditions(screenOn = true, charging = false, wifiConnected = false)
    val POCKET: DeviceConditions = DeviceConditions(screenOn = false, charging = false, wifiConnected = false)

    fun lte(
        measuredAtMs: Long,
        pci: Int = 212,
        earfcn: Int = 66_786,
        status: Int? = CellSnapshot.CONNECTION_PRIMARY_SERVING,
        rsrp: Int? = -90,
        rsrq: Int? = -9,
        sinr: Int? = 12,
        cellId: Long = 21_640_193L,
    ): CellSnapshot = CellSnapshot(
        rat = Rat.LTE,
        registered = status == CellSnapshot.CONNECTION_PRIMARY_SERVING,
        connectionStatus = status,
        timestampMs = BOOT0 + measuredAtMs,
        mcc = "311",
        mnc = "480",
        operatorLong = "Verizon",
        pci = pci,
        arfcn = earfcn,
        bands = listOf(66),
        tac = 18_704,
        cellId = cellId,
        rsrp = rsrp,
        rsrq = rsrq,
        sinr = sinr,
    )

    fun nr(
        measuredAtMs: Long,
        pci: Int = 393,
        arfcn: Int = 650_000,
        status: Int? = CellSnapshot.CONNECTION_SECONDARY_SERVING,
        rsrp: Int? = -95,
        sinr: Int? = 9,
    ): CellSnapshot = CellSnapshot(
        rat = Rat.NR,
        registered = status == CellSnapshot.CONNECTION_PRIMARY_SERVING,
        connectionStatus = status,
        timestampMs = BOOT0 + measuredAtMs,
        pci = pci,
        arfcn = arfcn,
        bands = listOf(77),
        rsrp = rsrp,
        rsrq = -10,
        sinr = sinr,
    )

    fun gsm(measuredAtMs: Long): CellSnapshot = CellSnapshot(
        rat = Rat.GSM,
        registered = false,
        connectionStatus = CellSnapshot.CONNECTION_NONE,
        timestampMs = BOOT0 + measuredAtMs,
        mcc = "311",
        mnc = "480",
        arfcn = 128,
        cellId = 30_211L,
        rssi = -81,
    )

    fun answer(
        atMs: Long,
        cells: List<CellSnapshot>,
        conditions: DeviceConditions = SHORT,
    ): CellInfoAnswer = CellInfoAnswer(
        source = CellInfoSource.REQUEST,
        cells = cells,
        subId = 1,
        conditions = conditions,
        observedWallMs = WALL0 + atMs,
        observedElapsedMs = BOOT0 + atMs,
    )
}
