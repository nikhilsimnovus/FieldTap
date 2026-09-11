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
 * Tests (workstream `format`): each encoder, fed rows parsed from the golden session in
 * `tests/fixtures/android_session/20260910-143000_Mall-walk-north-path/`, reproduces that file byte
 * for byte, header included; plus the edge cases listed in android/ARCHITECTURE.md.
 *
 * Owner: workstream `format`.
 */
interface CsvEncoder<T> {
    val file: SessionFile

    /** The header record, ending in CR LF. */
    val headerLine: String

    /** One record, ending in CR LF. */
    fun encode(row: T): String
}

/** kpi.csv. `frame` and `meas_id` blank; `comment` is [comment]. */
object KpiCsv : CsvEncoder<KpiRow> {
    override val file: SessionFile = SessionFile.KPI
    override val headerLine: String get() = TODO("format")
    override fun encode(row: KpiRow): String = TODO("format")

    /** `android-api age_ms=<ageMs> src=<request|push>`; matches [Schema.KPI_COMMENT_PATTERN]. */
    fun comment(ageMs: Long, source: CellInfoSource): String = TODO("format")
}

/** track.csv. `source` is always `android`; accuracy `%.1f`, altitude `%.1f`, speed `%.2f`. */
object TrackCsv : CsvEncoder<TrackRow> {
    override val file: SessionFile = SessionFile.TRACK
    override val headerLine: String get() = TODO("format")
    override fun encode(row: TrackRow): String = TODO("format")
}

/** events.csv. `frame` and `setup_ms` always blank. */
object EventsCsv : CsvEncoder<EventRow> {
    override val file: SessionFile = SessionFile.EVENTS
    override val headerLine: String get() = TODO("format")
    override fun encode(row: EventRow): String = TODO("format")
}

/** traffic.csv. `ok` `0`/`1`; seconds `%.2f`; loss and RTTs `%.1f`; mbps `%.3f`. */
object TrafficCsv : CsvEncoder<TrafficRow> {
    override val file: SessionFile = SessionFile.TRAFFIC
    override val headerLine: String get() = TODO("format")
    override fun encode(row: TrafficRow): String = TODO("format")
}

/** cells.csv. `version` always `android`; `plausible` `True`/`False`; bandwidths `%.1f`. */
object CellsCsv : CsvEncoder<CellRow> {
    override val file: SessionFile = SessionFile.CELLS
    override val headerLine: String get() = TODO("format")
    override fun encode(row: CellRow): String = TODO("format")
}

/** cellinfo.csv. `registered`, `stale`, `screen_on`, `charging`, `wifi_connected` as `0`/`1`. */
object CellInfoCsv : CsvEncoder<CellInfoRow> {
    override val file: SessionFile = SessionFile.CELLINFO
    override val headerLine: String get() = TODO("format")
    override fun encode(row: CellInfoRow): String = TODO("format")
}
