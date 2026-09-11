package com.fieldtap.format

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every column each encoder writes obeys its `schema/columns.json` entry: type and digits, allowed
 * values, pattern, required and always-blank, and range (by RAT where the schema gives one). Rows are
 * filled at the edges of the ranges.
 */
class EncoderSchemaConformanceTest {
    private val t = Golden.at(400)
    private val here = LatLon(38.8895123, -77.0353323)

    @Test
    fun kpiRecordsConform() {
        assertConforms(SessionFile.KPI, KpiCsv.encode(KpiRow(t, ServingRat.LTE, 503, -156, -34, 40, 2_500, CellInfoSource.REQUEST, here)))
        assertConforms(SessionFile.KPI, KpiCsv.encode(KpiRow(t, ServingRat.NR, 1007, -29, 20, -23, 0, CellInfoSource.PUSH, here)))
        assertConforms(SessionFile.KPI, KpiCsv.encode(KpiRow(t, ServingRat.NR, null, null, null, null, 11_000, CellInfoSource.PUSH)))
    }

    @Test
    fun trackRecordsConform() {
        assertConforms(SessionFile.TRACK, TrackCsv.encode(TrackRow(t, here, 4.25, -1000.0, 400.0, FixProvider.NETWORK)))
        assertConforms(SessionFile.TRACK, TrackCsv.encode(TrackRow(t, LatLon(-90.0, 180.0), 0.0, 20_000.0, 0.0, FixProvider.GPS)))
        assertConforms(SessionFile.TRACK, TrackCsv.encode(TrackRow(t, here, null, null, null, FixProvider.FUSED)))
    }

    @Test
    fun everyEventKindConforms() {
        for (kind in EventKind.entries) {
            for (rat in kind.rats) {
                val cell = when {
                    !kind.namesCell -> null
                    rat == EventRat.NR -> 1007 to 3_279_165
                    else -> 503 to 262_143
                }
                for (severity in kind.severities) {
                    val row = EventRow(t, rat, kind, severity, "Title, with a comma", "detail \"quoted\"", cell?.first, cell?.second, "screen_off")
                    assertConforms(SessionFile.EVENTS, EventsCsv.encode(row))
                }
            }
        }
    }

    @Test
    fun trafficRecordsConform() {
        assertConforms(SessionFile.TRAFFIC, TrafficCsv.encode(TrafficRow(t, TrafficTest.PING, "8.8.8.8", true, 4.135, 0.0, 38.2, 44.7, 58.9)))
        assertConforms(
            SessionFile.TRAFFIC,
            TrafficCsv.encode(TrafficRow(t, TrafficTest.DOWNLOAD, "https://speed.cloudflare.com/__down?bytes=10000000", true, 4.0, mbps = 20.0625, bytes = 10_031_250L, httpCode = 200)),
        )
        assertConforms(
            SessionFile.TRAFFIC,
            TrafficCsv.encode(TrafficRow(t, TrafficTest.DOWNLOAD, "https://example.invalid/", false, 0.5, lossPct = null, httpCode = 599, error = "HTTP 599")),
        )
        assertConforms(SessionFile.TRAFFIC, TrafficCsv.encode(TrafficRow(t, TrafficTest.PING, "10.0.2.2", false, 5.0, lossPct = 100.0, error = "no reply")))
    }

    @Test
    fun cellsRecordsConform() {
        assertConforms(
            SessionFile.CELLS,
            CellsCsv.encode(CellRow(t, ServingRat.LTE, "311", "480", 16_777_215, 268_435_455L, 503, 1024, 262_143, 262_143, 400.0, 0.0, true, "Verizon", listOf("311490", "31026"), 0, -156, -43)),
        )
        assertConforms(
            SessionFile.CELLS,
            CellsCsv.encode(CellRow(t, ServingRat.NR, "001", "01", 0, 68_719_476_735L, 1007, 1, 3_279_165, null, null, null, true, null, emptyList(), 46, -156, -29)),
        )
        assertConforms(SessionFile.CELLS, CellsCsv.encode(GoldenData.cells()[1]))
    }

    @Test
    fun cellinfoRecordsConform() {
        val lte = CellInfoRow(
            seenUtcMs = t + 500,
            rat = Rat.LTE,
            registered = true,
            mcc = "311",
            mnc = "480",
            operator = "Verizon",
            pci = 212,
            arfcn = 66_786,
            bands = listOf(66, 2),
            tac = 18_704,
            cellId = 21_640_193L,
            bandwidthKhz = 20_000,
            rsrp = -84,
            rsrq = -8,
            sinr = 15,
            rssi = -57,
            level = 4,
            additionalPlmns = listOf("310260"),
            timeEpochMs = t,
            timestampMs = 25_323_856L,
            ageMs = 500L,
            stale = false,
            connectionStatus = 1,
            source = CellInfoSource.REQUEST,
            cqi = 15,
            timingAdvance = 3_846,
            csiRsrp = -31,
            csiRsrq = -3,
            csiSinr = 23,
            screenOn = true,
            charging = false,
            wifiConnected = false,
            subId = 0,
            position = here,
        )
        assertConforms(SessionFile.CELLINFO, CellInfoCsv.encode(lte))
        val nrNeighbour = lte.copy(
            rat = Rat.NR,
            registered = false,
            mcc = null,
            mnc = null,
            operator = null,
            pci = 1007,
            arfcn = 3_279_165,
            bands = emptyList(),
            tac = null,
            cellId = 68_719_476_735L,
            bandwidthKhz = null,
            rsrp = -29,
            rsrq = 20,
            sinr = 40,
            rssi = null,
            level = 0,
            additionalPlmns = emptyList(),
            stale = true,
            connectionStatus = 0,
            source = CellInfoSource.PUSH,
            cqi = null,
            timingAdvance = null,
            csiRsrp = -156,
            csiRsrq = -20,
            csiSinr = -23,
            subId = null,
            position = null,
        )
        assertConforms(SessionFile.CELLINFO, CellInfoCsv.encode(nrNeighbour))
        val gsm = nrNeighbour.copy(rat = Rat.GSM, pci = null, arfcn = 62, cellId = 12_345L, rsrp = null, rsrq = null, sinr = null, rssi = -150, level = 2, connectionStatus = 2)
        assertConforms(SessionFile.CELLINFO, CellInfoCsv.encode(gsm))
    }

    private fun assertConforms(file: SessionFile, record: String) {
        assertTrue("${file.fileName}: record ends in CR LF", record.endsWith(Csv.LINE_END))
        val records = Csv.records(record)
        assertEquals("${file.fileName}: one physical line", 1, records.size)
        val fields = Csv.parseRecord(records[0])
        val columns = SchemaJson.columns(file)
        assertEquals("${file.fileName}: field count", columns.size, fields.size)
        val ratIndex = columns.indexOfFirst { it.str("name") == "rat" }
        val rat = if (ratIndex >= 0) fields[ratIndex] else null
        for ((column, value) in columns.zip(fields)) assertColumn(file, column, value, rat)
    }

    private fun assertColumn(file: SessionFile, column: JsonObject, value: String, rat: String?) {
        val name = "${file.fileName} ${column.str("name")} '$value'"
        assertFalse("$name holds Android's unavailable value", value.contains("2147483647"))
        if (column.bool("always_blank")) {
            assertEquals(name, "", value)
            return
        }
        if (value.isEmpty()) {
            assertFalse("$name is required", column.bool("required"))
            return
        }
        val type = column.str("type")
        when (type) {
            "utc" -> assertTrue(name, Regex(Schema.UTC_PATTERN).matches(value))
            "integer" -> assertTrue(name, Regex("-?(0|[1-9][0-9]*)").matches(value))
            "decimal" -> assertTrue(name, Regex("-?[0-9]+\\.[0-9]{" + column.long("decimals") + "}").matches(value))
            "enum" -> assertTrue(name, value in column.strings("values"))
            "text" -> column.strOrNull("pattern")?.let { assertTrue(name, Regex(it).matches(value)) }
            else -> fail("$name has an unknown type $type")
        }
        if (type == "integer" || type == "decimal") {
            val number = value.toDouble()
            val byRat = column["range_by_rat"] as? JsonObject
            val bounds: List<Double?> = if (byRat != null && rat != null && byRat.containsKey(rat)) {
                byRat.getValue(rat).jsonArray.map { it.jsonPrimitive.content.toDouble() }
            } else {
                listOf(column.numberOrNull("min"), column.numberOrNull("max"))
            }
            bounds[0]?.let { assertTrue("$name is at least $it", number >= it) }
            bounds[1]?.let { assertTrue("$name is at most $it", number <= it) }
        }
    }
}
