package com.fieldtap.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvEncodersTest {
    private val t = Golden.at(400)
    private val time = "2026-09-10T14:30:00.400+00:00"

    private fun line(vararg fields: String): String = fields.joinToString(",") + "\r\n"

    private fun fields(record: String): List<String> {
        assertTrue(record.endsWith("\r\n"))
        return Csv.parseRecord(record.removeSuffix("\r\n"))
    }

    @Test
    fun headerLinesAreTheSchemaHeadersEndingInCrLf() {
        val encoders: List<CsvEncoder<*>> = listOf(KpiCsv, TrackCsv, EventsCsv, TrafficCsv, CellsCsv, CellInfoCsv)
        assertEquals(SessionFile.CSV, encoders.map { it.file })
        for (encoder in encoders) {
            assertEquals(encoder.file.header.joinToString(",") + "\r\n", encoder.headerLine)
            assertEquals(encoder.file.header, fields(encoder.headerLine))
        }
    }

    @Test
    fun kpiWritesAServingSample() {
        val row = KpiRow(t, ServingRat.LTE, 212, -84, -8, 15, 500, CellInfoSource.REQUEST, LatLon(38.8895, -77.0353))
        assertEquals(
            line("", "1789050600.400", "lte", "", "212", "-84.0", "-8.0", "15.0", "android-api age_ms=500 src=request", "38.8895000", "-77.0353000"),
            KpiCsv.encode(row),
        )
    }

    @Test
    fun kpiLeavesUnknownValuesAndAMissingPositionBlank() {
        val row = KpiRow(t, ServingRat.NR, null, null, Int.MAX_VALUE, null, 0, CellInfoSource.PUSH)
        assertEquals(
            line("", "1789050600.400", "nr", "", "", "", "", "", "android-api age_ms=0 src=push", "", ""),
            KpiCsv.encode(row),
        )
    }

    @Test
    fun kpiNeverWritesOneCoordinateWithoutTheOther() {
        val row = KpiRow(t, ServingRat.LTE, 1, -90, null, null, 10, CellInfoSource.REQUEST, LatLon(38.0, Double.NaN))
        val written = fields(KpiCsv.encode(row))
        assertEquals("", written[9])
        assertEquals("", written[10])
    }

    @Test
    fun kpiCommentMatchesTheSchemaPattern() {
        val pattern = Regex(Schema.KPI_COMMENT_PATTERN)
        assertTrue(pattern.matches(KpiCsv.comment(0, CellInfoSource.REQUEST)))
        assertTrue(pattern.matches(KpiCsv.comment(10_999, CellInfoSource.PUSH)))
        assertEquals("android-api age_ms=0 src=request", KpiCsv.comment(-5, CellInfoSource.REQUEST))
    }

    @Test
    fun trackWritesAFixWithUnknownValuesBlank() {
        val row = TrackRow(t, LatLon(-33.8688197, 151.2092955), null, null, null, FixProvider.FUSED)
        assertEquals(line(time, "-33.8688197", "151.2092955", "", "", "", "fused", "android"), TrackCsv.encode(row))
    }

    @Test
    fun trackRoundsAccuracyAltitudeAndSpeedHalfEven() {
        val row = TrackRow(t, LatLon(38.8895, -77.0353), 4.25, 18.25, 1.125, FixProvider.GPS)
        assertEquals(line(time, "38.8895000", "-77.0353000", "4.2", "18.2", "1.12", "gps", "android"), TrackCsv.encode(row))
    }

    @Test
    fun eventsTurnLineBreaksIntoSpacesAndQuoteOnlyWhenNeeded() {
        val row = EventRow(t, EventRat.NONE, EventKind.MARKER, Severity.INFO, "Marker", "Door \"B\", level 2\r\nnear lift")
        assertEquals(
            line(time, "-", "marker", "info", "Marker", "\"Door \"\"B\"\", level 2  near lift\"", "", "", "", "", ""),
            EventsCsv.encode(row),
        )
    }

    @Test
    fun eventsWriteTheCellAndTheCause() {
        val row = EventRow(t, EventRat.LTE, EventKind.RAT_CHANGE, Severity.WARN, "RAT\nchanged", "NR to LTE", pci = 212, arfcn = 66_786, cause = "no_service")
        assertEquals(
            line(time, "lte", "rat_change", "warn", "RAT changed", "NR to LTE", "", "212", "66786", "no_service", ""),
            EventsCsv.encode(row),
        )
    }

    @Test
    fun trafficWritesAPingWithoutACellularNetwork() {
        val row = TrafficRow(t, TrafficTest.PING, "8.8.8.8", ok = false, seconds = 0.0, error = "no cellular network")
        assertEquals(
            line(time, "ping", "8.8.8.8", "0", "0.00", "", "", "", "", "", "", "", "no cellular network"),
            TrafficCsv.encode(row),
        )
    }

    @Test
    fun trafficWritesAPingThatLostEveryEcho() {
        val row = TrafficRow(t, TrafficTest.PING, "10.0.2.2", ok = false, seconds = 5.0, lossPct = 100.0, error = "no reply")
        assertEquals(
            line(time, "ping", "10.0.2.2", "0", "5.00", "100.0", "", "", "", "", "", "", "no reply"),
            TrafficCsv.encode(row),
        )
    }

    @Test
    fun trafficWritesAnHttpError() {
        val url = "https://speed.cloudflare.com/__down?bytes=10000000"
        val row = TrafficRow(t, TrafficTest.DOWNLOAD, url, ok = false, seconds = 0.25, bytes = 0, httpCode = 503, error = "HTTP 503")
        assertEquals(
            line(time, "download", url, "0", "0.25", "", "", "", "", "", "0", "503", "HTTP 503"),
            TrafficCsv.encode(row),
        )
    }

    @Test
    fun cellsDeriveEnbAndSectorAndSortAdditionalPlmns() {
        val row = CellRow(t, ServingRat.LTE, "311", "480", 18_704, 21_640_193L, 212, 66, 66_786, 132_322, 20.0, 20.0, true, "Verizon", listOf("311490", "310260"), 32, -102, -83)
        assertEquals(
            line(time, "lte", "311480", "311", "480", "18704", "21640193", "84532", "1", "212", "66", "66786", "132322", "20.0", "20.0", "android", "True", "Verizon", "\"310260, 311490\"", "32", "-102.0", "-83.0"),
            CellsCsv.encode(row),
        )
    }

    @Test
    fun cellsLeaveEnbAndSectorBlankWithoutACellId() {
        val nr = CellRow(t, ServingRat.NR, null, null, null, null, 393, 77, 650_000, null, null, null, true, null, emptyList(), 46, null, null)
        assertEquals(
            line(time, "nr", "", "", "", "", "", "", "", "393", "77", "650000", "", "", "", "android", "True", "", "", "46", "", ""),
            CellsCsv.encode(nr),
        )
        val unavailableEci = nr.copy(rat = ServingRat.LTE, cellId = Long.MAX_VALUE)
        assertEquals(listOf("", "", ""), fields(CellsCsv.encode(unavailableEci)).subList(6, 9))
    }

    @Test
    fun cellsPlausibleFollowsTheWrittenPciAndArfcn() {
        val row = CellRow(t, ServingRat.NR, null, null, null, null, Int.MAX_VALUE, 78, 632_628, null, null, null, true, null, emptyList(), 3, -101, -95)
        val written = fields(CellsCsv.encode(row))
        assertEquals("", written[9])
        assertEquals("False", written[16])
        val claimedImplausible = row.copy(pci = 17, plausible = false)
        assertEquals("True", fields(CellsCsv.encode(claimedImplausible))[16])
    }

    @Test
    fun cellinfoWritesTheGoldenServingCellRecord() {
        val goldenFirstRecord = Csv.records(Golden.text(SessionFile.CELLINFO))[1] + "\r\n"
        assertEquals(goldenFirstRecord, CellInfoCsv.encode(cellInfoRow(bands = listOf(66), additionalPlmns = emptyList())))
    }

    @Test
    fun cellinfoKeepsBandOrderAndSortsAdditionalPlmns() {
        val written = fields(CellInfoCsv.encode(cellInfoRow(bands = listOf(78, 41), additionalPlmns = listOf("311490", "310260"))))
        assertEquals("78, 41", written[9])
        assertEquals("310260, 311490", written[18])
    }

    @Test
    fun cellinfoWritesUnknownValuesBlank() {
        val row = CellInfoRow(
            seenUtcMs = t,
            rat = Rat.GSM,
            registered = false,
            mcc = null,
            mnc = null,
            operator = null,
            pci = null,
            arfcn = 62,
            bands = emptyList(),
            tac = null,
            cellId = Long.MAX_VALUE,
            bandwidthKhz = null,
            rsrp = null,
            rsrq = null,
            sinr = null,
            rssi = -95,
            level = 2,
            additionalPlmns = emptyList(),
            timeEpochMs = t,
            timestampMs = 0,
            ageMs = 0,
            stale = true,
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
        )
        assertEquals(
            line(
                time, "gsm", "0", "", "", "", "", "", "62", "", "", "", "", "", "", "", "-95", "2", "",
                "1789050600.400", "0", "0", "1", "", "push", "", "", "", "", "", "0", "1", "1", "", "", "",
            ),
            CellInfoCsv.encode(row),
        )
    }

    @Test
    fun everyEncoderWritesOneFieldPerColumnOnOneLine() {
        val tricky = "a, \"b\"\r\nc"
        assertOneRecord(SessionFile.EVENTS, EventsCsv.encode(EventRow(t, EventRat.NONE, EventKind.TEST_FAILED, Severity.ERROR, tricky, tricky)))
        assertOneRecord(SessionFile.TRAFFIC, TrafficCsv.encode(TrafficRow(t, TrafficTest.DOWNLOAD, tricky, false, 1.0, error = tricky)))
        assertOneRecord(
            SessionFile.CELLS,
            CellsCsv.encode(CellRow(t, ServingRat.LTE, "311", "480", 1, 1L, 1, 1, 1, null, null, null, true, tricky, listOf("311480", "310260"), 1, null, null)),
        )
        assertOneRecord(SessionFile.CELLINFO, CellInfoCsv.encode(cellInfoRow(listOf(2, 66), listOf("311480", "310260")).copy(operator = tricky)))
        assertOneRecord(SessionFile.KPI, KpiCsv.encode(KpiRow(t, ServingRat.LTE, null, null, null, null, 0, CellInfoSource.PUSH)))
        assertOneRecord(SessionFile.TRACK, TrackCsv.encode(TrackRow(t, LatLon(1.0, 1.0), null, null, null, FixProvider.GPS)))
    }

    private fun assertOneRecord(file: SessionFile, record: String) {
        assertTrue(record.endsWith("\r\n"))
        val records = Csv.records(record)
        assertEquals(file.fileName, 1, records.size)
        assertEquals(file.fileName, file.header.size, Csv.parseRecord(records[0]).size)
    }

    /** The golden session's first cellinfo row: the LTE serving cell at 14:30:00.900. */
    private fun cellInfoRow(bands: List<Int>, additionalPlmns: List<String>): CellInfoRow = CellInfoRow(
        seenUtcMs = Golden.at(900),
        rat = Rat.LTE,
        registered = true,
        mcc = "311",
        mnc = "480",
        operator = "Verizon",
        pci = 212,
        arfcn = 66_786,
        bands = bands,
        tac = 18_704,
        cellId = 21_640_193L,
        bandwidthKhz = 20_000,
        rsrp = -84,
        rsrq = -8,
        sinr = 15,
        rssi = -57,
        level = 4,
        additionalPlmns = additionalPlmns,
        timeEpochMs = Golden.at(400),
        timestampMs = 25_323_856L,
        ageMs = 500L,
        stale = false,
        connectionStatus = 1,
        source = CellInfoSource.REQUEST,
        cqi = 11,
        timingAdvance = 3,
        csiRsrp = null,
        csiRsrq = null,
        csiSinr = null,
        screenOn = true,
        charging = false,
        wifiConnected = false,
        subId = 1,
        position = LatLon(38.8895, -77.0353),
    )
}
