package com.fieldtap.core.session

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.privacy.PrivacyZoneGate
import com.fieldtap.core.radio.ClassifiedAnswer
import com.fieldtap.core.radio.ClassifiedCell
import com.fieldtap.core.radio.CollectionStats
import com.fieldtap.core.radio.KpiCandidate
import com.fieldtap.core.radio.RadioRows
import com.fieldtap.core.radio.SamplingGap
import com.fieldtap.core.radio.ServingCellIdentity
import com.fieldtap.core.radio.ServingCellSelector
import com.fieldtap.core.radio.ServingCellTable
import com.fieldtap.core.radio.freshReference
import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.CellRow
import com.fieldtap.format.CollectionMeta
import com.fieldtap.format.Csv
import com.fieldtap.format.EventKind
import com.fieldtap.format.KpiRow
import com.fieldtap.format.LatLon
import com.fieldtap.format.Rat
import com.fieldtap.format.ServingRat
import com.fieldtap.format.SessionFile
import com.fieldtap.format.SessionFormat
import java.io.File
import java.math.BigDecimal

/**
 * The parts of a session derived from its CSV files, rebuilt from those files: `summary.plmns`, cells.csv and
 * `collection`. Recovery uses it when it closes a session its recorder could not finish, because the recorder
 * rewrites these only every 60 s: without the rebuild, a session killed in its first minute would say it has no
 * operator, no serving cell and no fresh sample next to the rows it holds.
 *
 * - Answers: cellinfo.csv holds every cell of every written answer, the rows of one answer together, so
 *   consecutive rows with the same `seen_utc` and `source` are one answer. Its serving cells are picked again by
 *   [ServingCellSelector] from the rows; each cell's freshness is its `stale` column.
 * - `collection`: the answers fed, in order, to [CollectionStats], as the radio pipeline fed them. The session's
 *   start, and each `Logging resumed` privacy_zone event before an answer, start a new interval chain that no
 *   sample measured before them begins. Answers with no cells leave no row, so the shares count only answers that
 *   had cells.
 * - `collection.gaps`: one entry per `sampling_gap` event, in order, with the event's cause. Its stop is the
 *   measurement time of the fresh sample of the answer the event came with (the same `seen_utc`), its start that of
 *   the fresh sample before it in the chain. An event with no such answer keeps its own time as the stop and the
 *   seconds of its detail before it as the start.
 * - `summary.plmns` and cells.csv: each kpi.csv row fed to [ServingCellTable] with the identity of its serving cell:
 *   the fresh primary or NSA-leg cell of the same RAT, PCI and `time_epoch` in cellinfo.csv. A kpi.csv row without
 *   one counts toward nothing.
 *
 * Blocking file IO. Throws IOException when a file cannot be read and IllegalArgumentException when a row cannot be
 * read; the caller then keeps what it had.
 *
 * Owner: workstream `session-core`.
 */
internal object SessionRebuild {
    data class Derived(val plmns: Map<String, Int>, val cells: List<CellRow>, val collection: CollectionMeta)

    private val GAP_SECONDS: Regex = Regex("([0-9]+(?:\\.[0-9]+)?) s$")
    private val KPI_COMMENT: Regex = Regex("age_ms=([0-9]+) src=([a-z]+)")

    fun derive(directory: File, startedUtcMs: Long): Derived {
        val cellInfo = Table.read(File(directory, SessionFile.CELLINFO.fileName), SessionFile.CELLINFO.header)
        val kpi = Table.read(File(directory, SessionFile.KPI.fileName), SessionFile.KPI.header)
        val events = Table.read(File(directory, SessionFile.EVENTS.fileName), SessionFile.EVENTS.header)

        val answers = answers(cellInfo)
        val resumes = events.rows
            .filter { it[events.col("kind")] == EventKind.PRIVACY_ZONE.wire && it[events.col("title")] == PrivacyZoneGate.RESUMED_TITLE }
            .map { utcMs(it[events.col("time_utc")]) }
            .sorted()
        val gapEvents = events.rows
            .filter { it[events.col("kind")] == EventKind.SAMPLING_GAP.wire }
            .map { GapEvent(utcMs(it[events.col("time_utc")]), it[events.col("cause")], it[events.col("detail")]) }

        val stats = CollectionStats()
        val gapsByEvent = arrayOfNulls<SamplingGap>(gapEvents.size)
        val identities = HashMap<IdentityKey, ServingSample>()
        var nextResume = 0
        var windowFromElapsedMs = Long.MIN_VALUE
        var previous: ClassifiedCell? = null
        for ((index, answer) in answers.withIndex()) {
            val offsetMs = answer.answer.observedWallMs - answer.answer.observedElapsedMs
            if (index == 0) {
                windowFromElapsedMs = startedUtcMs - offsetMs
                stats.onResume(windowFromElapsedMs)
            }
            while (nextResume < resumes.size && resumes[nextResume] <= answer.answer.observedWallMs) {
                windowFromElapsedMs = resumes[nextResume] - offsetMs
                stats.onResume(windowFromElapsedMs)
                previous = null
                nextResume++
            }
            stats.onAnswer(answer)

            val reference = answer.freshReference()?.takeIf { it.cell.timestampMs >= windowFromElapsedMs }
            val last = previous
            if (reference != null && (last == null || reference.cell.timestampMs > last.cell.timestampMs)) {
                if (last != null) {
                    val event = gapEvents.indices.firstOrNull { gapsByEvent[it] == null && gapEvents[it].timeUtcMs == answer.answer.observedWallMs }
                    if (event != null) {
                        gapsByEvent[event] = SamplingGap(last.measurementWallMs, reference.measurementWallMs, gapEvents[event].cause, gapEvents[event].timeUtcMs)
                    }
                }
                previous = reference
            }

            for (serving in listOfNotNull(answer.primary, answer.nsaSecondary)) {
                if (serving.stale) continue
                val identity = RadioRows.identity(serving.cell) ?: continue
                identities.putIfAbsent(
                    IdentityKey(identity.rat, identity.pci, serving.measurementWallMs),
                    ServingSample(identity, serving.cell.timestampMs),
                )
            }
        }
        for ((index, event) in gapEvents.withIndex()) {
            stats.onGap(gapsByEvent[index] ?: event.fallbackGap())
        }

        val table = ServingCellTable()
        for (row in kpi.rows) {
            val kpiRow = kpiRow(kpi, row)
            val sample = identities[IdentityKey(kpiRow.rat, kpiRow.pci, kpiRow.timeEpochMs)] ?: continue
            table.onKpiWritten(KpiCandidate(kpiRow, sample.identity, sample.measurementElapsedMs))
        }
        return Derived(table.plmns(), table.rows(), stats.snapshot())
    }

    private fun answers(table: Table): List<ClassifiedAnswer> {
        val out = ArrayList<ClassifiedAnswer>()
        var group = ArrayList<List<String>>()
        for (row in table.rows) {
            val first = group.firstOrNull()
            if (first != null && (first[table.col("seen_utc")] != row[table.col("seen_utc")] || first[table.col("source")] != row[table.col("source")])) {
                out += answer(table, group)
                group = ArrayList()
            }
            group += row
        }
        if (group.isNotEmpty()) out += answer(table, group)
        return out
    }

    private fun answer(table: Table, rows: List<List<String>>): ClassifiedAnswer {
        val cells = rows.map { row ->
            ClassifiedCell(
                cell = snapshot(table, row),
                stale = flag(row[table.col("stale")]),
                ageMs = long(row[table.col("age_ms")]) ?: throw IllegalArgumentException("cellinfo.csv row without age_ms"),
                measurementWallMs = epochMs(row[table.col("time_epoch")]),
            )
        }
        val first = rows.first()
        val serving = ServingCellSelector.select(cells.map { it.cell })
        val primary = serving.primary?.let { chosen -> cells.first { it.cell === chosen } }
        val leg = serving.nsaSecondary?.let { chosen -> cells.first { it.cell === chosen } }
        val fresh = if (primary != null) !primary.stale else cells.any { !it.stale }
        val answer = CellInfoAnswer(
            source = CellInfoSource.entries.firstOrNull { it.wire == first[table.col("source")] }
                ?: throw IllegalArgumentException("cellinfo.csv source ${first[table.col("source")]}"),
            cells = cells.map { it.cell },
            subId = int(first[table.col("sub_id")]),
            conditions = DeviceConditions(
                screenOn = flag(first[table.col("screen_on")]),
                charging = flag(first[table.col("charging")]),
                wifiConnected = flag(first[table.col("wifi_connected")]),
            ),
            observedWallMs = utcMs(first[table.col("seen_utc")]),
            observedElapsedMs = cells.first().cell.timestampMs + cells.first().ageMs,
        )
        return ClassifiedAnswer(answer, cells, primary, leg, fresh, repeat = !fresh)
    }

    private fun snapshot(table: Table, row: List<String>): CellSnapshot {
        fun textAt(column: String): String? = row[table.col(column)].ifEmpty { null }
        fun intAt(column: String): Int? = int(row[table.col(column)])
        return CellSnapshot(
            rat = Rat.entries.firstOrNull { it.wire == row[table.col("rat")] }
                ?: throw IllegalArgumentException("cellinfo.csv rat ${row[table.col("rat")]}"),
            registered = flag(row[table.col("registered")]),
            connectionStatus = intAt("connection_status"),
            timestampMs = long(row[table.col("timestamp_ms")]) ?: throw IllegalArgumentException("cellinfo.csv row without timestamp_ms"),
            mcc = textAt("mcc"),
            mnc = textAt("mnc"),
            operatorLong = textAt("operator"),
            pci = intAt("pci"),
            arfcn = intAt("arfcn"),
            bands = list(row[table.col("bands")]).map { it.toInt() },
            tac = intAt("tac"),
            cellId = long(row[table.col("cell_id")]),
            bandwidthKhz = intAt("bandwidth_khz"),
            additionalPlmns = list(row[table.col("additional_plmns")]),
            rsrp = intAt("rsrp"),
            rsrq = intAt("rsrq"),
            sinr = intAt("sinr"),
            csiRsrp = intAt("csi_rsrp"),
            csiRsrq = intAt("csi_rsrq"),
            csiSinr = intAt("csi_sinr"),
            rssi = intAt("rssi"),
            level = intAt("level"),
            cqi = intAt("cqi"),
            timingAdvance = intAt("timing_advance"),
        )
    }

    private fun kpiRow(table: Table, row: List<String>): KpiRow {
        val comment = KPI_COMMENT.find(row[table.col("comment")])
            ?: throw IllegalArgumentException("kpi.csv comment ${row[table.col("comment")]}")
        val lat = row[table.col("lat")]
        val lon = row[table.col("lon")]
        return KpiRow(
            timeEpochMs = epochMs(row[table.col("time_epoch")]),
            rat = ServingRat.entries.firstOrNull { it.wire == row[table.col("rat")] }
                ?: throw IllegalArgumentException("kpi.csv rat ${row[table.col("rat")]}"),
            pci = int(row[table.col("pci")]),
            rsrpDbm = decimalInt(row[table.col("rsrp_dbm")]),
            rsrqDb = decimalInt(row[table.col("rsrq_db")]),
            sinrDb = decimalInt(row[table.col("sinr_db")]),
            ageMs = comment.groupValues[1].toLong(),
            source = CellInfoSource.entries.firstOrNull { it.wire == comment.groupValues[2] }
                ?: throw IllegalArgumentException("kpi.csv source ${comment.groupValues[2]}"),
            position = if (lat.isEmpty() || lon.isEmpty()) null else LatLon(lat.toDouble(), lon.toDouble()),
        )
    }

    private fun flag(text: String): Boolean = when (text) {
        "1" -> true
        "0" -> false
        else -> throw IllegalArgumentException("not a 0 or 1 flag: $text")
    }

    private fun int(text: String): Int? = if (text.isEmpty()) null else text.toInt()

    private fun long(text: String): Long? = if (text.isEmpty()) null else text.toLong()

    /** `-84.0` as -84; the app writes Android's integers with one decimal. */
    private fun decimalInt(text: String): Int? = if (text.isEmpty()) null else BigDecimal(text).intValueExact()

    private fun list(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split(", ")

    private fun epochMs(text: String): Long = BigDecimal(text).movePointRight(3).longValueExact()

    private fun utcMs(text: String): Long = SessionFormat.parseUtc(text) ?: throw IllegalArgumentException("not a UTC time: $text")

    private data class IdentityKey(val rat: ServingRat, val pci: Int?, val measurementWallMs: Long)

    private class ServingSample(val identity: ServingCellIdentity, val measurementElapsedMs: Long)

    private class GapEvent(val timeUtcMs: Long, val cause: String, val detail: String) {
        fun fallbackGap(): SamplingGap {
            val seconds = GAP_SECONDS.find(detail)?.groupValues?.get(1)?.let { BigDecimal(it) } ?: BigDecimal.ZERO
            val startMs = timeUtcMs - seconds.movePointRight(3).toLong()
            return SamplingGap(startMs, timeUtcMs, cause.ifEmpty { "unknown" }, timeUtcMs)
        }
    }

    /** A CSV file read back: its data rows as fields, with column lookup by name. Missing file: no rows. */
    private class Table(private val header: List<String>, val rows: List<List<String>>) {
        fun col(name: String): Int {
            val index = header.indexOf(name)
            require(index >= 0) { "no column $name" }
            return index
        }

        companion object {
            fun read(file: File, expectedHeader: List<String>): Table {
                if (!file.isFile) return Table(expectedHeader, emptyList())
                val records = Csv.records(file.readText(Charsets.UTF_8)).filter { it.isNotEmpty() }
                if (records.isEmpty()) return Table(expectedHeader, emptyList())
                val header = Csv.parseRecord(records.first())
                require(header == expectedHeader) { "${file.name} has an unexpected header" }
                val rows = records.drop(1).map { record ->
                    Csv.parseRecord(record).also { require(it.size == header.size) { "${file.name} has a row of ${it.size} fields" } }
                }
                return Table(header, rows)
            }
        }
    }
}
