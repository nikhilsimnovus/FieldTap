package com.fieldtap.core.radio

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.radio.RadioFixtures.BOOT0
import com.fieldtap.core.radio.RadioFixtures.POCKET
import com.fieldtap.core.radio.RadioFixtures.SHORT
import com.fieldtap.core.radio.RadioFixtures.WALL0
import com.fieldtap.core.radio.RadioFixtures.answer
import com.fieldtap.core.radio.RadioFixtures.gsm
import com.fieldtap.core.radio.RadioFixtures.lte
import com.fieldtap.core.radio.RadioFixtures.nr
import com.fieldtap.format.CellInfoRow
import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.KpiRow
import com.fieldtap.format.Rat
import com.fieldtap.format.ServingRat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioRowsTest {

    /** Classifies [answer] on a new engine, so every cell in it is fresh. */
    private fun classifyFresh(answer: CellInfoAnswer): ClassifiedAnswer = FreshnessEngine().classify(answer)

    @Test
    fun onTheShortIntervalA2500MsSampleIsKpiAndA2501MsSampleIsNot() {
        val accepted = RadioRows.kpi(classifyFresh(answer(2_500, listOf(lte(0)), conditions = SHORT)))
        assertEquals(1, accepted.size)
        assertEquals(2_500L, accepted.single().row.ageMs)
        assertTrue(RadioRows.kpi(classifyFresh(answer(2_501, listOf(lte(0)), conditions = SHORT))).isEmpty())
    }

    @Test
    fun onTheLongIntervalAn11000MsSampleIsKpiAndAn11001MsSampleIsNot() {
        assertEquals(1, RadioRows.kpi(classifyFresh(answer(11_000, listOf(lte(0)), conditions = POCKET))).size)
        assertTrue(RadioRows.kpi(classifyFresh(answer(11_001, listOf(lte(0)), conditions = POCKET))).isEmpty())

        val wifiScreenOn = DeviceConditions(screenOn = true, charging = false, wifiConnected = true)
        assertEquals(1, RadioRows.kpi(classifyFresh(answer(11_000, listOf(lte(0)), conditions = wifiScreenOn))).size)
        assertTrue(RadioRows.kpi(classifyFresh(answer(11_001, listOf(lte(0)), conditions = wifiScreenOn))).isEmpty())
    }

    @Test
    fun aStaleServingSampleIsNeverAKpiRow() {
        val engine = FreshnessEngine()
        assertEquals(1, RadioRows.kpi(engine.classify(answer(500, listOf(lte(0))))).size)

        val repeat = engine.classify(answer(1_500, listOf(lte(0))))
        assertTrue(RadioRows.kpi(repeat).isEmpty())
        assertTrue(RadioRows.cellInfo(repeat).single().row.stale)
    }

    @Test
    fun nsaGivesAnLteAndAnNrRowWithTheSameTime() {
        val neighbour = lte(0, pci = 213, status = CellSnapshot.CONNECTION_NONE)
        val classified = classifyFresh(answer(500, listOf(lte(0), nr(0), neighbour)))
        val kpi = RadioRows.kpi(classified)
        assertEquals(listOf(ServingRat.LTE, ServingRat.NR), kpi.map { it.row.rat })
        assertEquals(kpi[0].row.timeEpochMs, kpi[1].row.timeEpochMs)
        assertEquals(listOf(212, 393), kpi.map { it.row.pci })
        assertEquals(3, RadioRows.cellInfo(classified).size)
    }

    @Test
    fun aKpiRowCarriesTheMeasurementTimeAgeSourceAndServingIdentity() {
        val cell = lte(0, rsrp = -84, rsrq = -8, sinr = 15)
        val candidate = RadioRows.kpi(classifyFresh(answer(500, listOf(cell), source = CellInfoSource.PUSH))).single()
        assertEquals(
            KpiRow(
                timeEpochMs = WALL0,
                rat = ServingRat.LTE,
                pci = 212,
                rsrpDbm = -84,
                rsrqDb = -8,
                sinrDb = 15,
                ageMs = 500,
                source = CellInfoSource.PUSH,
            ),
            candidate.row,
        )
        assertEquals(BOOT0, candidate.measurementElapsedMs)
        assertEquals(
            ServingCellIdentity(
                rat = ServingRat.LTE,
                mcc = "311",
                mnc = "480",
                tac = 18_704,
                cellId = 21_640_193L,
                pci = 212,
                arfcn = 66_786,
                band = 66,
                bandwidthKhz = 20_000,
                operator = "Verizon",
                additionalPlmns = emptyList(),
            ),
            candidate.identity,
        )
        assertEquals("311480", candidate.identity.plmn)
    }

    @Test
    fun nrKpiValuesAreSsOnlyWhileCellInfoFallsBackToCsi() {
        val csiOnly = nr(
            0,
            status = CellSnapshot.CONNECTION_PRIMARY_SERVING,
            rsrp = null,
            rsrq = null,
            sinr = null,
            csiRsrp = -80,
            csiRsrq = -12,
            csiSinr = 10,
        )
        val classified = classifyFresh(answer(500, listOf(csiOnly)))
        val kpi = RadioRows.kpi(classified).single().row
        assertNull(kpi.rsrpDbm)
        assertNull(kpi.rsrqDb)
        assertNull(kpi.sinrDb)

        val info = RadioRows.cellInfo(classified).single().row
        assertEquals(-80, info.rsrp)
        assertEquals(-12, info.rsrq)
        assertEquals(10, info.sinr)
        assertEquals(-80, info.csiRsrp)
        assertEquals(-12, info.csiRsrq)
        assertEquals(10, info.csiSinr)

        val both = nr(
            0,
            status = CellSnapshot.CONNECTION_PRIMARY_SERVING,
            rsrp = -95,
            rsrq = -10,
            sinr = 9,
            csiRsrp = -80,
            csiRsrq = -12,
            csiSinr = 10,
        )
        val row = RadioRows.cellInfo(classifyFresh(answer(500, listOf(both)))).single().row
        assertEquals(-95, row.rsrp)
        assertEquals(-10, row.rsrq)
        assertEquals(9, row.sinr)
    }

    @Test
    fun anLteCellNeverTakesCsiValues() {
        val cell = lte(0, rsrp = null).copy(csiRsrp = -80)
        val row = RadioRows.cellInfo(classifyFresh(answer(500, listOf(cell)))).single().row
        assertNull(row.rsrp)
        assertEquals(-80, row.csiRsrp)
    }

    @Test
    fun valuesOutsideTheRangesAreBlankNeverClipped() {
        val cell = lte(
            0,
            pci = 504,
            earfcn = 262_144,
            cellId = 268_435_456L,
            tac = 16_777_216,
            bands = listOf(0, 66),
            bandwidthKhz = 400_001,
            rsrp = -42,
            rsrq = 4,
            sinr = 41,
        )
        val classified = classifyFresh(answer(500, listOf(cell)))
        val info = RadioRows.cellInfo(classified).single().row
        assertNull(info.pci)
        assertNull(info.arfcn)
        assertNull(info.cellId)
        assertNull(info.tac)
        assertNull(info.bandwidthKhz)
        assertNull(info.rsrp)
        assertNull(info.rsrq)
        assertNull(info.sinr)
        assertEquals(listOf(66), info.bands)

        val candidate = RadioRows.kpi(classified).single()
        assertNull(candidate.row.pci)
        assertNull(candidate.row.rsrpDbm)
        assertNull(candidate.row.rsrqDb)
        assertNull(candidate.row.sinrDb)
        // The first band is out of range, and the next one is never promoted in its place.
        assertNull(candidate.identity.band)
        assertNull(candidate.identity.arfcn)
        assertNull(candidate.identity.cellId)
        assertNull(candidate.identity.tac)
        assertNull(candidate.identity.bandwidthKhz)

        val edge = lte(0, pci = 503, earfcn = 262_143, rsrp = -43, rsrq = 3, sinr = 40)
        val edgeKpi = RadioRows.kpi(classifyFresh(answer(500, listOf(edge)))).single().row
        assertEquals(503, edgeKpi.pci)
        assertEquals(-43, edgeKpi.rsrpDbm)
        assertEquals(3, edgeKpi.rsrqDb)
        assertEquals(40, edgeKpi.sinrDb)
    }

    @Test
    fun nrRangesAreWiderThanLteRanges() {
        val cell = nr(
            0,
            status = CellSnapshot.CONNECTION_PRIMARY_SERVING,
            pci = 1_007,
            arfcn = 3_279_165,
            rsrp = -29,
            rsrq = 20,
            sinr = 40,
        )
        val classified = classifyFresh(answer(500, listOf(cell)))
        val kpi = RadioRows.kpi(classified).single().row
        assertEquals(1_007, kpi.pci)
        assertEquals(-29, kpi.rsrpDbm)
        assertEquals(20, kpi.rsrqDb)
        assertEquals(40, kpi.sinrDb)
        val info = RadioRows.cellInfo(classified).single().row
        assertEquals(20, info.rsrq)
        assertEquals(40, info.sinr)
        assertEquals(3_279_165, info.arfcn)

        val beyond = nr(0, status = CellSnapshot.CONNECTION_PRIMARY_SERVING, rsrp = -28, rsrq = 21, sinr = 41)
        val beyondClassified = classifyFresh(answer(500, listOf(beyond)))
        val beyondKpi = RadioRows.kpi(beyondClassified).single().row
        assertNull(beyondKpi.rsrpDbm)
        assertNull(beyondKpi.rsrqDb)
        assertNull(beyondKpi.sinrDb)
        val beyondInfo = RadioRows.cellInfo(beyondClassified).single().row
        assertNull(beyondInfo.rsrp)
        assertNull(beyondInfo.rsrq)
        assertNull(beyondInfo.sinr)
    }

    @Test
    fun theOtherCellInfoColumnsAreRangeCheckedToo() {
        val outside = lte(0).copy(rssi = 1, level = 5, cqi = 16, timingAdvance = 3_847, csiRsrp = -30, csiRsrq = -2, csiSinr = 24)
        val outsideRow = RadioRows.cellInfo(classifyFresh(answer(500, listOf(outside)))).single().row
        assertNull(outsideRow.rssi)
        assertNull(outsideRow.level)
        assertNull(outsideRow.cqi)
        assertNull(outsideRow.timingAdvance)
        assertNull(outsideRow.csiRsrp)
        assertNull(outsideRow.csiRsrq)
        assertNull(outsideRow.csiSinr)

        val edge = lte(0).copy(rssi = -150, level = 4, cqi = 15, timingAdvance = 3_846, csiRsrp = -156, csiRsrq = -20, csiSinr = 23)
        val edgeRow = RadioRows.cellInfo(classifyFresh(answer(500, listOf(edge)))).single().row
        assertEquals(-150, edgeRow.rssi)
        assertEquals(4, edgeRow.level)
        assertEquals(15, edgeRow.cqi)
        assertEquals(3_846, edgeRow.timingAdvance)
        assertEquals(-156, edgeRow.csiRsrp)
        assertEquals(-20, edgeRow.csiRsrq)
        assertEquals(23, edgeRow.csiSinr)
    }

    @Test
    fun aGsmPrimaryNeverYieldsAKpiRow() {
        val classified = classifyFresh(answer(500, listOf(gsm(0, status = CellSnapshot.CONNECTION_PRIMARY_SERVING))))
        assertEquals(Rat.GSM, classified.primary!!.cell.rat)
        assertTrue(RadioRows.kpi(classified).isEmpty())

        val row = RadioRows.cellInfo(classified).single().row
        assertEquals(Rat.GSM, row.rat)
        assertEquals(128, row.arfcn)
        assertEquals(30_211L, row.cellId)
        assertEquals(4_101, row.tac)
        assertEquals(-81, row.rssi)
        assertNull(RadioRows.identity(gsm(0, status = null)))
    }

    @Test
    fun aCellInfoRowCopiesTheAnswerAndBlanksImplausibleText() {
        val cell = lte(0, mcc = "31", operator = " ").copy(
            operatorShort = "VZW",
            additionalPlmns = listOf("311490", "3114", "311480", "bogus", "311480"),
            connectionStatus = 7,
        )
        val conditions = DeviceConditions(screenOn = false, charging = true, wifiConnected = true)
        val observed = answer(1_500, listOf(cell), conditions = conditions, source = CellInfoSource.PUSH, subId = -1)
        val candidate = RadioRows.cellInfo(classifyFresh(observed)).single()
        assertEquals(
            CellInfoRow(
                seenUtcMs = WALL0 + 1_500,
                rat = Rat.LTE,
                registered = true,
                mcc = null,
                mnc = "480",
                operator = "VZW",
                pci = 212,
                arfcn = 66_786,
                bands = listOf(66),
                tac = 18_704,
                cellId = 21_640_193L,
                bandwidthKhz = 20_000,
                rsrp = -90,
                rsrq = -9,
                sinr = 12,
                rssi = null,
                level = null,
                additionalPlmns = listOf("311480", "311490"),
                timeEpochMs = WALL0,
                timestampMs = BOOT0,
                ageMs = 1_500,
                stale = false,
                connectionStatus = null,
                source = CellInfoSource.PUSH,
                cqi = null,
                timingAdvance = null,
                csiRsrp = null,
                csiRsrq = null,
                csiSinr = null,
                screenOn = false,
                charging = true,
                wifiConnected = true,
                subId = null,
            ),
            candidate.row,
        )
        assertNull(candidate.row.plmn)
        assertEquals(BOOT0, candidate.measurementElapsedMs)
    }

    @Test
    fun aStaleRowKeepsItsMeasurementTimeWhileSeenAndAgeMoveOn() {
        val engine = FreshnessEngine()
        engine.classify(answer(500, listOf(lte(0))))
        val row = RadioRows.cellInfo(engine.classify(answer(1_500, listOf(lte(0))))).single().row
        assertTrue(row.stale)
        assertEquals(WALL0 + 1_500, row.seenUtcMs)
        assertEquals(WALL0, row.timeEpochMs)
        assertEquals(BOOT0, row.timestampMs)
        assertEquals(1_500L, row.ageMs)
    }

    @Test
    fun anNrIdentityHasNoBandwidthAndNothingGuessed() {
        val identity = RadioRows.identity(nr(0).copy(bandwidthKhz = 100_000))!!
        assertEquals(
            ServingCellIdentity(ServingRat.NR, null, null, null, null, 393, 650_000, 77, null, null, emptyList()),
            identity,
        )
        assertNull(identity.plmn)
    }
}
