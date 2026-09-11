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
import java.io.Closeable
import java.io.File
import java.math.BigDecimal

/**
 * The parts of a session derived from its CSV files, rebuilt from those files: `summary.plmns`, cells.csv and
 * `collection`, the `privacy_zone` pauses its events record and the newest time its rows carry. Recovery uses it when
 * it closes a session its recorder could not finish, because the recorder rewrites these only every 60 s: without the
 * rebuild, a session killed in its first minute would say it has no operator, no serving cell and no fresh sample next
 * to the rows it holds.
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
 * - [Derived.zonePauseEvents]: the `Logging paused in a privacy zone` events. A pause without a fix that a fix inside a
 *   zone later confirms writes no event, so this is a lower bound of `privacy.zone_pauses`.
 * - [Derived.newestRowUtcMs]: the latest `time_epoch`, `seen_utc` or `time_utc` in the five CSV files.
 *
 * Memory stays bounded however long the session ran: every file is read one record at a time, one answer is held at a
 * time, and kpi.csv is read alongside cellinfo.csv, both being in the order the recorder wrote them, keeping only the
 * serving samples of the last few minutes to match its rows against.
 *
 * Blocking file IO. Throws IOException when a file cannot be read and IllegalArgumentException when a row cannot be
 * read; the caller then keeps what it had.
 *
 * Owner: workstream `session-core`.
 */
internal object SessionRebuild {
    data class Derived(
        val plmns: Map<String, Int>,
        val cells: List<CellRow>,
        val collection: CollectionMeta,
        val zonePauseEvents: Int = 0,
        val newestRowUtcMs: Long? = null,
    )

    /** How long after a kpi row's measurement the answer that carried it can have arrived: well past the 11 s KPI age limit. */
    private const val ANSWER_LOOKAHEAD_MS: Long = 60_000

    /** Serving samples measured this long before the kpi row being matched are forgotten. */
    private const val SAMPLE_WINDOW_MS: Long = 300_000

    /** At most this many serving samples are kept for matching, whatever the clock did. */
    private const val MAX_WINDOW_SAMPLES: Int = 20_000

    private val GAP_SECONDS: Regex = Regex("([0-9]+(?:\\.[0-9]+)?) s$")
    private val KPI_COMMENT: Regex = Regex("age_ms=([0-9]+) src=([a-z]+)")

    fun derive(directory: File, startedUtcMs: Long): Derived {
        val newest = NewestTime()
        val events = readEvents(File(directory, SessionFile.EVENTS.fileName), newest)
        for (file in listOf(SessionFile.TRACK, SessionFile.TRAFFIC)) readTimes(File(directory, file.fileName), file.header, newest)

        val chain = Chain(startedUtcMs, events)
        val window = ServingWindow()
        val table = ServingCellTable()
        RowReader.open(File(directory, SessionFile.CELLINFO.fileName), SessionFile.CELLINFO.header).use { cellInfo ->
            RowReader.open(File(directory, SessionFile.KPI.fileName), SessionFile.KPI.header).use { kpi ->
                val answers = AnswerReader(cellInfo)
                var ahead = answers.next()
                fun consume(answer: ClassifiedAnswer) {
                    chain.onAnswer(answer)
                    window.add(answer)
                    newest.add(answer.answer.observedWallMs)
                    for (cell in answer.cells) newest.add(cell.measurementWallMs)
                }
                while (true) {
                    val row = kpi.next() ?: break
                    val kpiRow = kpiRow(kpi, row)
                    newest.add(kpiRow.timeEpochMs)
                    while (ahead != null && ahead.answer.observedWallMs <= kpiRow.timeEpochMs + ANSWER_LOOKAHEAD_MS) {
                        consume(ahead)
                        ahead = answers.next()
                    }
                    window.forgetBefore(kpiRow.timeEpochMs - SAMPLE_WINDOW_MS)
                    val sample = window[IdentityKey(kpiRow.rat, kpiRow.pci, kpiRow.timeEpochMs)] ?: continue
                    table.onKpiWritten(KpiCandidate(kpiRow, sample.identity, sample.measurementElapsedMs))
                }
                while (ahead != null) {
                    consume(ahead)
                    ahead = answers.next()
                }
            }
        }
        return Derived(table.plmns(), table.rows(), chain.collection(), events.zonePauses, newest.value)
    }

    /** The events that shape the rebuild, read one row at a time. */
    private fun readEvents(file: File, newest: NewestTime): EventFacts {
        val resumes = ArrayList<Long>()
        val gaps = ArrayList<GapEvent>()
        var pauses = 0
        RowReader.open(file, SessionFile.EVENTS.header).use { rows ->
            val time = rows.col("time_utc")
            val kind = rows.col("kind")
            val title = rows.col("title")
            val cause = rows.col("cause")
            val detail = rows.col("detail")
            while (true) {
                val row = rows.next() ?: break
                val atMs = utcMs(row[time])
                newest.add(atMs)
                when (row[kind]) {
                    EventKind.PRIVACY_ZONE.wire -> when (row[title]) {
                        PrivacyZoneGate.RESUMED_TITLE -> resumes += atMs
                        PrivacyZoneGate.PAUSED_TITLE -> pauses++
                    }
                    EventKind.SAMPLING_GAP.wire -> gaps += GapEvent(atMs, row[cause], row[detail])
                }
            }
        }
        resumes.sort()
        return EventFacts(resumes, gaps, pauses)
    }

    /** The `time_utc` of every row of [file], into [newest]. */
    private fun readTimes(file: File, header: List<String>, newest: NewestTime) {
        RowReader.open(file, header).use { rows ->
            val time = rows.col("time_utc")
            while (true) {
                val row = rows.next() ?: break
                newest.add(utcMs(row[time]))
            }
        }
    }

    private fun answer(rows: RowReader, group: List<List<String>>): ClassifiedAnswer {
        val cells = group.map { row ->
            ClassifiedCell(
                cell = snapshot(rows, row),
                stale = flag(row[rows.col("stale")]),
                ageMs = long(row[rows.col("age_ms")]) ?: throw IllegalArgumentException("cellinfo.csv row without age_ms"),
                measurementWallMs = epochMs(row[rows.col("time_epoch")]),
            )
        }
        val first = group.first()
        val serving = ServingCellSelector.select(cells.map { it.cell })
        val primary = serving.primary?.let { chosen -> cells.first { it.cell === chosen } }
        val leg = serving.nsaSecondary?.let { chosen -> cells.first { it.cell === chosen } }
        val fresh = if (primary != null) !primary.stale else cells.any { !it.stale }
        val answer = CellInfoAnswer(
            source = CellInfoSource.entries.firstOrNull { it.wire == first[rows.col("source")] }
                ?: throw IllegalArgumentException("cellinfo.csv source ${first[rows.col("source")]}"),
            cells = cells.map { it.cell },
            subId = int(first[rows.col("sub_id")]),
            conditions = DeviceConditions(
                screenOn = flag(first[rows.col("screen_on")]),
                charging = flag(first[rows.col("charging")]),
                wifiConnected = flag(first[rows.col("wifi_connected")]),
            ),
            observedWallMs = utcMs(first[rows.col("seen_utc")]),
            observedElapsedMs = cells.first().cell.timestampMs + cells.first().ageMs,
        )
        return ClassifiedAnswer(answer, cells, primary, leg, fresh, repeat = !fresh)
    }

    private fun snapshot(rows: RowReader, row: List<String>): CellSnapshot {
        fun textAt(column: String): String? = row[rows.col(column)].ifEmpty { null }
        fun intAt(column: String): Int? = int(row[rows.col(column)])
        return CellSnapshot(
            rat = Rat.entries.firstOrNull { it.wire == row[rows.col("rat")] }
                ?: throw IllegalArgumentException("cellinfo.csv rat ${row[rows.col("rat")]}"),
            registered = flag(row[rows.col("registered")]),
            connectionStatus = intAt("connection_status"),
            timestampMs = long(row[rows.col("timestamp_ms")]) ?: throw IllegalArgumentException("cellinfo.csv row without timestamp_ms"),
            mcc = textAt("mcc"),
            mnc = textAt("mnc"),
            operatorLong = textAt("operator"),
            pci = intAt("pci"),
            arfcn = intAt("arfcn"),
            bands = list(row[rows.col("bands")]).map { it.toInt() },
            tac = intAt("tac"),
            cellId = long(row[rows.col("cell_id")]),
            bandwidthKhz = intAt("bandwidth_khz"),
            additionalPlmns = list(row[rows.col("additional_plmns")]),
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

    private fun kpiRow(rows: RowReader, row: List<String>): KpiRow {
        val comment = KPI_COMMENT.find(row[rows.col("comment")])
            ?: throw IllegalArgumentException("kpi.csv comment ${row[rows.col("comment")]}")
        val lat = row[rows.col("lat")]
        val lon = row[rows.col("lon")]
        return KpiRow(
            timeEpochMs = epochMs(row[rows.col("time_epoch")]),
            rat = ServingRat.entries.firstOrNull { it.wire == row[rows.col("rat")] }
                ?: throw IllegalArgumentException("kpi.csv rat ${row[rows.col("rat")]}"),
            pci = int(row[rows.col("pci")]),
            rsrpDbm = decimalInt(row[rows.col("rsrp_dbm")]),
            rsrqDb = decimalInt(row[rows.col("rsrq_db")]),
            sinrDb = decimalInt(row[rows.col("sinr_db")]),
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

    /** The interval chains, sampling gaps and statistics of the answers, fed in file order. */
    private class Chain(private val startedUtcMs: Long, private val events: EventFacts) {
        private val stats = CollectionStats()
        private val gapsByEvent = arrayOfNulls<SamplingGap>(events.gaps.size)
        private val unmatchedGapsByTime = HashMap<Long, ArrayDeque<Int>>().also { byTime ->
            events.gaps.forEachIndexed { index, gap -> byTime.getOrPut(gap.timeUtcMs) { ArrayDeque() }.addLast(index) }
        }
        private var started = false
        private var nextResume = 0
        private var windowFromElapsedMs = Long.MIN_VALUE
        private var previous: ClassifiedCell? = null

        fun onAnswer(answer: ClassifiedAnswer) {
            val offsetMs = answer.answer.observedWallMs - answer.answer.observedElapsedMs
            if (!started) {
                started = true
                windowFromElapsedMs = startedUtcMs - offsetMs
                stats.onResume(windowFromElapsedMs)
            }
            while (nextResume < events.resumes.size && events.resumes[nextResume] <= answer.answer.observedWallMs) {
                windowFromElapsedMs = events.resumes[nextResume] - offsetMs
                stats.onResume(windowFromElapsedMs)
                previous = null
                nextResume++
            }
            stats.onAnswer(answer)

            val reference = answer.freshReference()?.takeIf { it.cell.timestampMs >= windowFromElapsedMs } ?: return
            val last = previous
            if (last != null && reference.cell.timestampMs <= last.cell.timestampMs) return
            if (last != null) {
                val event = unmatchedGapsByTime[answer.answer.observedWallMs]?.removeFirstOrNull()
                if (event != null) {
                    gapsByEvent[event] = SamplingGap(last.measurementWallMs, reference.measurementWallMs, events.gaps[event].cause, events.gaps[event].timeUtcMs)
                }
            }
            previous = reference
        }

        fun collection(): CollectionMeta {
            for ((index, event) in events.gaps.withIndex()) stats.onGap(gapsByEvent[index] ?: event.fallbackGap())
            return stats.snapshot()
        }
    }

    /** The fresh serving samples of recent answers, by the RAT, PCI and measurement time a kpi.csv row carries. */
    private class ServingWindow {
        private val samples = LinkedHashMap<IdentityKey, ServingSample>()

        fun add(answer: ClassifiedAnswer) {
            for (serving in listOfNotNull(answer.primary, answer.nsaSecondary)) {
                if (serving.stale) continue
                val identity = RadioRows.identity(serving.cell) ?: continue
                samples.putIfAbsent(IdentityKey(identity.rat, identity.pci, serving.measurementWallMs), ServingSample(identity, serving.cell.timestampMs))
            }
            val oldest = samples.keys.iterator()
            while (samples.size > MAX_WINDOW_SAMPLES && oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }

        operator fun get(key: IdentityKey): ServingSample? = samples[key]

        /** Forgets samples, oldest added first, until one measured at or after [utcMs]. */
        fun forgetBefore(utcMs: Long) {
            val oldest = samples.keys.iterator()
            while (oldest.hasNext()) {
                if (oldest.next().measurementWallMs >= utcMs) return
                oldest.remove()
            }
        }
    }

    /** Groups cellinfo.csv rows into answers, one answer in memory at a time. */
    private class AnswerReader(private val rows: RowReader) {
        private val seen = rows.col("seen_utc")
        private val source = rows.col("source")
        private var pending: List<String>? = null

        fun next(): ClassifiedAnswer? {
            val first = pending ?: rows.next() ?: return null
            pending = null
            val group = arrayListOf(first)
            while (true) {
                val row = rows.next() ?: break
                if (row[seen] != first[seen] || row[source] != first[source]) {
                    pending = row
                    break
                }
                group += row
            }
            return answer(rows, group)
        }
    }

    /** The latest time seen. */
    private class NewestTime {
        var value: Long? = null
            private set

        fun add(utcMs: Long) {
            val current = value
            if (current == null || utcMs > current) value = utcMs
        }
    }

    private class EventFacts(val resumes: List<Long>, val gaps: List<GapEvent>, val zonePauses: Int)

    private data class IdentityKey(val rat: ServingRat, val pci: Int?, val measurementWallMs: Long)

    private class ServingSample(val identity: ServingCellIdentity, val measurementElapsedMs: Long)

    private class GapEvent(val timeUtcMs: Long, val cause: String, val detail: String) {
        fun fallbackGap(): SamplingGap {
            val seconds = GAP_SECONDS.find(detail)?.groupValues?.get(1)?.let { BigDecimal(it) } ?: BigDecimal.ZERO
            val startMs = timeUtcMs - seconds.movePointRight(3).toLong()
            return SamplingGap(startMs, timeUtcMs, cause.ifEmpty { "unknown" }, timeUtcMs)
        }
    }

    /** A session CSV read one row at a time, its header checked, with column lookup by name. A missing or empty file has no rows. */
    private class RowReader private constructor(
        private val fileName: String,
        private val records: Csv.RecordReader?,
        private val header: List<String>,
    ) : Closeable {
        fun col(name: String): Int {
            val index = header.indexOf(name)
            require(index >= 0) { "no column $name" }
            return index
        }

        fun next(): List<String>? {
            val reader = records ?: return null
            while (true) {
                val record = reader.next() ?: return null
                if (record.isEmpty()) continue
                return Csv.parseRecord(record).also { require(it.size == header.size) { "$fileName has a row of ${it.size} fields" } }
            }
        }

        override fun close() {
            records?.close()
        }

        companion object {
            fun open(file: File, expectedHeader: List<String>): RowReader {
                if (!file.isFile) return RowReader(file.name, null, expectedHeader)
                val records = Csv.RecordReader(file.reader(Charsets.UTF_8))
                try {
                    var first = records.next()
                    while (first != null && first.isEmpty()) first = records.next()
                    if (first == null) {
                        records.close()
                        return RowReader(file.name, null, expectedHeader)
                    }
                    val header = Csv.parseRecord(first)
                    require(header == expectedHeader) { "${file.name} has an unexpected header" }
                    return RowReader(file.name, records, header)
                } catch (e: Throwable) {
                    records.close()
                    throw e
                }
            }
        }
    }
}
