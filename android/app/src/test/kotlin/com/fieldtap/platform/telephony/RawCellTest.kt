package com.fieldtap.platform.telephony

import android.telephony.CellInfo
import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.format.Rat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCellTest {

    @Test
    fun androidsUnavailableValuesAreTheDefaults() {
        assertEquals(CellInfo.UNAVAILABLE, RawCell(Rat.LTE, true, 1, 0).rsrp)
        assertEquals(CellInfo.UNAVAILABLE_LONG, RawCell(Rat.NR, true, 1, 0).cellId)
    }

    @Test
    fun everySentinelBecomesNull() {
        val snapshot = RawCell(
            rat = Rat.LTE,
            registered = false,
            connectionStatus = CellInfo.CONNECTION_UNKNOWN,
            timestampMs = 56_019,
            mcc = "",
            mnc = " ",
            operatorLong = "",
            operatorShort = null,
            bands = listOf(Int.MAX_VALUE),
            additionalPlmns = setOf(""),
        ).toSnapshot()

        assertEquals(
            CellSnapshot(rat = Rat.LTE, registered = false, connectionStatus = null, timestampMs = 56_019),
            snapshot,
        )
    }

    @Test
    fun theEmulatorsLteCellKeepsItsValues() {
        // The GitHub Actions emulator after boot: RSRQ, RSSNR, CQI and TA are always unavailable.
        val snapshot = RawCell(
            rat = Rat.LTE,
            registered = true,
            connectionStatus = CellInfo.CONNECTION_PRIMARY_SERVING,
            timestampMs = 56_019,
            mcc = "310",
            mnc = "260",
            operatorLong = "T-Mobile",
            operatorShort = "TMOBILE",
            pci = 0,
            arfcn = 103,
            bands = listOf(42),
            tac = 8514,
            cellId = 47_108L,
            bandwidthKhz = 10_000,
            rsrp = -64,
            rsrq = Int.MAX_VALUE,
            sinr = Int.MAX_VALUE,
            rssi = Int.MAX_VALUE,
            level = 4,
            cqi = Int.MAX_VALUE,
            timingAdvance = Int.MAX_VALUE,
        ).toSnapshot()

        assertEquals(
            CellSnapshot(
                rat = Rat.LTE,
                registered = true,
                connectionStatus = 1,
                timestampMs = 56_019,
                mcc = "310",
                mnc = "260",
                operatorLong = "T-Mobile",
                operatorShort = "TMOBILE",
                pci = 0,
                arfcn = 103,
                bands = listOf(42),
                tac = 8514,
                cellId = 47_108L,
                bandwidthKhz = 10_000,
                rsrp = -64,
                level = 4,
            ),
            snapshot,
        )
    }

    @Test
    fun theEmulatorsNrCellKeepsItsLongCellId() {
        val snapshot = RawCell(
            rat = Rat.NR,
            registered = true,
            connectionStatus = 1,
            timestampMs = 76_014,
            mcc = "310",
            mnc = "260",
            pci = 555,
            arfcn = 9_000,
            bands = listOf(41),
            tac = 8514,
            cellId = 100_500L,
            rsrp = -44,
            level = 4,
        ).toSnapshot()

        assertEquals(100_500L, snapshot.cellId)
        assertEquals(555, snapshot.pci)
        assertEquals(9_000, snapshot.arfcn)
        assertEquals(-44, snapshot.rsrp)
        assertNull(snapshot.sinr)
        assertNull(snapshot.csiRsrp)
        assertNull(snapshot.bandwidthKhz)
    }

    @Test
    fun anUnavailableIntCellIdWidenedToLongIsNull() {
        val widened = RawCell(Rat.LTE, true, 1, 0, cellId = Int.MAX_VALUE.toLong()).toSnapshot()
        val nrUnavailable = RawCell(Rat.NR, true, 1, 0, cellId = Long.MAX_VALUE).toSnapshot()

        assertNull(widened.cellId)
        assertNull(nrUnavailable.cellId)
    }

    @Test
    fun integerMinValueIsAlsoUnavailable() {
        val snapshot = RawCell(Rat.LTE, true, 1, 0, rsrp = Int.MIN_VALUE, tac = Int.MIN_VALUE).toSnapshot()

        assertNull(snapshot.rsrp)
        assertNull(snapshot.tac)
    }

    @Test
    fun leadingZerosAndBandOrderAreKept() {
        val snapshot = RawCell(
            rat = Rat.NR,
            registered = true,
            connectionStatus = 1,
            timestampMs = 0,
            mcc = "001",
            mnc = "01",
            bands = listOf(78, Int.MAX_VALUE, 41),
        ).toSnapshot()

        assertEquals("001", snapshot.mcc)
        assertEquals("01", snapshot.mnc)
        assertEquals(listOf(78, 41), snapshot.bands)
    }

    @Test
    fun additionalPlmnsAreSortedWithoutBlanks() {
        val snapshot = RawCell(Rat.LTE, true, 1, 0, additionalPlmns = linkedSetOf("311480", "", "310260")).toSnapshot()

        assertEquals(listOf("310260", "311480"), snapshot.additionalPlmns)
    }

    @Test
    fun signalValuesOfAnyRatPassThrough() {
        val gsm = RawCell(Rat.GSM, false, 0, 0, arfcn = 128, tac = 1000, cellId = 20_001L, rssi = -85, level = 2).toSnapshot()

        assertEquals(128, gsm.arfcn)
        assertEquals(1000, gsm.tac)
        assertEquals(20_001L, gsm.cellId)
        assertEquals(-85, gsm.rssi)
        assertEquals(0, gsm.connectionStatus)
        assertTrue(gsm.bands.isEmpty())
    }
}
