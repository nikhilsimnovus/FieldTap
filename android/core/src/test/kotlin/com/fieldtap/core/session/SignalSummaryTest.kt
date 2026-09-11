package com.fieldtap.core.session

import com.fieldtap.format.ServingRat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SignalSummaryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun theGoldenSessionIsSummarisedByItsLteRows() {
        assertTrue("golden kpi.csv missing at ${GOLDEN_KPI.absolutePath}", GOLDEN_KPI.isFile)

        // Python's statistics.median over the golden rows: 54 LTE values, median -89.0, none below -105; 46 NR values.
        assertEquals(SignalSummary(ServingRat.LTE, samples = 54, medianRsrpDbm = -89, belowFairPct = 0.0), SignalSummaries.read(GOLDEN_KPI))
    }

    @Test
    fun anNrOnlySessionIsSummarisedAsNrWithTheReportsShareBelow105() {
        val lines = GOLDEN_KPI.readText(Charsets.UTF_8).split(CRLF).filter { it.isNotEmpty() }
        val nrOnly = listOf(lines.first()) + lines.drop(1).filter { it.split(',')[2] == "nr" }

        val summary = SignalSummaries.read(kpi(nrOnly))

        // 46 NR values, median -96.0, 4 below -105 dBm (the report's pct_below_-105 rounds this to 8.7).
        assertEquals(ServingRat.NR, summary?.rat)
        assertEquals(46, summary?.samples)
        assertEquals(-96, summary?.medianRsrpDbm)
        assertEquals(400.0 / 46, summary!!.belowFairPct, 1e-9)
    }

    @Test
    fun theRatWithMoreValuesWinsAndLteWinsATie() {
        val nrMore = SignalSummaries.read(kpi(listOf(HEADER, row("lte", "-80.0"), row("nr", "-100.0"), row("nr", "-101.0"))))
        assertEquals(ServingRat.NR, nrMore?.rat)
        assertEquals(-100, nrMore?.medianRsrpDbm)

        val tie = SignalSummaries.read(kpi(listOf(HEADER, row("lte", "-80.0"), row("nr", "-100.0"))))
        assertEquals(ServingRat.LTE, tie?.rat)
        assertEquals(-80, tie?.medianRsrpDbm)
    }

    @Test
    fun anEvenCountAveragesTheMiddleValuesAndRoundsHalfToEven() {
        assertEquals(-92, SignalSummaries.read(kpi(listOf(HEADER, row("lte", "-92.0"), row("lte", "-93.0"))))?.medianRsrpDbm)
        assertEquals(-94, SignalSummaries.read(kpi(listOf(HEADER, row("lte", "-93.0"), row("lte", "-94.0"))))?.medianRsrpDbm)
        assertEquals(-106, SignalSummaries.read(kpi(listOf(HEADER, row("lte", "-105.0"), row("lte", "-107.0"))))?.medianRsrpDbm)
    }

    @Test
    fun onlyValuesStrictlyBelow105CountAsBelow() {
        val summary = SignalSummaries.read(kpi(listOf(HEADER, row("lte", "-105.0"), row("lte", "-105.1"), row("lte", "-90.0"), row("lte", "-120.0"))))

        assertEquals(50.0, summary!!.belowFairPct, 1e-9)
    }

    @Test
    fun blankOutOfRangeAndMisshapenRowsAreLeftOut() {
        val summary = SignalSummaries.read(
            kpi(
                listOf(
                    HEADER,
                    row("lte", ""),
                    row("lte", "-20.0"),
                    row("lte", "-90.0"),
                    ",1789050600.400,lte,,212,-60.0",
                    row("gsm", "-70.0"),
                ),
            ),
        )

        assertEquals(SignalSummary(ServingRat.LTE, samples = 1, medianRsrpDbm = -90, belowFairPct = 0.0), summary)
    }

    @Test
    fun aTornLastRowIsLeftOut() {
        val file = kpi(listOf(HEADER, row("lte", "-90.0"), row("lte", "-91.0")))
        file.appendText(row("lte", "-1"), Charsets.UTF_8)

        assertEquals(2, SignalSummaries.read(file)?.samples)
        assertEquals(-90, SignalSummaries.read(file)?.medianRsrpDbm)
    }

    @Test
    fun noValuesMissingColumnsOrAMissingFileGiveNull() {
        assertNull(SignalSummaries.read(kpi(listOf(HEADER))))
        assertNull(SignalSummaries.read(kpi(listOf(HEADER, row("lte", "")))))
        assertNull(SignalSummaries.read(kpi(listOf("frame,time_epoch,pci", ",1789050600.400,212"))))
        assertNull(SignalSummaries.read(File(temp.root, "missing.csv")))
        assertNull(SignalSummaries.read(temp.newFile("empty.csv")))
    }

    /** A kpi.csv of [lines], each ending in CR LF as the writer ends them. */
    private fun kpi(lines: List<String>): File =
        File(temp.root, "kpi-${counter++}.csv").apply { writeText(lines.joinToString("") { it + CRLF }, Charsets.UTF_8) }

    private var counter = 0

    private fun row(rat: String, rsrp: String): String = ",1789050600.400,$rat,,212,$rsrp,-8.0,15.0,android-api age_ms=500 src=request,,"

    private companion object {
        const val CRLF = "\r\n"
        const val HEADER = "frame,time_epoch,rat,meas_id,pci,rsrp_dbm,rsrq_db,sinr_db,comment,lat,lon"
        val GOLDEN_KPI = File("../../tests/fixtures/android_session/20260910-143000_Mall-walk-north-path/kpi.csv")
    }
}
