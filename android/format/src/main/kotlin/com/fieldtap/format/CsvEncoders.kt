package com.fieldtap.format

/**
 * Row encoders: the exact bytes of every CSV record the app writes.
 *
 * Contract for every encoder (docs/SESSION-FORMAT.md, "CSV bytes" and "Numbers"):
 * - [CsvEncoder.headerLine] is `Csv.record(file.header)`: the exact header, CR LF at the end.
 * - [CsvEncoder.encode] returns one record with exactly `file.header.size` fields, built only with
 *   the helpers in [Csv], ending in CR LF.
 * - Integers `v.toString()`; decimals with the column's `decimals` via
 *   `BigDecimal(v).setScale(n, RoundingMode.HALF_EVEN).toPlainString()`, never locale formatting,
 *   never `-0.0`; Android integers written as decimals with no double involved (`-84` -> `-84.0`).
 * - Free text passes through [Csv.text] (CR and LF become spaces; minimal quoting by [Csv.record]).
 * - A position fills `lat` and `lon` together or leaves both blank.
 *
 * Encoders write the values they are given: range checks, the choice of serving cells and event
 * rules belong to the workstreams that build the rows. They never throw for a value: Android's
 * sentinels, NaN and infinity are written blank (see [Csv]).
 *
 * Tests (workstream `format`): each encoder, fed rows parsed from the golden session in
 * `tests/fixtures/android_session/20260910-143000_Mall-walk-north-path/`, reproduces that file byte
 * for byte, header included; plus the edge cases listed in android/ARCHITECTURE.md.
 *
 * Owner: workstream `format`.
 */
interface CsvEncoder<T> {
    /** The session file this encoder writes. */
    val file: SessionFile

    /** The header record, ending in CR LF. */
    val headerLine: String

    /** One record, ending in CR LF. */
    fun encode(row: T): String
}

/** kpi.csv. `frame` and `meas_id` blank; `comment` is [comment]. */
object KpiCsv : CsvEncoder<KpiRow> {
    override val file: SessionFile = SessionFile.KPI
    override val headerLine: String = Csv.record(SessionFile.KPI.header)

    override fun encode(row: KpiRow): String {
        val position = positionFields(row.position)
        return encodeRecord(
            SessionFile.KPI,
            listOf(
                BLANK, // frame: laptop sessions only
                Csv.epoch(row.timeEpochMs),
                row.rat.wire,
                BLANK, // meas_id: laptop sessions only
                Csv.int(row.pci),
                Csv.decimalOfInt(row.rsrpDbm, 1),
                Csv.decimalOfInt(row.rsrqDb, 1),
                Csv.decimalOfInt(row.sinrDb, 1),
                comment(row.ageMs, row.source),
                position.lat,
                position.lon,
            ),
        )
    }

    /**
     * `android-api age_ms=<ageMs> src=<request|push>`; matches [Schema.KPI_COMMENT_PATTERN]. A negative
     * age, which the freshness engine never produces, is written as 0 so that the pattern still holds.
     */
    fun comment(ageMs: Long, source: CellInfoSource): String =
        "android-api age_ms=" + ageMs.coerceAtLeast(0L) + " src=" + source.wire
}

/** track.csv. `source` is always `android`; accuracy `%.1f`, altitude `%.1f`, speed `%.2f`. */
object TrackCsv : CsvEncoder<TrackRow> {
    override val file: SessionFile = SessionFile.TRACK
    override val headerLine: String = Csv.record(SessionFile.TRACK.header)

    override fun encode(row: TrackRow): String {
        val position = positionFields(row.position)
        return encodeRecord(
            SessionFile.TRACK,
            listOf(
                Csv.utc(row.timeUtcMs),
                position.lat,
                position.lon,
                Csv.decimal(row.accuracyM, 1),
                Csv.decimal(row.altitudeM, 1),
                Csv.decimal(row.speedMps, 2),
                row.provider.wire,
                ANDROID,
            ),
        )
    }
}

/** events.csv. `frame` and `setup_ms` always blank. */
object EventsCsv : CsvEncoder<EventRow> {
    override val file: SessionFile = SessionFile.EVENTS
    override val headerLine: String = Csv.record(SessionFile.EVENTS.header)

    override fun encode(row: EventRow): String = encodeRecord(
        SessionFile.EVENTS,
        listOf(
            Csv.utc(row.timeUtcMs),
            row.rat.wire,
            row.kind.wire,
            row.severity.wire,
            Csv.text(row.title),
            Csv.text(row.detail),
            BLANK, // frame: laptop sessions only
            Csv.int(row.pci),
            Csv.int(row.arfcn),
            Csv.text(row.cause),
            BLANK, // setup_ms: laptop sessions only
        ),
    )
}

/** traffic.csv. `ok` `0`/`1`; seconds `%.2f`; loss and RTTs `%.1f`; mbps `%.3f`. */
object TrafficCsv : CsvEncoder<TrafficRow> {
    override val file: SessionFile = SessionFile.TRAFFIC
    override val headerLine: String = Csv.record(SessionFile.TRAFFIC.header)

    override fun encode(row: TrafficRow): String = encodeRecord(
        SessionFile.TRAFFIC,
        listOf(
            Csv.utc(row.timeUtcMs),
            row.test.wire,
            Csv.text(row.target),
            Csv.flag(row.ok),
            Csv.decimal(row.seconds, 2),
            Csv.decimal(row.lossPct, 1),
            Csv.decimal(row.rttMinMs, 1),
            Csv.decimal(row.rttAvgMs, 1),
            Csv.decimal(row.rttMaxMs, 1),
            Csv.decimal(row.mbps, 3),
            Csv.long(row.bytes),
            Csv.int(row.httpCode),
            Csv.text(row.error),
        ),
    )
}

/**
 * cells.csv. `version` always `android`; `plausible` `True`/`False`; bandwidths `%.1f`.
 *
 * Three columns follow from what is written rather than from the row, so that they can never
 * contradict it (`fieldtap validate` rejects a contradiction):
 * - `enb_id` and `sector` are blank whenever `cell_id` is blank;
 * - `plausible` is `True` exactly when the written `pci` and `dl_earfcn` are both filled
 *   (columns.json and SESSION-FORMAT.md); [CellRow.plausible] is not consulted;
 * - `additional_plmns` are sorted ascending, as Python's `sorted()` orders strings.
 */
object CellsCsv : CsvEncoder<CellRow> {
    override val file: SessionFile = SessionFile.CELLS
    override val headerLine: String = Csv.record(SessionFile.CELLS.header)

    override fun encode(row: CellRow): String {
        val cellId = Csv.long(row.cellId)
        val pci = Csv.int(row.pci)
        val dlEarfcn = Csv.int(row.dlEarfcn)
        return encodeRecord(
            SessionFile.CELLS,
            listOf(
                Csv.utc(row.firstSeenUtcMs),
                row.rat.wire,
                Csv.text(row.plmn),
                Csv.text(row.mcc),
                Csv.text(row.mnc),
                Csv.int(row.tac),
                cellId,
                if (cellId.isEmpty()) BLANK else Csv.long(row.enbId),
                if (cellId.isEmpty()) BLANK else Csv.int(row.sector),
                pci,
                Csv.int(row.band),
                dlEarfcn,
                Csv.int(row.ulEarfcn),
                Csv.decimal(row.dlBwMhz, 1),
                Csv.decimal(row.ulBwMhz, 1),
                ANDROID,
                Csv.pythonBool(pci.isNotEmpty() && dlEarfcn.isNotEmpty()),
                Csv.text(row.operator),
                Csv.list(row.additionalPlmns.sorted()),
                Csv.int(row.samples),
                Csv.decimalOfInt(row.rsrpMin, 1),
                Csv.decimalOfInt(row.rsrpMax, 1),
            ),
        )
    }
}

/**
 * cellinfo.csv. `registered`, `stale`, `screen_on`, `charging`, `wifi_connected` as `0`/`1`.
 * `bands` in the row's order (Android's); `additional_plmns` sorted ascending.
 */
object CellInfoCsv : CsvEncoder<CellInfoRow> {
    override val file: SessionFile = SessionFile.CELLINFO
    override val headerLine: String = Csv.record(SessionFile.CELLINFO.header)

    override fun encode(row: CellInfoRow): String {
        val position = positionFields(row.position)
        return encodeRecord(
            SessionFile.CELLINFO,
            listOf(
                Csv.utc(row.seenUtcMs),
                row.rat.wire,
                Csv.flag(row.registered),
                Csv.text(row.plmn),
                Csv.text(row.mcc),
                Csv.text(row.mnc),
                Csv.text(row.operator),
                Csv.int(row.pci),
                Csv.int(row.arfcn),
                Csv.list(row.bands.map { Csv.int(it) }.filter { it.isNotEmpty() }),
                Csv.int(row.tac),
                Csv.long(row.cellId),
                Csv.int(row.bandwidthKhz),
                Csv.int(row.rsrp),
                Csv.int(row.rsrq),
                Csv.int(row.sinr),
                Csv.int(row.rssi),
                Csv.int(row.level),
                Csv.list(row.additionalPlmns.sorted()),
                Csv.epoch(row.timeEpochMs),
                Csv.long(row.timestampMs),
                Csv.long(row.ageMs),
                Csv.flag(row.stale),
                Csv.int(row.connectionStatus),
                row.source.wire,
                Csv.int(row.cqi),
                Csv.int(row.timingAdvance),
                Csv.int(row.csiRsrp),
                Csv.int(row.csiRsrq),
                Csv.int(row.csiSinr),
                Csv.flag(row.screenOn),
                Csv.flag(row.charging),
                Csv.flag(row.wifiConnected),
                Csv.int(row.subId),
                position.lat,
                position.lon,
            ),
        )
    }
}

private const val BLANK: String = ""
private const val ANDROID: String = "android"

/** The `lat` and `lon` fields of one row. */
private class PositionFields(val lat: String, val lon: String)

private val NO_POSITION: PositionFields = PositionFields(BLANK, BLANK)

/** Both coordinates, or both blank when there is no position or either one cannot be written. */
private fun positionFields(position: LatLon?): PositionFields {
    if (position == null) return NO_POSITION
    val lat = Coordinates.format(position.lat)
    val lon = Coordinates.format(position.lon)
    return if (lat.isEmpty() || lon.isEmpty()) NO_POSITION else PositionFields(lat, lon)
}

/** The record, after checking that the encoder produced one field per column (a programming error otherwise). */
private fun encodeRecord(file: SessionFile, fields: List<String>): String {
    check(fields.size == file.header.size) { "${file.fileName}: ${fields.size} fields for ${file.header.size} columns" }
    return Csv.record(fields)
}
