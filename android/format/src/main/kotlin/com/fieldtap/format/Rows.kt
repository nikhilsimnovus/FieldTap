package com.fieldtap.format

/**
 * One typed row per CSV file. A `null` field is written blank ("blank means unknown"); the
 * encoders in CsvEncoders.kt own every byte of formatting. Columns the app always leaves blank
 * (`frame`, `meas_id`, `setup_ms`) and constant columns (`source` `android`, `version` `android`)
 * have no field.
 *
 * Times: every `...UtcMs` and `...EpochMs` field is Unix milliseconds. A measurement row carries
 * the modem's measurement time (answer wall clock minus age); every other row carries the wall
 * clock when the app observed it.
 *
 * Owner: workstream `format`.
 */

/** A position, full precision. Written `%.7f`. */
data class LatLon(val lat: Double, val lon: Double)

/** kpi.csv: one fresh serving-cell sample of one RAT. */
data class KpiRow(
    /** Measurement time; written as `time_epoch` with three decimals from integer milliseconds. */
    val timeEpochMs: Long,
    val rat: ServingRat,
    val pci: Int?,
    /** LTE `getRsrp()`, NR `getSsRsrp()`. Android's integer, written `-84.0`. */
    val rsrpDbm: Int?,
    /** LTE `getRsrq()`, NR `getSsRsrq()`. */
    val rsrqDb: Int?,
    /** LTE `getRssnr()`, NR `getSsSinr()`. */
    val sinrDb: Int?,
    /** Age when the answer arrived; goes into `comment` as `age_ms=<n>`. */
    val ageMs: Long,
    /** Goes into `comment` as `src=<request|push>`. */
    val source: CellInfoSource,
    /** Nearest fix within 5 s on the monotonic clock, or null for blank lat and lon. */
    val position: LatLon? = null,
)

/** track.csv: one GPS fix. Always has a position; a fix without one is never a row. */
data class TrackRow(
    val timeUtcMs: Long,
    val position: LatLon,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val speedMps: Double?,
    val provider: FixProvider,
)

/** events.csv. `frame` and `setup_ms` are always blank for the app. */
data class EventRow(
    val timeUtcMs: Long,
    val rat: EventRat,
    val kind: EventKind,
    val severity: Severity,
    /** The short line the report shows. Never names a place for [EventKind.PRIVACY_ZONE]. */
    val title: String,
    /** Blank when null. CR and LF become spaces. */
    val detail: String? = null,
    val pci: Int? = null,
    val arfcn: Int? = null,
    /** A token matching [Schema.TOKEN_PATTERN], e.g. `screen_off`, or null. */
    val cause: String? = null,
)

/** traffic.csv: one ping or download run. */
data class TrafficRow(
    /** When the test started. */
    val timeUtcMs: Long,
    val test: TrafficTest,
    /** The host pinged or the URL downloaded. */
    val target: String,
    val ok: Boolean,
    val seconds: Double,
    val lossPct: Double? = null,
    val rttMinMs: Double? = null,
    val rttAvgMs: Double? = null,
    val rttMaxMs: Double? = null,
    val mbps: Double? = null,
    val bytes: Long? = null,
    val httpCode: Int? = null,
    /** A short reason when [ok] is false, e.g. `no cellular network`. */
    val error: String? = null,
)

/** cells.csv: one distinct serving cell (primary, or the NSA NR leg). Rewritten whole at snapshots. */
data class CellRow(
    /** Measurement time of the first kpi.csv row from this cell. */
    val firstSeenUtcMs: Long,
    val rat: ServingRat,
    val mcc: String?,
    /** Leading zeros kept. */
    val mnc: String?,
    val tac: Int?,
    /** LTE ECI (28 bits) or NR NCI (36 bits). */
    val cellId: Long?,
    /**
     * Null when Android did not report it, as for some NSA legs; the row is still written, with `pci` blank and
     * `plausible` False. Android's unavailable value ([Schema.ANDROID_UNAVAILABLE]) is also written blank.
     */
    val pci: Int?,
    /** The first entry of `getBands()`. */
    val band: Int?,
    /** LTE EARFCN or NR-ARFCN; null (written blank, `plausible` False) when Android did not report it. */
    val dlEarfcn: Int?,
    /** LTE FDD only: dl plus the band's offset. Blank for TDD and NR. */
    val ulEarfcn: Int?,
    val dlBwMhz: Double?,
    val ulBwMhz: Double?,
    /**
     * The producer's plausibility verdict, for its own use. cells.csv never writes this value: [CellsCsv]
     * writes `True` exactly when the written `pci` and `dl_earfcn` are both filled, the rule
     * `fieldtap validate` enforces, so the column cannot contradict the row.
     */
    val plausible: Boolean,
    val operator: String?,
    /** Written ascending, `a, b`. */
    val additionalPlmns: List<String>,
    /** The number of kpi.csv rows written for this cell. */
    val samples: Int,
    val rsrpMin: Int?,
    val rsrpMax: Int?,
) {
    /** `mcc` followed by `mnc`, only when both are known. */
    val plmn: String? get() = if (mcc != null && mnc != null) mcc + mnc else null

    /** LTE only: `cell_id >> 8`. Blank for NR. */
    val enbId: Long? get() = if (rat == ServingRat.LTE) cellId?.shr(8) else null

    /** LTE only: `cell_id & 255`. Blank for NR. */
    val sector: Int? get() = if (rat == ServingRat.LTE) cellId?.and(0xFF)?.toInt() else null
}

/** cellinfo.csv: every cell of every answer, serving and neighbour, fresh and stale. */
data class CellInfoRow(
    /** Wall clock when the answer arrived. */
    val seenUtcMs: Long,
    val rat: Rat,
    val registered: Boolean,
    val mcc: String?,
    val mnc: String?,
    /** `getOperatorAlphaLong()`, else `getOperatorAlphaShort()`. */
    val operator: String?,
    val pci: Int?,
    val arfcn: Int?,
    /** In Android's order, written `a, b`. */
    val bands: List<Int>,
    val tac: Int?,
    val cellId: Long?,
    val bandwidthKhz: Int?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val rssi: Int?,
    val level: Int?,
    /** Written ascending, `a, b`. */
    val additionalPlmns: List<String>,
    /** Measurement time: the answer's wall clock minus [ageMs]. */
    val timeEpochMs: Long,
    /** `CellInfo.getTimestampMillis()`: milliseconds since boot when the modem measured. */
    val timestampMs: Long,
    /** elapsedRealtime at the answer minus [timestampMs]. */
    val ageMs: Long,
    /** True when this cell with this [timestampMs] was already logged. */
    val stale: Boolean,
    /** 0 none, 1 primary serving, 2 secondary serving; null when Android reports it unknown. */
    val connectionStatus: Int?,
    val source: CellInfoSource,
    val cqi: Int?,
    val timingAdvance: Int?,
    val csiRsrp: Int?,
    val csiRsrq: Int?,
    val csiSinr: Int?,
    val screenOn: Boolean,
    val charging: Boolean,
    val wifiConnected: Boolean,
    /** Default data subscription id: a local slot number, never a subscriber identity. */
    val subId: Int?,
    val position: LatLon? = null,
) {
    val plmn: String? get() = if (mcc != null && mnc != null) mcc + mnc else null
}
