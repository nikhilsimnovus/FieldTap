package com.fieldtap.core.radio

import com.fieldtap.core.radio.RadioFixtures.BOOT0
import com.fieldtap.core.radio.RadioFixtures.WALL0
import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.CellRow
import com.fieldtap.format.CellsCsv
import com.fieldtap.format.Csv
import com.fieldtap.format.KpiRow
import com.fieldtap.format.ServingRat
import com.fieldtap.format.SessionFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServingCellTableTest {
    private val table = ServingCellTable()

    private fun identity(
        rat: ServingRat = ServingRat.LTE,
        mcc: String? = "311",
        mnc: String? = "480",
        tac: Int? = 18_704,
        cellId: Long? = 21_640_193L,
        pci: Int? = 212,
        arfcn: Int? = 66_786,
        band: Int? = 66,
        bandwidthKhz: Int? = 20_000,
        operator: String? = "Verizon",
        additionalPlmns: List<String> = emptyList(),
    ): ServingCellIdentity =
        ServingCellIdentity(rat, mcc, mnc, tac, cellId, pci, arfcn, band, bandwidthKhz, operator, additionalPlmns)

    /** The golden session's NSA leg: Android reports only its PCI, NR-ARFCN and band. */
    private fun nrLeg(pci: Int? = 393): ServingCellIdentity = identity(
        rat = ServingRat.NR,
        mcc = null,
        mnc = null,
        tac = null,
        cellId = null,
        pci = pci,
        arfcn = 650_000,
        band = 77,
        bandwidthKhz = null,
        operator = null,
    )

    private fun write(atMs: Long, identity: ServingCellIdentity, rsrp: Int? = -90) {
        table.onKpiWritten(
            KpiCandidate(
                row = KpiRow(
                    timeEpochMs = WALL0 + atMs,
                    rat = identity.rat,
                    pci = identity.pci,
                    rsrpDbm = rsrp,
                    rsrqDb = null,
                    sinrDb = null,
                    ageMs = 500,
                    source = CellInfoSource.REQUEST,
                ),
                identity = identity,
                measurementElapsedMs = BOOT0 + atMs,
            ),
        )
    }

    @Test
    fun anLteFddCellCountsItsSamplesAndRsrpExtremes() {
        write(400, identity(), rsrp = -84)
        write(2_400, identity(), rsrp = -102)
        write(4_400, identity(), rsrp = -83)
        assertEquals(
            listOf(
                CellRow(
                    firstSeenUtcMs = WALL0 + 400,
                    rat = ServingRat.LTE,
                    mcc = "311",
                    mnc = "480",
                    tac = 18_704,
                    cellId = 21_640_193L,
                    pci = 212,
                    band = 66,
                    dlEarfcn = 66_786,
                    ulEarfcn = 132_322,
                    dlBwMhz = 20.0,
                    ulBwMhz = 20.0,
                    plausible = true,
                    operator = "Verizon",
                    additionalPlmns = emptyList(),
                    samples = 3,
                    rsrpMin = -102,
                    rsrpMax = -83,
                ),
            ),
            table.rows(),
        )
        assertEquals(84_532L, table.rows().single().enbId)
        assertEquals(1, table.rows().single().sector)
        assertEquals(mapOf("311480" to 3), table.plmns())
    }

    @Test
    fun anNsaLegHasOnlyWhatAndroidReportsAndCountsTowardNoPlmn() {
        write(8_400, nrLeg(), rsrp = -90)
        write(10_400, nrLeg(), rsrp = -110)
        assertEquals(
            listOf(
                CellRow(
                    firstSeenUtcMs = WALL0 + 8_400,
                    rat = ServingRat.NR,
                    mcc = null,
                    mnc = null,
                    tac = null,
                    cellId = null,
                    pci = 393,
                    band = 77,
                    dlEarfcn = 650_000,
                    ulEarfcn = null,
                    dlBwMhz = null,
                    ulBwMhz = null,
                    plausible = true,
                    operator = null,
                    additionalPlmns = emptyList(),
                    samples = 2,
                    rsrpMin = -110,
                    rsrpMax = -90,
                ),
            ),
            table.rows(),
        )
        assertTrue(table.plmns().isEmpty())
    }

    @Test
    fun aTddBandHasNoUplinkColumns() {
        write(400, identity(band = 41, arfcn = 40_000, pci = 10))
        val row = table.rows().single()
        assertNull(row.ulEarfcn)
        assertNull(row.ulBwMhz)
        assertEquals(20.0, row.dlBwMhz!!, 0.0)
    }

    @Test
    fun aLegWithoutPciOrArfcnIsStillARowThatIsNotPlausible() {
        write(400, nrLeg(pci = null))
        write(600, identity(arfcn = null))
        val rows = table.rows()

        assertEquals(listOf(null, 212), rows.map { it.pci })
        assertEquals(listOf(650_000, null), rows.map { it.dlEarfcn })
        assertEquals(listOf(false, false), rows.map { it.plausible })
        assertNull(rows[1].ulEarfcn)
        assertNull(rows[1].ulBwMhz)
        assertEquals(mapOf("311480" to 1), table.plmns())

        // cells.csv writes the missing value blank and plausible False, the pair fieldtap validate accepts.
        val header = SessionFile.CELLS.header
        val nrRecord = Csv.parseRecord(CellsCsv.encode(rows[0]).trimEnd('\r', '\n'))
        assertEquals("", nrRecord[header.indexOf("pci")])
        assertEquals("650000", nrRecord[header.indexOf("dl_earfcn")])
        assertEquals("False", nrRecord[header.indexOf("plausible")])
        val lteRecord = Csv.parseRecord(CellsCsv.encode(rows[1]).trimEnd('\r', '\n'))
        assertEquals("212", lteRecord[header.indexOf("pci")])
        assertEquals("", lteRecord[header.indexOf("dl_earfcn")])
        assertEquals("False", lteRecord[header.indexOf("plausible")])
    }

    @Test
    fun legsWithoutPciAreDistinctFromLegsWithOne() {
        write(400, nrLeg(pci = null))
        write(2_400, nrLeg())
        write(4_400, nrLeg(pci = null))
        assertEquals(listOf(null, 393), table.rows().map { it.pci })
        assertEquals(listOf(2, 1), table.rows().map { it.samples })
    }

    @Test
    fun blanksAreFilledByLaterRowsButKnownValuesAreKept() {
        write(400, identity(tac = null, operator = null, band = null, bandwidthKhz = null))
        write(2_400, identity(tac = 1, operator = "A", band = 66, bandwidthKhz = 20_000, additionalPlmns = listOf("311490")))
        write(4_400, identity(tac = 2, operator = "B", band = 2, bandwidthKhz = 5_000, additionalPlmns = listOf("310260")))
        val row = table.rows().single()
        assertEquals(WALL0 + 400, row.firstSeenUtcMs)
        assertEquals(1, row.tac)
        assertEquals("A", row.operator)
        assertEquals(66, row.band)
        assertEquals(20.0, row.dlBwMhz!!, 0.0)
        assertEquals(132_322, row.ulEarfcn)
        assertEquals(listOf("311490"), row.additionalPlmns)
        assertEquals(3, row.samples)
    }

    @Test
    fun aFillNeverCompletesAPlmnTheCellWasKeyedWithout() {
        write(400, identity(mnc = null))
        write(2_400, identity(mcc = null, mnc = "480"))
        val row = table.rows().single()
        assertEquals("311", row.mcc)
        assertNull(row.mnc)
        assertNull(row.plmn)
        assertEquals(2, row.samples)
        assertTrue(table.plmns().isEmpty())
    }

    @Test
    fun aFillThatKeepsThePlmnBlankIsMade() {
        write(400, identity(mcc = null, mnc = null))
        write(2_400, identity(mcc = "311", mnc = null))
        assertEquals("311", table.rows().single().mcc)
    }

    @Test
    fun plmnsKeepTheOrderTheyWereFirstSeen() {
        write(400, identity(mcc = "310", mnc = "260", cellId = 1L))
        write(600, identity())
        write(800, identity(mcc = "310", mnc = "260", cellId = 1L))
        assertEquals(listOf("310260", "311480"), table.plmns().keys.toList())
        assertEquals(mapOf("310260" to 2, "311480" to 1), table.plmns())
    }

    @Test
    fun rowsWithoutRsrpLeaveTheExtremesBlank() {
        write(400, identity(), rsrp = null)
        assertNull(table.rows().single().rsrpMin)
        assertNull(table.rows().single().rsrpMax)
        write(600, identity(), rsrp = -95)
        assertEquals(-95, table.rows().single().rsrpMin)
        assertEquals(-95, table.rows().single().rsrpMax)
        assertEquals(2, table.rows().single().samples)
    }

    @Test
    fun cellsAreDistinctByKeyInTheOrderFirstWritten() {
        write(400, identity())
        write(8_400, nrLeg())
        write(64_400, identity(pci = 213, cellId = 21_640_194L))
        write(66_400, identity())
        assertEquals(listOf(212, 393, 213), table.rows().map { it.pci })
        assertEquals(listOf(2, 1, 1), table.rows().map { it.samples })
        assertEquals(listOf(WALL0 + 400, WALL0 + 8_400, WALL0 + 64_400), table.rows().map { it.firstSeenUtcMs })
    }

    @Test
    fun plmnsAndRowsAreSnapshots() {
        val plmns = table.plmns()
        val rows = table.rows()
        write(400, identity())
        assertTrue(plmns.isEmpty())
        assertTrue(rows.isEmpty())
    }
}
