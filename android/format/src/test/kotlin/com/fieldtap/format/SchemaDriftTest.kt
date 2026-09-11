package com.fieldtap.format

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-copied constants in [Schema], [Ranges], the enums, [EventKind], [SessionFile] and the
 * writers equal `schema/columns.json` at the repository root. When this fails, the JSON changed:
 * update the Kotlin side to match (docs/SESSION-FORMAT.md, "Keeping both sides honest").
 */
class SchemaDriftTest {
    private val schema: JsonObject
        get() = SchemaJson.root

    @Test
    fun formatAndTopLevelConstants() {
        assertEquals(schema.str("format"), Schema.FORMAT)
        assertEquals(schema.str("format"), SessionFormat.ID)
        assertEquals(schema.long("schema_version"), Schema.SCHEMA_VERSION.toLong())
        assertEquals(schema.long("android_unavailable"), Schema.ANDROID_UNAVAILABLE.toLong())
        assertEquals(schema.strings("report_outputs"), Schema.REPORT_OUTPUTS)
    }

    @Test
    fun encodingRulesAreTheWritersBehaviour() {
        val encoding = schema.obj("encoding")
        assertEquals("utf-8", encoding.str("charset"))
        assertFalse(encoding.bool("byte_order_mark"))
        assertEquals(encoding.str("csv_line_terminator"), Csv.LINE_END)
        assertEquals(",", encoding.str("csv_delimiter"))
        assertEquals("\"", encoding.str("csv_quote"))
        assertEquals("minimal", encoding.str("csv_quoting"))
        assertEquals("", encoding.str("csv_blank"))
        assertEquals("replace_with_space", encoding.str("text_line_breaks"))
        assertEquals("half_even_exact_binary", encoding.str("number_rounding"))
        assertEquals("ascii", encoding.str("number_digits"))
        assertEquals(".", encoding.str("decimal_separator"))
        assertFalse(encoding.bool("negative_zero"))
        assertEquals("\n", encoding.str("json_line_terminator"))
        assertEquals(2L, encoding.long("json_indent"))
        assertFalse(encoding.bool("json_ascii_only"))

        val quoteIfContains = encoding.strings("csv_quote_if_contains")
        assertEquals(listOf(",", "\"", "\r", "\n"), quoteIfContains)
        for (special in quoteIfContains) {
            assertTrue(Csv.record(listOf("a" + special + "b", "x")).startsWith("\""))
        }
        for (code in 0x20..0x7E) {
            val c = Char(code).toString()
            if (c !in quoteIfContains) assertEquals("a" + c + "b,x\r\n", Csv.record(listOf("a" + c + "b", "x")))
        }
        assertEquals("0.0", Csv.decimal(-0.0, 1))
        assertEquals("{\n  \"k\": 1\n}\n", JsonText.render(JsonObj(listOf("k" to JsonInt(1)))))
        assertEquals("\"" + ch(0xE9) + "\"", JsonText.quote(ch(0xE9)))
    }

    @Test
    fun timestampsAndDirectoryNames() {
        val timestamps = schema.obj("timestamps")
        assertEquals("_utc", timestamps.str("utc_suffix"))
        assertEquals(timestamps.str("utc_pattern"), Schema.UTC_PATTERN)
        assertEquals(timestamps.str("utc_kotlin_pattern"), SessionFormat.UTC_KOTLIN_PATTERN)
        val example = timestamps.str("utc_example")
        assertEquals(example, SessionFormat.utc(SessionFormat.parseUtc(example)!!))
        assertEquals(3L, timestamps.long("time_epoch_decimals"))
        assertEquals(3, Csv.epoch(1_789_050_600_400L).substringAfter('.').length)

        val directory = schema.obj("directory")
        assertEquals(directory.str("pattern"), Schema.DIRECTORY_PATTERN)
        assertEquals(directory.str("pattern"), SessionDirName.PATTERN.pattern)
        assertEquals(directory.str("time_format_kotlin"), SessionDirName.TIME_FORMAT)
        assertEquals(directory.str("slug_replace_pattern"), SessionDirName.SLUG_REPLACE_PATTERN)
        assertEquals("-", directory.str("slug_replacement"))
        assertEquals("-", directory.str("slug_strip"))
        assertEquals(directory.long("slug_max_length"), Schema.SLUG_MAX_LENGTH.toLong())
        assertEquals(directory.str("slug_empty"), Schema.SLUG_EMPTY)
    }

    @Test
    fun sessionFilesAndTheUploadBundle() {
        assertEquals(schema.strings("files"), SessionFile.entries.map { it.fileName })
        val bundle = schema.obj("upload_bundle")
        assertEquals(bundle.strings("file_names"), SessionFile.BUNDLE.map { it.fileName })
        assertEquals(bundle.strings("required"), listOf(SessionFile.SESSION_JSON.fileName))
        assertEquals(bundle.long("max_compressed_bytes"), Schema.MAX_COMPRESSED_BYTES)
        assertEquals(bundle.long("max_uncompressed_bytes"), Schema.MAX_UNCOMPRESSED_BYTES)
        assertEquals(SessionFile.CSV, SessionFile.entries.filter { it != SessionFile.SESSION_JSON })
        for (file in SessionFile.entries) assertEquals(file, SessionFile.byFileName(file.fileName))
        assertNull(SessionFile.byFileName("summary.json"))
    }

    @Test
    fun csvHeaders() {
        assertEquals(schema.arr("csv").map { it.jsonObject.str("name") }, SessionFile.CSV.map { it.fileName })
        for (file in SessionFile.CSV) {
            assertEquals(file.fileName, SchemaJson.csv(file).strings("header"), file.header)
            assertEquals(file.fileName, SchemaJson.columns(file).map { it.str("name") }, file.header)
        }
        assertEquals(emptyList<String>(), SessionFile.SESSION_JSON.header)
    }

    @Test
    fun kpiRules() {
        val kpi = schema.obj("kpi")
        assertEquals(kpi.long("max_age_ms"), Schema.KPI_MAX_AGE_MS)
        assertEquals(kpi.long("max_age_ms_short_interval"), Schema.KPI_MAX_AGE_MS_SHORT_INTERVAL)
        assertTrue(Schema.KPI_MAX_AGE_MS_SHORT_INTERVAL < Schema.KPI_MAX_AGE_MS)
        assertEquals((kpi.str("gps_match_seconds").toDouble() * 1000).toLong(), Schema.GPS_MATCH_MS)
        assertEquals(kpi.str("comment_pattern"), Schema.KPI_COMMENT_PATTERN)
        assertEquals(SchemaJson.column(SessionFile.KPI, "comment").str("pattern"), Schema.KPI_COMMENT_PATTERN)
    }

    @Test
    fun sessionJsonConstants() {
        val sessionJson = schema.obj("session_json")
        assertEquals(sessionJson.str("transport_app"), Schema.TRANSPORT_APP)
        assertEquals(sessionJson.strings("handset_keys"), Schema.HANDSET_KEYS)
        assertEquals(sessionJson.strings("forbidden_keys"), Schema.FORBIDDEN_KEYS)
        assertEquals(sessionJson.strings("location_precisions"), LocationPrecision.entries.map { it.wire })
        assertEquals(sessionJson.long("approx_110m_decimals"), Coordinates.APPROX_110M_DECIMALS.toLong())
        assertEquals(SchemaJson.field("format").strings("values"), listOf(SessionFormat.ID))
        assertEquals(SchemaJson.field("transport.transport").strings("values"), listOf(Schema.TRANSPORT_APP))
        assertEquals(SchemaJson.field("privacy.data_class").strings("values"), listOf(Schema.DATA_CLASS))
        assertEquals(SchemaJson.field("privacy.location_precision").strings("values"), LocationPrecision.entries.map { it.wire })
        assertEquals(listOf("false"), SchemaJson.field("capabilities.layer3").strings("values"))
        assertFalse(SessionMeta(
            sessionId = "s",
            name = "n",
            startedUtcMs = 0,
            transport = TransportMeta("0.1.0", 1),
            device = DeviceMeta("app:k"),
            summary = SummaryMeta("recording"),
            privacy = PrivacyMeta(LocationPrecision.FULL, 0, "v", "h"),
        ).capabilities.layer3)
        assertTrue(SchemaJson.field("device.key").str("pattern").startsWith("^" + Schema.DEVICE_KEY_PREFIX))
        assertEquals(SchemaJson.field("summary.stopped_by").str("pattern"), Schema.TOKEN_PATTERN)
        assertEquals(SchemaJson.field("collection.gaps[].reason").str("pattern"), Schema.TOKEN_PATTERN)
        assertEquals(SchemaJson.column(SessionFile.EVENTS, "kind").str("pattern"), Schema.TOKEN_PATTERN)
        assertEquals(SchemaJson.column(SessionFile.EVENTS, "cause").str("pattern"), Schema.TOKEN_PATTERN)

        // SessionJson writes exactly these numbers with one decimal.
        val oneDecimal = SchemaJson.fields().filter { it.numberOrNull("decimals") != null }
        assertEquals(
            listOf(
                "collection.short_interval_pct",
                "collection.screen_on_pct",
                "collection.wifi_connected_pct",
                "collection.charging_pct",
                "collection.gaps[].seconds",
            ),
            oneDecimal.map { it.str("path") },
        )
        for (field in oneDecimal) assertEquals(field.str("path"), 1L, field.long("decimals"))
        for (key in Schema.FORBIDDEN_KEYS) assertFalse(key, SchemaJson.fields().any { it.str("path").substringAfterLast('.') == key })
    }

    @Test
    fun eventKindsSeveritiesAndRats() {
        val events = schema.obj("events")
        assertEquals(events.strings("severities"), Severity.entries.map { it.wire })
        assertEquals(SchemaJson.column(SessionFile.EVENTS, "severity").strings("values"), Severity.entries.map { it.wire })
        assertEquals(SchemaJson.column(SessionFile.EVENTS, "rat").strings("values"), EventRat.entries.map { it.wire })
        val kinds = events.arr("app_kinds").map { it.jsonObject }
        assertEquals(kinds.map { it.str("kind") }, EventKind.entries.map { it.wire })
        for ((spec, kind) in kinds.zip(EventKind.entries)) {
            assertEquals(kind.wire, spec.strings("rat").toSet(), kind.rats.map { it.wire }.toSet())
            assertEquals(kind.wire, spec.strings("severity").toSet(), kind.severities.map { it.wire }.toSet())
            assertEquals(kind.wire, spec.bool("pci_arfcn"), kind.namesCell)
            assertTrue(kind.wire, Regex(Schema.TOKEN_PATTERN).matches(kind.wire))
        }
        assertEquals(events.strings("signalling_kinds"), Schema.SIGNALLING_KINDS)
        assertEquals(events.strings("signalling_kind_prefixes"), Schema.SIGNALLING_KIND_PREFIXES)
        for (kind in EventKind.entries) {
            assertFalse(kind.wire, kind.wire in Schema.SIGNALLING_KINDS)
            assertFalse(kind.wire, Schema.SIGNALLING_KIND_PREFIXES.any { kind.wire.startsWith(it) })
        }
    }

    @Test
    fun enumWireValues() {
        val servingRats = ServingRat.entries.map { it.wire }
        assertEquals(SchemaJson.column(SessionFile.KPI, "rat").strings("values"), servingRats)
        assertEquals(SchemaJson.column(SessionFile.CELLS, "rat").strings("values"), servingRats)
        assertEquals(SchemaJson.column(SessionFile.CELLINFO, "rat").strings("values"), Rat.entries.map { it.wire })
        assertEquals(SchemaJson.column(SessionFile.CELLINFO, "source").strings("values"), CellInfoSource.entries.map { it.wire })
        assertEquals(SchemaJson.column(SessionFile.TRACK, "provider").strings("values"), FixProvider.entries.map { it.wire })
        assertEquals(SchemaJson.column(SessionFile.TRACK, "source").strings("values"), listOf("android"))
        assertEquals(SchemaJson.column(SessionFile.CELLS, "version").strings("values"), listOf("android"))
        assertEquals(SchemaJson.column(SessionFile.CELLS, "plausible").strings("values"), listOf("True", "False"))
        assertEquals(SchemaJson.column(SessionFile.CELLINFO, "connection_status").strings("values"), listOf("0", "1", "2"))
        assertEquals(SchemaJson.column(SessionFile.TRAFFIC, "test").strings("values"), TrafficTest.entries.map { it.wire })
        assertEquals(schema.obj("traffic_tests").strings("app"), TrafficTest.entries.map { it.wire })
        val flags = listOf(
            SessionFile.TRAFFIC to "ok",
            SessionFile.CELLINFO to "registered",
            SessionFile.CELLINFO to "stale",
            SessionFile.CELLINFO to "screen_on",
            SessionFile.CELLINFO to "charging",
            SessionFile.CELLINFO to "wifi_connected",
        )
        for ((file, column) in flags) assertEquals(column, listOf("0", "1"), SchemaJson.column(file, column).strings("values"))
        for (rat in Rat.entries) assertEquals(rat.wire, rat == Rat.LTE || rat == Rat.NR, rat.servingRat != null)
        for (rat in ServingRat.entries) {
            assertEquals(rat.wire, rat.rat.wire)
            assertEquals(rat.wire, rat.eventRat.wire)
            assertEquals(rat, rat.rat.servingRat)
        }
        for (precision in LocationPrecision.entries) assertEquals(precision, LocationPrecision.fromWire(precision.wire))
        assertNull(LocationPrecision.fromWire("exact"))
    }

    @Test
    fun rangesMatchEveryBoundInTheSchema() {
        val mirrored = mirroredRanges()
        val unmirrored = unmirroredRanges()
        assertTrue(mirrored.keys.intersect(unmirrored.keys).isEmpty())
        val seen = HashSet<String>()
        for (file in SessionFile.CSV) {
            for (column in SchemaJson.columns(file)) {
                val name = key(file, column.str("name"))
                val actual = schemaBounds(column)
                val expected = mirrored[name] ?: unmirrored[name]
                if (actual == NO_BOUNDS) {
                    assertNull("$name has no range in columns.json", expected)
                } else {
                    assertEquals(name, expected, actual)
                    seen.add(name)
                }
            }
        }
        assertEquals(mirrored.keys + unmirrored.keys, seen)
    }

    private data class Bounds(val min: Double?, val max: Double?, val byRat: Map<String, List<Double>>?)

    private fun key(file: SessionFile, column: String): String = file.fileName + " " + column

    private fun schemaBounds(column: JsonObject): Bounds = Bounds(
        min = column.numberOrNull("min"),
        max = column.numberOrNull("max"),
        byRat = (column["range_by_rat"] as? JsonObject)?.let { byRat ->
            byRat.entries.associate { (rat, pair) -> rat to pair.jsonArray.map { it.jsonPrimitive.content.toDouble() } }
        },
    )

    private fun bounds(range: ClosedFloatingPointRange<Double>): Bounds = Bounds(range.start, range.endInclusive, null)

    private fun bounds(range: IntRange): Bounds = Bounds(range.first.toDouble(), range.last.toDouble(), null)

    private fun bounds(range: LongRange): Bounds = Bounds(range.first.toDouble(), range.last.toDouble(), null)

    private fun doubleByRat(ranges: Map<ServingRat, ClosedFloatingPointRange<Double>>): Bounds =
        Bounds(null, null, ranges.entries.associate { (rat, range) -> rat.wire to listOf(range.start, range.endInclusive) })

    private fun intByRat(ranges: Map<ServingRat, IntRange>): Bounds =
        Bounds(null, null, ranges.entries.associate { (rat, range) -> rat.wire to listOf(range.first.toDouble(), range.last.toDouble()) })

    private fun longByRat(ranges: Map<ServingRat, LongRange>): Bounds =
        Bounds(null, null, ranges.entries.associate { (rat, range) -> rat.wire to listOf(range.first.toDouble(), range.last.toDouble()) })

    /** Every schema bound that has a [Ranges] constant. */
    private fun mirroredRanges(): Map<String, Bounds> = mapOf(
        key(SessionFile.KPI, "time_epoch") to bounds(Ranges.EPOCH_SECONDS),
        key(SessionFile.KPI, "pci") to intByRat(Ranges.PCI),
        key(SessionFile.KPI, "rsrp_dbm") to doubleByRat(Ranges.KPI_RSRP_DBM),
        key(SessionFile.KPI, "rsrq_db") to doubleByRat(Ranges.KPI_RSRQ_DB),
        key(SessionFile.KPI, "sinr_db") to doubleByRat(Ranges.KPI_SINR_DB),
        key(SessionFile.KPI, "lat") to bounds(Ranges.LAT),
        key(SessionFile.KPI, "lon") to bounds(Ranges.LON),
        key(SessionFile.TRACK, "lat") to bounds(Ranges.LAT),
        key(SessionFile.TRACK, "lon") to bounds(Ranges.LON),
        key(SessionFile.TRACK, "accuracy_m") to Bounds(Ranges.ACCURACY_M_MIN, null, null),
        key(SessionFile.TRACK, "altitude_m") to bounds(Ranges.ALTITUDE_M),
        key(SessionFile.TRACK, "speed_mps") to bounds(Ranges.SPEED_MPS),
        key(SessionFile.EVENTS, "pci") to intByRat(Ranges.PCI),
        key(SessionFile.EVENTS, "arfcn") to intByRat(Ranges.ARFCN),
        key(SessionFile.TRAFFIC, "loss_pct") to bounds(Ranges.LOSS_PCT),
        key(SessionFile.TRAFFIC, "http_code") to bounds(Ranges.HTTP_CODE),
        key(SessionFile.CELLS, "tac") to bounds(Ranges.TAC),
        key(SessionFile.CELLS, "cell_id") to longByRat(Ranges.CELL_ID),
        key(SessionFile.CELLS, "enb_id") to bounds(Ranges.ENB_ID),
        key(SessionFile.CELLS, "sector") to bounds(Ranges.SECTOR),
        key(SessionFile.CELLS, "pci") to intByRat(Ranges.PCI),
        key(SessionFile.CELLS, "band") to bounds(Ranges.BAND),
        key(SessionFile.CELLS, "dl_earfcn") to intByRat(Ranges.ARFCN),
        key(SessionFile.CELLS, "ul_earfcn") to intByRat(Ranges.ARFCN),
        key(SessionFile.CELLS, "dl_bw_mhz") to bounds(Ranges.BW_MHZ),
        key(SessionFile.CELLS, "ul_bw_mhz") to bounds(Ranges.BW_MHZ),
        key(SessionFile.CELLS, "rsrp_min") to doubleByRat(Ranges.KPI_RSRP_DBM),
        key(SessionFile.CELLS, "rsrp_max") to doubleByRat(Ranges.KPI_RSRP_DBM),
        key(SessionFile.CELLINFO, "pci") to intByRat(Ranges.PCI),
        key(SessionFile.CELLINFO, "arfcn") to intByRat(Ranges.ARFCN),
        key(SessionFile.CELLINFO, "tac") to bounds(Ranges.TAC),
        key(SessionFile.CELLINFO, "cell_id") to longByRat(Ranges.CELL_ID),
        key(SessionFile.CELLINFO, "bandwidth_khz") to bounds(Ranges.BANDWIDTH_KHZ),
        key(SessionFile.CELLINFO, "rsrp") to intByRat(Ranges.CELLINFO_RSRP),
        key(SessionFile.CELLINFO, "rsrq") to intByRat(Ranges.CELLINFO_RSRQ),
        key(SessionFile.CELLINFO, "sinr") to intByRat(Ranges.CELLINFO_SINR),
        key(SessionFile.CELLINFO, "rssi") to bounds(Ranges.RSSI),
        key(SessionFile.CELLINFO, "level") to bounds(Ranges.LEVEL),
        key(SessionFile.CELLINFO, "time_epoch") to bounds(Ranges.EPOCH_SECONDS),
        key(SessionFile.CELLINFO, "cqi") to bounds(Ranges.CQI),
        key(SessionFile.CELLINFO, "timing_advance") to bounds(Ranges.TIMING_ADVANCE),
        key(SessionFile.CELLINFO, "csi_rsrp") to bounds(Ranges.CSI_RSRP),
        key(SessionFile.CELLINFO, "csi_rsrq") to bounds(Ranges.CSI_RSRQ),
        key(SessionFile.CELLINFO, "csi_sinr") to bounds(Ranges.CSI_SINR),
        key(SessionFile.CELLINFO, "lat") to bounds(Ranges.LAT),
        key(SessionFile.CELLINFO, "lon") to bounds(Ranges.LON),
    )

    /**
     * Schema bounds without a [Ranges] constant: columns the app always leaves blank, and "0 or more"
     * lower bounds of values the producers compute from durations and counts. Listed with their
     * bounds, so that a change in columns.json still fails this test.
     */
    private fun unmirroredRanges(): Map<String, Bounds> {
        val zeroOrMore = Bounds(0.0, null, null)
        return mapOf(
            key(SessionFile.EVENTS, "frame") to Bounds(1.0, null, null),
            key(SessionFile.EVENTS, "setup_ms") to zeroOrMore,
            key(SessionFile.TRAFFIC, "seconds") to zeroOrMore,
            key(SessionFile.TRAFFIC, "rtt_min_ms") to zeroOrMore,
            key(SessionFile.TRAFFIC, "rtt_avg_ms") to zeroOrMore,
            key(SessionFile.TRAFFIC, "rtt_max_ms") to zeroOrMore,
            key(SessionFile.TRAFFIC, "mbps") to zeroOrMore,
            key(SessionFile.TRAFFIC, "bytes") to zeroOrMore,
            key(SessionFile.CELLS, "samples") to zeroOrMore,
            key(SessionFile.CELLINFO, "timestamp_ms") to zeroOrMore,
            key(SessionFile.CELLINFO, "age_ms") to zeroOrMore,
            key(SessionFile.CELLINFO, "sub_id") to zeroOrMore,
        )
    }

    private companion object {
        val NO_BOUNDS = Bounds(null, null, null)
    }
}
