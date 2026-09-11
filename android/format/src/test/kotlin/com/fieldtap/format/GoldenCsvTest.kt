package com.fieldtap.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The production encoders reproduce every golden CSV byte for byte, header included. Where the golden
 * text was rounded from a value that needs half-even rounding on the exact binary value (a fix's
 * 4.25 m accuracy, a 4135 ms ping, a 20.0625 Mbit/s download), the rows carry the unrounded value.
 */
class GoldenCsvTest {

    @Test
    fun kpiCsvIsReproducedByteForByte() {
        val rows = kpiRows()
        assertEquals(100, rows.size)
        assertReproduced(KpiCsv, rows)
    }

    @Test
    fun trackCsvIsReproducedFromFixValuesThatNeedHalfEvenRounding() {
        val roundingFix = GoldenRows.table(SessionFile.TRACK).single { it.getValue("time_utc") == "2026-09-10T14:30:12.000+00:00" }
        assertEquals("4.2", roundingFix.getValue("accuracy_m"))
        assertEquals("18.2", roundingFix.getValue("altitude_m"))
        assertEquals("1.12", roundingFix.getValue("speed_mps"))
        val rows = trackRows()
        assertEquals(120, rows.size)
        assertReproduced(TrackCsv, rows)
    }

    @Test
    fun eventsCsvIsReproducedFromDerivedEvents() {
        assertReproduced(EventsCsv, GoldenData.events())
    }

    @Test
    fun eventsCsvRecordsParseBackToTheDerivedEvents() {
        assertEquals(GoldenData.events(), GoldenRows.table(SessionFile.EVENTS).map { GoldenRows.event(it) })
    }

    @Test
    fun trafficCsvIsReproducedFromMeasuredValues() {
        assertReproduced(TrafficCsv, GoldenData.traffic())
    }

    @Test
    fun trafficCsvRecordsReEncodeToTheSameBytes() {
        assertReproduced(TrafficCsv, GoldenRows.table(SessionFile.TRAFFIC).map { GoldenRows.traffic(it) })
    }

    @Test
    fun cellsCsvIsReproducedFromTheServingCells() {
        assertReproduced(CellsCsv, GoldenData.cells())
    }

    @Test
    fun cellsCsvRecordsParseBackToTheServingCells() {
        assertEquals(GoldenData.cells(), GoldenRows.table(SessionFile.CELLS).map { GoldenRows.cell(it) })
    }

    @Test
    fun cellinfoCsvIsReproducedByteForByte() {
        val rows = cellInfoRows()
        assertEquals(344, rows.size)
        assertReproduced(CellInfoCsv, rows)
    }

    @Test
    fun noDefaultLocaleChangesAnyByte() {
        val kpi = kpiRows()
        val track = trackRows()
        val cellInfo = cellInfoRows()
        for (tag in TRICKY_LOCALES) {
            withDefaultLocale(tag) {
                assertReproduced(KpiCsv, kpi)
                assertReproduced(TrackCsv, track)
                assertReproduced(EventsCsv, GoldenData.events())
                assertReproduced(TrafficCsv, GoldenData.traffic())
                assertReproduced(CellsCsv, GoldenData.cells())
                assertReproduced(CellInfoCsv, cellInfo)
            }
        }
    }

    private fun kpiRows(): List<KpiRow> = GoldenRows.table(SessionFile.KPI).map { GoldenRows.kpi(it) }

    private fun cellInfoRows(): List<CellInfoRow> = GoldenRows.table(SessionFile.CELLINFO).map { GoldenRows.cellInfo(it) }

    /** The golden track, with the fix at 12 s carrying the float values the generator measured. */
    private fun trackRows(): List<TrackRow> = GoldenRows.table(SessionFile.TRACK).map { GoldenRows.track(it) }.map { row ->
        if (row.timeUtcMs == Golden.at(12_000)) row.copy(accuracyM = 4.25, altitudeM = 18.25, speedMps = 1.125) else row
    }

    private fun <T> assertReproduced(encoder: CsvEncoder<T>, rows: List<T>) {
        val written = StringBuilder(encoder.headerLine)
        for (row in rows) written.append(encoder.encode(row))
        val text = written.toString()
        val expectedLines = Golden.text(encoder.file).split(Csv.LINE_END)
        val actualLines = text.split(Csv.LINE_END)
        for (index in 0 until maxOf(expectedLines.size, actualLines.size)) {
            assertEquals("${encoder.file.fileName} line ${index + 1}", expectedLines.getOrNull(index), actualLines.getOrNull(index))
        }
        assertArrayEquals("${encoder.file.fileName} bytes", Golden.bytes(encoder.file), text.toByteArray(Charsets.UTF_8))
    }
}
