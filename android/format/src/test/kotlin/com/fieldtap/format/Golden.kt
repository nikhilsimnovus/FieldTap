package com.fieldtap.format

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The repository's contract files, read relative to android/format (the Gradle test working
 * directory). A missing file fails the test; it never skips.
 */
internal object Golden {
    const val DIR_NAME: String = "20260910-143000_Mall-walk-north-path"

    /** `started_utc` of the golden session, 2026-09-10T14:30:00.000+00:00. */
    const val STARTED_UTC_MS: Long = 1_789_050_600_000L

    private val repositoryRoot: File = File("../..")
    val sessionDir: File = File(repositoryRoot, "tests/fixtures/android_session/$DIR_NAME")
    val schemaFile: File = File(repositoryRoot, "schema/columns.json")

    /** Unix milliseconds [offsetMs] after the golden start. */
    fun at(offsetMs: Long): Long = STARTED_UTC_MS + offsetMs

    fun bytes(file: SessionFile): ByteArray = read(File(sessionDir, file.fileName))

    fun text(file: SessionFile): String = String(bytes(file), Charsets.UTF_8)

    fun schemaText(): String = String(read(schemaFile), Charsets.UTF_8)

    private fun read(file: File): ByteArray {
        if (!file.isFile) {
            throw AssertionError(
                "Missing contract file ${file.absolutePath}: the :format tests run from android/format and read " +
                    "schema/columns.json and tests/fixtures/android_session/ of the repository.",
            )
        }
        return file.readBytes()
    }
}

/** Parses golden CSV records into the row types, checking the columns the row types do not carry. */
internal object GoldenRows {
    private val epochText = Regex("([0-9]+)\\.([0-9]{3})")
    private val commentText = Regex(Schema.KPI_COMMENT_PATTERN)

    /** Every data record of [file] as a column-name map, after checking the header and field counts. */
    fun table(file: SessionFile): List<Map<String, String>> {
        val records = Csv.records(Golden.text(file))
        assertEquals("${file.fileName} header", file.header, Csv.parseRecord(records.first()))
        return records.drop(1).mapIndexed { index, record ->
            val fields = Csv.parseRecord(record)
            assertEquals("${file.fileName} line ${index + 2} field count", file.header.size, fields.size)
            file.header.zip(fields).toMap()
        }
    }

    fun kpi(row: Map<String, String>): KpiRow {
        assertEquals("", row.getValue("frame"))
        assertEquals("", row.getValue("meas_id"))
        val comment = commentText.matchEntire(row.getValue("comment"))
            ?: throw AssertionError("comment '${row.getValue("comment")}' does not match the schema pattern")
        return KpiRow(
            timeEpochMs = epochMs(row.getValue("time_epoch")),
            rat = ServingRat.entries.single { it.wire == row.getValue("rat") },
            pci = int(row.getValue("pci")),
            rsrpDbm = decimalInt(row.getValue("rsrp_dbm")),
            rsrqDb = decimalInt(row.getValue("rsrq_db")),
            sinrDb = decimalInt(row.getValue("sinr_db")),
            ageMs = comment.groupValues[1].toLong(),
            source = CellInfoSource.entries.single { it.wire == comment.groupValues[2] },
            position = position(row),
        )
    }

    fun track(row: Map<String, String>): TrackRow {
        assertEquals("android", row.getValue("source"))
        return TrackRow(
            timeUtcMs = utc(row.getValue("time_utc")),
            position = LatLon(row.getValue("lat").toDouble(), row.getValue("lon").toDouble()),
            accuracyM = double(row.getValue("accuracy_m")),
            altitudeM = double(row.getValue("altitude_m")),
            speedMps = double(row.getValue("speed_mps")),
            provider = FixProvider.entries.single { it.wire == row.getValue("provider") },
        )
    }

    fun event(row: Map<String, String>): EventRow {
        assertEquals("", row.getValue("frame"))
        assertEquals("", row.getValue("setup_ms"))
        return EventRow(
            timeUtcMs = utc(row.getValue("time_utc")),
            rat = EventRat.entries.single { it.wire == row.getValue("rat") },
            kind = EventKind.entries.single { it.wire == row.getValue("kind") },
            severity = Severity.entries.single { it.wire == row.getValue("severity") },
            title = row.getValue("title"),
            detail = textOrNull(row.getValue("detail")),
            pci = int(row.getValue("pci")),
            arfcn = int(row.getValue("arfcn")),
            cause = textOrNull(row.getValue("cause")),
        )
    }

    fun traffic(row: Map<String, String>): TrafficRow = TrafficRow(
        timeUtcMs = utc(row.getValue("time_utc")),
        test = TrafficTest.entries.single { it.wire == row.getValue("test") },
        target = row.getValue("target"),
        ok = flag(row.getValue("ok")),
        seconds = row.getValue("seconds").toDouble(),
        lossPct = double(row.getValue("loss_pct")),
        rttMinMs = double(row.getValue("rtt_min_ms")),
        rttAvgMs = double(row.getValue("rtt_avg_ms")),
        rttMaxMs = double(row.getValue("rtt_max_ms")),
        mbps = double(row.getValue("mbps")),
        bytes = long(row.getValue("bytes")),
        httpCode = int(row.getValue("http_code")),
        error = textOrNull(row.getValue("error")),
    )

    fun cell(row: Map<String, String>): CellRow {
        assertEquals("android", row.getValue("version"))
        val parsed = CellRow(
            firstSeenUtcMs = utc(row.getValue("first_seen_utc")),
            rat = ServingRat.entries.single { it.wire == row.getValue("rat") },
            mcc = textOrNull(row.getValue("mcc")),
            mnc = textOrNull(row.getValue("mnc")),
            tac = int(row.getValue("tac")),
            cellId = long(row.getValue("cell_id")),
            pci = row.getValue("pci").toInt(),
            band = int(row.getValue("band")),
            dlEarfcn = row.getValue("dl_earfcn").toInt(),
            ulEarfcn = int(row.getValue("ul_earfcn")),
            dlBwMhz = double(row.getValue("dl_bw_mhz")),
            ulBwMhz = double(row.getValue("ul_bw_mhz")),
            plausible = row.getValue("plausible") == "True",
            operator = textOrNull(row.getValue("operator")),
            additionalPlmns = list(row.getValue("additional_plmns")),
            samples = row.getValue("samples").toInt(),
            rsrpMin = decimalInt(row.getValue("rsrp_min")),
            rsrpMax = decimalInt(row.getValue("rsrp_max")),
        )
        assertEquals(row.getValue("plmn"), parsed.plmn ?: "")
        assertEquals(row.getValue("enb_id"), parsed.enbId?.toString() ?: "")
        assertEquals(row.getValue("sector"), parsed.sector?.toString() ?: "")
        return parsed
    }

    fun cellInfo(row: Map<String, String>): CellInfoRow {
        val parsed = CellInfoRow(
            seenUtcMs = utc(row.getValue("seen_utc")),
            rat = Rat.entries.single { it.wire == row.getValue("rat") },
            registered = flag(row.getValue("registered")),
            mcc = textOrNull(row.getValue("mcc")),
            mnc = textOrNull(row.getValue("mnc")),
            operator = textOrNull(row.getValue("operator")),
            pci = int(row.getValue("pci")),
            arfcn = int(row.getValue("arfcn")),
            bands = list(row.getValue("bands")).map { it.toInt() },
            tac = int(row.getValue("tac")),
            cellId = long(row.getValue("cell_id")),
            bandwidthKhz = int(row.getValue("bandwidth_khz")),
            rsrp = int(row.getValue("rsrp")),
            rsrq = int(row.getValue("rsrq")),
            sinr = int(row.getValue("sinr")),
            rssi = int(row.getValue("rssi")),
            level = int(row.getValue("level")),
            additionalPlmns = list(row.getValue("additional_plmns")),
            timeEpochMs = epochMs(row.getValue("time_epoch")),
            timestampMs = row.getValue("timestamp_ms").toLong(),
            ageMs = row.getValue("age_ms").toLong(),
            stale = flag(row.getValue("stale")),
            connectionStatus = int(row.getValue("connection_status")),
            source = CellInfoSource.entries.single { it.wire == row.getValue("source") },
            cqi = int(row.getValue("cqi")),
            timingAdvance = int(row.getValue("timing_advance")),
            csiRsrp = int(row.getValue("csi_rsrp")),
            csiRsrq = int(row.getValue("csi_rsrq")),
            csiSinr = int(row.getValue("csi_sinr")),
            screenOn = flag(row.getValue("screen_on")),
            charging = flag(row.getValue("charging")),
            wifiConnected = flag(row.getValue("wifi_connected")),
            subId = int(row.getValue("sub_id")),
            position = position(row),
        )
        assertEquals(row.getValue("plmn"), parsed.plmn ?: "")
        return parsed
    }

    fun utc(text: String): Long = SessionFormat.parseUtc(text) ?: throw AssertionError("not a *_utc value: '$text'")

    fun epochMs(text: String): Long {
        val match = epochText.matchEntire(text) ?: throw AssertionError("not a time_epoch value: '$text'")
        return match.groupValues[1].toLong() * 1_000L + match.groupValues[2].toLong()
    }

    private fun int(text: String): Int? = if (text.isEmpty()) null else text.toInt()

    private fun long(text: String): Long? = if (text.isEmpty()) null else text.toLong()

    private fun double(text: String): Double? = if (text.isEmpty()) null else text.toDouble()

    private fun textOrNull(text: String): String? = text.ifEmpty { null }

    private fun list(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split(", ")

    private fun decimalInt(text: String): Int? {
        if (text.isEmpty()) return null
        assertTrue("'$text' is an integer written with .0", text.endsWith(".0"))
        return text.dropLast(2).toInt()
    }

    private fun flag(text: String): Boolean = when (text) {
        "1" -> true
        "0" -> false
        else -> throw AssertionError("not a 0/1 flag: '$text'")
    }

    private fun position(row: Map<String, String>): LatLon? {
        val lat = row.getValue("lat")
        val lon = row.getValue("lon")
        assertEquals("lat and lon are filled together", lat.isEmpty(), lon.isEmpty())
        return if (lat.isEmpty()) null else LatLon(lat.toDouble(), lon.toDouble())
    }
}

/**
 * The golden session's rows and session.json as the app's pipelines would produce them, from the
 * values in tests/fixtures/make_android_session.py rather than from the golden text.
 */
internal object GoldenData {
    private const val LTE_ECI_SECTOR_1: Long = (84_532L shl 8) or 1L
    private const val LTE_ECI_SECTOR_2: Long = (84_532L shl 8) or 2L

    fun events(): List<EventRow> = listOf(
        EventRow(Golden.at(400), EventRat.LTE, EventKind.SERVING_CELL, Severity.INFO, "Serving cell", servingDetail(1, 212), pci = 212, arfcn = 66_786),
        EventRow(Golden.at(8_900), EventRat.NR, EventKind.NR_DISPLAY, Severity.INFO, "5G icon on", "override NR_NSA, network LTE"),
        EventRow(Golden.at(45_200), EventRat.NONE, EventKind.MARKER, Severity.INFO, "Marker", "North entrance " + ch(0x2013) + " badge reader, door 3"),
        EventRow(Golden.at(62_900), EventRat.NR, EventKind.NR_DISPLAY, Severity.INFO, "5G icon off", "override NONE, network LTE"),
        EventRow(Golden.at(64_400), EventRat.LTE, EventKind.SERVING_CELL, Severity.INFO, "Serving cell changed", servingDetail(2, 213), pci = 213, arfcn = 66_786),
        EventRow(Golden.at(70_900), EventRat.NR, EventKind.NR_DISPLAY, Severity.INFO, "5G icon on", "override NR_NSA, network LTE"),
        EventRow(Golden.at(104_900), EventRat.NONE, EventKind.SAMPLING_GAP, Severity.WARN, "Sampling gap", "no fresh cell info for 14.0 s", cause = "screen_off"),
    )

    /** The ping took 4135 ms and the download 10 031 250 bytes in 4000 ms: values that need exact rounding. */
    fun traffic(): List<TrafficRow> {
        val downloadSeconds = 4_000 / 1000.0
        return listOf(
            TrafficRow(Golden.at(30_000), TrafficTest.PING, "8.8.8.8", ok = true, seconds = 4_135 / 1000.0, lossPct = 0.0, rttMinMs = 38.2, rttAvgMs = 44.7, rttMaxMs = 58.9),
            TrafficRow(
                Golden.at(36_000),
                TrafficTest.DOWNLOAD,
                "https://probe.5gto6g.com/10MB.bin",
                ok = true,
                seconds = downloadSeconds,
                mbps = 10_031_250L * 8 / downloadSeconds / 1e6,
                bytes = 10_031_250L,
                httpCode = 200,
            ),
        )
    }

    fun cells(): List<CellRow> = listOf(
        lteCell(Golden.at(400), LTE_ECI_SECTOR_1, pci = 212, samples = 32, rsrpMin = -102, rsrpMax = -83),
        CellRow(
            firstSeenUtcMs = Golden.at(8_400),
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
            samples = 46,
            rsrpMin = -110,
            rsrpMax = -88,
        ),
        lteCell(Golden.at(64_400), LTE_ECI_SECTOR_2, pci = 213, samples = 22, rsrpMin = -90, rsrpMax = -84),
    )

    fun meta(): SessionMeta = SessionMeta(
        sessionId = "3f6c1a2e-8b7d-4e21-9c55-2a1f0b9d7e44",
        groupId = null,
        name = "Mall walk (north path)",
        note = "Walk-mode check of the north path, screen on, Wi-Fi off.",
        location = "National Mall, Washington DC",
        startedUtcMs = Golden.STARTED_UTC_MS,
        stoppedUtcMs = Golden.at(120_000),
        transport = TransportMeta(appVersion = "0.1.0", versionCode = 1),
        handset = HandsetMeta(
            manufacturer = "samsung",
            model = "SM-S921U",
            device = "e1q",
            androidVersion = "15",
            androidBuild = "AP3A.240905.015.A2",
            securityPatch = "2026-08-01",
            baseband = "S921USQU4BXH2",
            soc = "SM8650",
            platform = "pineapple",
            hardware = "qcom",
            operatorMccmnc = "311480",
            operatorName = "Verizon",
            simMccmnc = "311480",
            simOperatorName = "Verizon",
            networkType = "LTE",
        ),
        device = DeviceMeta(key = "app:7d0e5b8c-1f2a-4c3d-9e6f-0a1b2c3d4e5f", label = "SM-S921U"),
        files = SessionFile.CSV,
        summary = SummaryMeta(stoppedBy = "user", plmns = mapOf("311480" to 54)),
        capabilities = Capabilities(layer3 = false),
        collection = CollectionMeta(
            medianFreshIntervalMs = 2_000,
            shortIntervalPct = 88.3,
            screenOnPct = 88.3,
            wifiConnectedPct = 0.0,
            chargingPct = 0.0,
            freshSamples = 54,
            repeatsDropped = 66,
            gaps = listOf(GapMeta(startUtcMs = Golden.at(90_400), stopUtcMs = Golden.at(104_400), reason = "screen_off")),
        ),
        privacy = PrivacyMeta(
            locationPrecision = LocationPrecision.FULL,
            zonePauses = 0,
            consentVersion = "2026-09-01",
            consentSha256 = "85902057108ac48854a0c09cc66e7626789d3b7fb9f0c95c8ab6205520f5030f",
        ),
    )

    private fun servingDetail(sector: Int, pci: Int): String =
        "PLMN 311480 TAC 18704 eNB 84532 sector $sector PCI $pci EARFCN 66786 band 66"

    private fun lteCell(firstSeenUtcMs: Long, cellId: Long, pci: Int, samples: Int, rsrpMin: Int, rsrpMax: Int): CellRow = CellRow(
        firstSeenUtcMs = firstSeenUtcMs,
        rat = ServingRat.LTE,
        mcc = "311",
        mnc = "480",
        tac = 18_704,
        cellId = cellId,
        pci = pci,
        band = 66,
        dlEarfcn = 66_786,
        ulEarfcn = 132_322,
        dlBwMhz = 20.0,
        ulBwMhz = 20.0,
        plausible = true,
        operator = "Verizon",
        additionalPlmns = emptyList(),
        samples = samples,
        rsrpMin = rsrpMin,
        rsrpMax = rsrpMax,
    )
}
