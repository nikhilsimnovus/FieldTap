package com.fieldtap.core.radio

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.format.CellInfoRow
import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.CellRow
import com.fieldtap.format.CollectionMeta
import com.fieldtap.format.EventKind
import com.fieldtap.format.EventRat
import com.fieldtap.format.EventRow
import com.fieldtap.format.GapMeta
import com.fieldtap.format.KpiRow
import com.fieldtap.format.Rat
import com.fieldtap.format.ServingRat
import com.fieldtap.format.Severity
import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The golden session in tests/fixtures/android_session/, read with a parser of its own so these tests
 * do not depend on the :format readers they would otherwise be checking.
 *
 * [answers] rebuilds the 120 `requestCellInfoUpdate` answers that tests/fixtures/make_android_session.py
 * describes, from what Android reported in them: the cellinfo.csv columns an adapter would fill, grouped
 * by `seen_utc`. The derived columns (`stale`, `age_ms`, `time_epoch`) are not used: the answer's
 * elapsedRealtime comes from its wall time, as in the generator, where both clocks start together at
 * `START` and `BOOT_MS_AT_START`.
 *
 * The files are found at ../../tests/fixtures/android_session relative to android/core, the directory
 * Gradle runs the tests in. A missing fixture fails the test.
 */
internal object GoldenSession {
    const val DIRECTORY: String = "20260910-143000_Mall-walk-north-path"

    /** make_android_session.py `START`, 2026-09-10T14:30:00Z. */
    const val START_WALL_MS: Long = 1_789_050_600_000L

    /** make_android_session.py `BOOT_MS_AT_START`. */
    const val BOOT_MS_AT_START: Long = 25_323_456L

    private val COMMENT = Regex("android-api age_ms=(0|[1-9][0-9]*) src=(request|push)")

    val dir: File by lazy {
        val candidate = File("../../tests/fixtures/android_session/$DIRECTORY")
        if (!File(candidate, "session.json").isFile) {
            throw AssertionError("The golden session is missing: expected it at ${candidate.absolutePath}")
        }
        candidate
    }

    private val sessionJson: JsonObject by lazy {
        Json.parseToJsonElement(File(dir, "session.json").readText(Charsets.UTF_8)).jsonObject
    }

    fun answers(): List<CellInfoAnswer> {
        val groups = LinkedHashMap<String, MutableList<Map<String, String>>>()
        for (row in table("cellinfo.csv")) {
            groups.getOrPut(row.getValue("seen_utc")) { ArrayList() }.add(row)
        }
        return groups.map { (seen, rows) ->
            val wallMs = utcMs(seen)
            val first = rows.first()
            CellInfoAnswer(
                source = source(first.getValue("source")),
                cells = rows.map { snapshot(it) },
                subId = intOrNull(first.getValue("sub_id")),
                conditions = DeviceConditions(
                    screenOn = flag(first.getValue("screen_on")),
                    charging = flag(first.getValue("charging")),
                    wifiConnected = flag(first.getValue("wifi_connected")),
                ),
                observedWallMs = wallMs,
                observedElapsedMs = BOOT_MS_AT_START + (wallMs - START_WALL_MS),
            )
        }
    }

    fun cellInfoRows(): List<CellInfoRow> = table("cellinfo.csv").map { row ->
        val parsed = CellInfoRow(
            seenUtcMs = utcMs(row.getValue("seen_utc")),
            rat = rat(row.getValue("rat")),
            registered = flag(row.getValue("registered")),
            mcc = textOrNull(row.getValue("mcc")),
            mnc = textOrNull(row.getValue("mnc")),
            operator = textOrNull(row.getValue("operator")),
            pci = intOrNull(row.getValue("pci")),
            arfcn = intOrNull(row.getValue("arfcn")),
            bands = listField(row.getValue("bands")).map { it.toInt() },
            tac = intOrNull(row.getValue("tac")),
            cellId = longOrNull(row.getValue("cell_id")),
            bandwidthKhz = intOrNull(row.getValue("bandwidth_khz")),
            rsrp = intOrNull(row.getValue("rsrp")),
            rsrq = intOrNull(row.getValue("rsrq")),
            sinr = intOrNull(row.getValue("sinr")),
            rssi = intOrNull(row.getValue("rssi")),
            level = intOrNull(row.getValue("level")),
            additionalPlmns = listField(row.getValue("additional_plmns")),
            timeEpochMs = epochMs(row.getValue("time_epoch")),
            timestampMs = row.getValue("timestamp_ms").toLong(),
            ageMs = row.getValue("age_ms").toLong(),
            stale = flag(row.getValue("stale")),
            connectionStatus = intOrNull(row.getValue("connection_status")),
            source = source(row.getValue("source")),
            cqi = intOrNull(row.getValue("cqi")),
            timingAdvance = intOrNull(row.getValue("timing_advance")),
            csiRsrp = intOrNull(row.getValue("csi_rsrp")),
            csiRsrq = intOrNull(row.getValue("csi_rsrq")),
            csiSinr = intOrNull(row.getValue("csi_sinr")),
            screenOn = flag(row.getValue("screen_on")),
            charging = flag(row.getValue("charging")),
            wifiConnected = flag(row.getValue("wifi_connected")),
            subId = intOrNull(row.getValue("sub_id")),
        )
        if (parsed.plmn != textOrNull(row.getValue("plmn"))) {
            throw AssertionError("cellinfo.csv plmn ${row["plmn"]} is not mcc + mnc")
        }
        parsed
    }

    fun kpiRows(): List<KpiRow> = table("kpi.csv").map { row ->
        val comment = COMMENT.matchEntire(row.getValue("comment"))
            ?: throw AssertionError("kpi.csv comment ${row["comment"]}")
        KpiRow(
            timeEpochMs = epochMs(row.getValue("time_epoch")),
            rat = servingRat(row.getValue("rat")),
            pci = intOrNull(row.getValue("pci")),
            rsrpDbm = wholeDecimalOrNull(row.getValue("rsrp_dbm")),
            rsrqDb = wholeDecimalOrNull(row.getValue("rsrq_db")),
            sinrDb = wholeDecimalOrNull(row.getValue("sinr_db")),
            ageMs = comment.groupValues[1].toLong(),
            source = source(comment.groupValues[2]),
        )
    }

    fun cellRows(): List<CellRow> = table("cells.csv").map { row ->
        val parsed = CellRow(
            firstSeenUtcMs = utcMs(row.getValue("first_seen_utc")),
            rat = servingRat(row.getValue("rat")),
            mcc = textOrNull(row.getValue("mcc")),
            mnc = textOrNull(row.getValue("mnc")),
            tac = intOrNull(row.getValue("tac")),
            cellId = longOrNull(row.getValue("cell_id")),
            pci = row.getValue("pci").toInt(),
            band = intOrNull(row.getValue("band")),
            dlEarfcn = row.getValue("dl_earfcn").toInt(),
            ulEarfcn = intOrNull(row.getValue("ul_earfcn")),
            dlBwMhz = textOrNull(row.getValue("dl_bw_mhz"))?.toDouble(),
            ulBwMhz = textOrNull(row.getValue("ul_bw_mhz"))?.toDouble(),
            plausible = when (row.getValue("plausible")) {
                "True" -> true
                "False" -> false
                else -> throw AssertionError("cells.csv plausible ${row["plausible"]}")
            },
            operator = textOrNull(row.getValue("operator")),
            additionalPlmns = listField(row.getValue("additional_plmns")),
            samples = row.getValue("samples").toInt(),
            rsrpMin = wholeDecimalOrNull(row.getValue("rsrp_min")),
            rsrpMax = wholeDecimalOrNull(row.getValue("rsrp_max")),
        )
        if (parsed.plmn != textOrNull(row.getValue("plmn")) ||
            parsed.enbId?.toString() != textOrNull(row.getValue("enb_id")) ||
            parsed.sector?.toString() != textOrNull(row.getValue("sector"))
        ) {
            throw AssertionError("cells.csv derived columns disagree with CellRow: $row")
        }
        parsed
    }

    fun eventRows(): List<EventRow> = table("events.csv").map { row ->
        EventRow(
            timeUtcMs = utcMs(row.getValue("time_utc")),
            rat = EventRat.entries.first { it.wire == row.getValue("rat") },
            kind = EventKind.entries.first { it.wire == row.getValue("kind") },
            severity = Severity.entries.first { it.wire == row.getValue("severity") },
            title = row.getValue("title"),
            detail = textOrNull(row.getValue("detail")),
            pci = intOrNull(row.getValue("pci")),
            arfcn = intOrNull(row.getValue("arfcn")),
            cause = textOrNull(row.getValue("cause")),
        )
    }

    /** session.json `collection`, with the shares as written there (one decimal). */
    fun collection(): CollectionMeta {
        val collection = sessionJson.getValue("collection").jsonObject
        return CollectionMeta(
            medianFreshIntervalMs = collection.getValue("median_fresh_interval_ms").jsonPrimitive.long,
            shortIntervalPct = collection.getValue("short_interval_pct").jsonPrimitive.double,
            screenOnPct = collection.getValue("screen_on_pct").jsonPrimitive.double,
            wifiConnectedPct = collection.getValue("wifi_connected_pct").jsonPrimitive.double,
            chargingPct = collection.getValue("charging_pct").jsonPrimitive.double,
            freshSamples = collection.getValue("fresh_samples").jsonPrimitive.long,
            repeatsDropped = collection.getValue("repeats_dropped").jsonPrimitive.long,
            gaps = collection.getValue("gaps").jsonArray.map { element ->
                val gap = element.jsonObject
                GapMeta(
                    startUtcMs = utcMs(gap.getValue("start_utc").jsonPrimitive.content),
                    stopUtcMs = utcMs(gap.getValue("stop_utc").jsonPrimitive.content),
                    reason = gap.getValue("reason").jsonPrimitive.content,
                )
            },
        )
    }

    /** session.json `summary.plmns`, in file order. */
    fun plmns(): Map<String, Int> {
        val plmns = sessionJson.getValue("summary").jsonObject.getValue("plmns").jsonObject
        val out = LinkedHashMap<String, Int>()
        for ((plmn, count) in plmns) out[plmn] = count.jsonPrimitive.int
        return out
    }

    /** A `*_utc` value as Unix milliseconds. */
    fun utcMs(text: String): Long = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    /** A `time_epoch` value (`1789050600.400`) as Unix milliseconds, without a double. */
    fun epochMs(text: String): Long {
        val dot = text.indexOf('.')
        if (dot < 1 || text.length - dot - 1 != 3) throw AssertionError("time_epoch $text")
        return text.substring(0, dot).toLong() * 1_000 + text.substring(dot + 1).toLong()
    }

    /** Data records of a CSV file as header-to-value maps. Every record must end in CR LF. */
    fun table(name: String): List<Map<String, String>> {
        val text = File(dir, name).readText(Charsets.UTF_8)
        if (!text.endsWith("\r\n")) throw AssertionError("$name does not end in CR LF")
        val lines = text.substring(0, text.length - 2).split("\r\n")
        val header = parseRecord(lines.first())
        return lines.drop(1).mapIndexed { index, line ->
            val fields = parseRecord(line)
            if (fields.size != header.size) {
                throw AssertionError("$name line ${index + 2} has ${fields.size} fields, the header ${header.size}")
            }
            header.zip(fields).toMap()
        }
    }

    /** One CSV record without its line ending, as Python's csv module writes it. */
    fun parseRecord(line: String): List<String> {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            if (quoted) {
                if (ch == '"') {
                    if (index + 1 < line.length && line[index + 1] == '"') {
                        field.append('"')
                        index += 1
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> quoted = true
                    ',' -> {
                        fields.add(field.toString())
                        field.setLength(0)
                    }

                    else -> field.append(ch)
                }
            }
            index += 1
        }
        fields.add(field.toString())
        return fields
    }

    private fun snapshot(row: Map<String, String>): CellSnapshot = CellSnapshot(
        rat = rat(row.getValue("rat")),
        registered = flag(row.getValue("registered")),
        connectionStatus = intOrNull(row.getValue("connection_status")),
        timestampMs = row.getValue("timestamp_ms").toLong(),
        mcc = textOrNull(row.getValue("mcc")),
        mnc = textOrNull(row.getValue("mnc")),
        operatorLong = textOrNull(row.getValue("operator")),
        pci = intOrNull(row.getValue("pci")),
        arfcn = intOrNull(row.getValue("arfcn")),
        bands = listField(row.getValue("bands")).map { it.toInt() },
        tac = intOrNull(row.getValue("tac")),
        cellId = longOrNull(row.getValue("cell_id")),
        bandwidthKhz = intOrNull(row.getValue("bandwidth_khz")),
        additionalPlmns = listField(row.getValue("additional_plmns")),
        rsrp = intOrNull(row.getValue("rsrp")),
        rsrq = intOrNull(row.getValue("rsrq")),
        sinr = intOrNull(row.getValue("sinr")),
        csiRsrp = intOrNull(row.getValue("csi_rsrp")),
        csiRsrq = intOrNull(row.getValue("csi_rsrq")),
        csiSinr = intOrNull(row.getValue("csi_sinr")),
        rssi = intOrNull(row.getValue("rssi")),
        level = intOrNull(row.getValue("level")),
        cqi = intOrNull(row.getValue("cqi")),
        timingAdvance = intOrNull(row.getValue("timing_advance")),
    )

    private fun rat(wire: String): Rat = Rat.entries.first { it.wire == wire }

    private fun servingRat(wire: String): ServingRat = ServingRat.entries.first { it.wire == wire }

    private fun source(wire: String): CellInfoSource = CellInfoSource.entries.first { it.wire == wire }

    private fun flag(text: String): Boolean = when (text) {
        "1" -> true
        "0" -> false
        else -> throw AssertionError("not a 0/1 flag: $text")
    }

    private fun textOrNull(text: String): String? = text.ifEmpty { null }

    private fun intOrNull(text: String): Int? = if (text.isEmpty()) null else text.toInt()

    private fun longOrNull(text: String): Long? = if (text.isEmpty()) null else text.toLong()

    /** `-84.0` as the Android integer -84; the golden KPI values are all whole. */
    private fun wholeDecimalOrNull(text: String): Int? {
        if (text.isEmpty()) return null
        if (!text.endsWith(".0")) throw AssertionError("not a whole decimal: $text")
        return text.substring(0, text.length - 2).toInt()
    }

    private fun listField(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split(", ")
}
