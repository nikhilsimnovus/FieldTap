package com.fieldtap.core.radio

import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.format.CellInfoRow
import com.fieldtap.format.KpiRow
import com.fieldtap.format.ServingRat

/** What cells.csv and the serving_cell event need to know about a serving cell. */
data class ServingCellIdentity(
    val rat: ServingRat,
    val mcc: String?,
    val mnc: String?,
    val tac: Int?,
    val cellId: Long?,
    val pci: Int?,
    val arfcn: Int?,
    /** The first entry of `bands`. */
    val band: Int?,
    val bandwidthKhz: Int?,
    val operator: String?,
    val additionalPlmns: List<String>,
) {
    val plmn: String? get() = if (mcc != null && mnc != null) mcc + mnc else null
}

/** A kpi.csv row waiting for its position, with what the cell table needs once it is written. */
data class KpiCandidate(
    /** `position` is null until the GPS join fills it. */
    val row: KpiRow,
    val identity: ServingCellIdentity,
    /** The sample's `timestampMs`: the GPS join key. */
    val measurementElapsedMs: Long,
)

/** A cellinfo.csv row waiting for its position. */
data class CellInfoCandidate(
    /** `position` is null until the GPS join fills it. */
    val row: CellInfoRow,
    /** The cell's `timestampMs`: the GPS join key (stale rows join at the original measurement). */
    val measurementElapsedMs: Long,
)

/**
 * Builds rows from a classified answer. Pure; positions are left null.
 *
 * [cellInfo]: one row per cell, in answer order, fresh and stale (`stale` flag set), columns exactly
 * as docs/SESSION-FORMAT.md "cellinfo.csv" describes: `rsrp` is NR SS-RSRP else CSI-RSRP (same for
 * rsrq, sinr); `operator` is long name else short; `plmn` only when mcc and mnc are both known;
 * values outside [com.fieldtap.format.Ranges] written blank; conditions and `sub_id` from the answer.
 *
 * [kpi]: at most one row per RAT per answer: the primary serving cell (LTE or NR) and the NSA NR
 * leg, each only when it is fresh (not stale) and its age is at most
 * `CadencePolicy.maxKpiAgeMs(answer.conditions)`. `rsrp_dbm` is LTE RSRP or NR SS-RSRP (never CSI),
 * blank when outside the KPI range; `time_epoch` is the cell's own measurement time.
 *
 * Tests: rows of the golden cellinfo.csv and kpi.csv rebuilt from equivalent snapshots, field by
 * field; an 11001 ms sample on the long interval and a 2501 ms sample on the short one are not KPI
 * rows; CSI fallback; out-of-range values blank.
 *
 * Owner: workstream `radio-core`.
 */
object RadioRows {
    fun cellInfo(classified: ClassifiedAnswer): List<CellInfoCandidate> = TODO("radio-core")

    fun kpi(classified: ClassifiedAnswer): List<KpiCandidate> = TODO("radio-core")

    /** Null for a RAT other than LTE or NR. */
    fun identity(cell: CellSnapshot): ServingCellIdentity? = TODO("radio-core")
}

/**
 * Cell identity arithmetic and plausibility.
 *
 * - [lteUlEarfcn]: LTE FDD UL EARFCN = DL EARFCN + the band's offset from TS 36.101 table 5.7.3-1
 *   (band 66: DL 66786 -> UL 132322). Null for TDD bands, unknown bands and NR.
 * - [isPlausible]: `pci` and `dlEarfcn` both present and inside [com.fieldtap.format.Ranges] for the RAT.
 * - [bandwidthMhz]: kHz / 1000; null for null or out of range.
 * - [inRange]: the value when inside the range (or when range is null), else null.
 *
 * Owner: workstream `radio-core`.
 */
object CellIdentityMath {
    fun lteUlEarfcn(dlEarfcn: Int, band: Int?): Int? = TODO("radio-core")

    fun isPlausible(rat: ServingRat, pci: Int?, dlEarfcn: Int?): Boolean = TODO("radio-core")

    fun bandwidthMhz(bandwidthKhz: Int?): Double? = TODO("radio-core")

    fun <T : Comparable<T>> inRange(value: T?, range: ClosedRange<T>?): T? = TODO("radio-core")
}
