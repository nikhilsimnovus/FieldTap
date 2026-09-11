package com.fieldtap.core.radio

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.format.CellInfoRow
import com.fieldtap.format.KpiRow
import com.fieldtap.format.Ranges
import com.fieldtap.format.Rat
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
 * Plausibility, beyond the numeric ranges, so no value can break the file's patterns:
 * - `mcc` must be 3 digits and `mnc` 2 or 3, else blank (and so is `plmn`);
 * - `additional_plmns` keeps only 5- or 6-digit entries, once each, ascending;
 * - `bands` keeps only entries in 1..1024, in Android's order; `band` is the first entry of `bands`,
 *   blank when that entry is outside the range (the next entry is never promoted);
 * - the SS-to-CSI fallback happens only when the SS value is unavailable: an out-of-range SS value is
 *   blank, never replaced by a CSI value;
 * - `connection_status` other than 0, 1 or 2 is blank; a negative `sub_id` is blank;
 * - an NR identity has no bandwidth (Android does not report it without a privileged permission).
 *
 * Tests: rows of the golden cellinfo.csv and kpi.csv rebuilt from equivalent snapshots, field by
 * field; an 11001 ms sample on the long interval and a 2501 ms sample on the short one are not KPI
 * rows; CSI fallback; out-of-range values blank.
 *
 * Owner: workstream `radio-core`.
 */
object RadioRows {
    private val MCC_PATTERN = Regex("[0-9]{3}")
    private val MNC_PATTERN = Regex("[0-9]{2,3}")
    private val PLMN_PATTERN = Regex("[0-9]{5,6}")
    private val CONNECTION_STATUSES: IntRange =
        CellSnapshot.CONNECTION_NONE..CellSnapshot.CONNECTION_SECONDARY_SERVING

    fun cellInfo(classified: ClassifiedAnswer): List<CellInfoCandidate> =
        classified.cells.map { CellInfoCandidate(cellInfoRow(classified.answer, it), it.cell.timestampMs) }

    fun kpi(classified: ClassifiedAnswer): List<KpiCandidate> {
        val maxAgeMs = CadencePolicy.maxKpiAgeMs(classified.answer.conditions)
        val candidates = ArrayList<KpiCandidate>(2)
        for (serving in listOfNotNull(classified.primary, classified.nsaSecondary)) {
            if (serving.stale || serving.ageMs > maxAgeMs) continue
            val candidate = kpiCandidate(classified.answer, serving) ?: continue
            if (candidates.none { it.row.rat == candidate.row.rat }) candidates.add(candidate)
        }
        return candidates
    }

    /** Null for a RAT other than LTE or NR. */
    fun identity(cell: CellSnapshot): ServingCellIdentity? {
        val rat = cell.rat.servingRat ?: return null
        return ServingCellIdentity(
            rat = rat,
            mcc = mccOrNull(cell.mcc),
            mnc = mncOrNull(cell.mnc),
            tac = CellIdentityMath.inRange(cell.tac, Ranges.TAC),
            cellId = CellIdentityMath.inRange(cell.cellId, Ranges.CELL_ID[rat]),
            pci = CellIdentityMath.inRange(cell.pci, Ranges.PCI[rat]),
            arfcn = CellIdentityMath.inRange(cell.arfcn, Ranges.ARFCN[rat]),
            band = CellIdentityMath.inRange(cell.bands.firstOrNull(), Ranges.BAND),
            bandwidthKhz = if (rat == ServingRat.LTE) {
                CellIdentityMath.inRange(cell.bandwidthKhz, Ranges.BANDWIDTH_KHZ)
            } else {
                null
            },
            operator = operatorOf(cell),
            additionalPlmns = plmnListOf(cell.additionalPlmns),
        )
    }

    private fun kpiCandidate(answer: CellInfoAnswer, serving: ClassifiedCell): KpiCandidate? {
        val cell = serving.cell
        val identity = identity(cell) ?: return null
        val rat = identity.rat
        val row = KpiRow(
            timeEpochMs = serving.measurementWallMs,
            rat = rat,
            pci = identity.pci,
            rsrpDbm = inDecimalRange(cell.rsrp, Ranges.KPI_RSRP_DBM[rat]),
            rsrqDb = inDecimalRange(cell.rsrq, Ranges.KPI_RSRQ_DB[rat]),
            sinrDb = inDecimalRange(cell.sinr, Ranges.KPI_SINR_DB[rat]),
            ageMs = serving.ageMs,
            source = answer.source,
        )
        return KpiCandidate(row = row, identity = identity, measurementElapsedMs = cell.timestampMs)
    }

    private fun cellInfoRow(answer: CellInfoAnswer, classified: ClassifiedCell): CellInfoRow {
        val cell = classified.cell
        val rat = cell.rat.servingRat
        val nr = cell.rat == Rat.NR
        val conditions = answer.conditions
        return CellInfoRow(
            seenUtcMs = answer.observedWallMs,
            rat = cell.rat,
            registered = cell.registered,
            mcc = mccOrNull(cell.mcc),
            mnc = mncOrNull(cell.mnc),
            operator = operatorOf(cell),
            pci = CellIdentityMath.inRange(cell.pci, rat?.let { Ranges.PCI[it] }),
            arfcn = CellIdentityMath.inRange(cell.arfcn, rat?.let { Ranges.ARFCN[it] }),
            bands = cell.bands.filter { it in Ranges.BAND },
            tac = CellIdentityMath.inRange(cell.tac, Ranges.TAC),
            cellId = CellIdentityMath.inRange(cell.cellId, rat?.let { Ranges.CELL_ID[it] }),
            bandwidthKhz = CellIdentityMath.inRange(cell.bandwidthKhz, Ranges.BANDWIDTH_KHZ),
            rsrp = CellIdentityMath.inRange(
                if (nr) cell.rsrp ?: cell.csiRsrp else cell.rsrp,
                rat?.let { Ranges.CELLINFO_RSRP[it] },
            ),
            rsrq = CellIdentityMath.inRange(
                if (nr) cell.rsrq ?: cell.csiRsrq else cell.rsrq,
                rat?.let { Ranges.CELLINFO_RSRQ[it] },
            ),
            sinr = CellIdentityMath.inRange(
                if (nr) cell.sinr ?: cell.csiSinr else cell.sinr,
                rat?.let { Ranges.CELLINFO_SINR[it] },
            ),
            rssi = CellIdentityMath.inRange(cell.rssi, Ranges.RSSI),
            level = CellIdentityMath.inRange(cell.level, Ranges.LEVEL),
            additionalPlmns = plmnListOf(cell.additionalPlmns),
            timeEpochMs = classified.measurementWallMs,
            timestampMs = cell.timestampMs,
            ageMs = classified.ageMs,
            stale = classified.stale,
            connectionStatus = cell.connectionStatus?.takeIf { it in CONNECTION_STATUSES },
            source = answer.source,
            cqi = CellIdentityMath.inRange(cell.cqi, Ranges.CQI),
            timingAdvance = CellIdentityMath.inRange(cell.timingAdvance, Ranges.TIMING_ADVANCE),
            csiRsrp = CellIdentityMath.inRange(cell.csiRsrp, Ranges.CSI_RSRP),
            csiRsrq = CellIdentityMath.inRange(cell.csiRsrq, Ranges.CSI_RSRQ),
            csiSinr = CellIdentityMath.inRange(cell.csiSinr, Ranges.CSI_SINR),
            screenOn = conditions.screenOn,
            charging = conditions.charging,
            wifiConnected = conditions.wifiConnected,
            subId = answer.subId?.takeIf { it >= 0 },
        )
    }

    private fun operatorOf(cell: CellSnapshot): String? =
        cell.operatorLong?.takeIf { it.isNotBlank() } ?: cell.operatorShort?.takeIf { it.isNotBlank() }

    private fun mccOrNull(mcc: String?): String? = mcc?.takeIf { MCC_PATTERN.matches(it) }

    private fun mncOrNull(mnc: String?): String? = mnc?.takeIf { MNC_PATTERN.matches(it) }

    private fun plmnListOf(plmns: List<String>): List<String> =
        plmns.filter { PLMN_PATTERN.matches(it) }.distinct().sorted()

    /** An Android integer checked against a decimal KPI range: the value when inside (or no range), else null. */
    private fun inDecimalRange(value: Int?, range: ClosedFloatingPointRange<Double>?): Int? = when {
        value == null -> null
        range == null -> value
        value.toDouble() in range -> value
        else -> null
    }
}

/**
 * Cell identity arithmetic and plausibility.
 *
 * - [lteUlEarfcn]: LTE FDD UL EARFCN = DL EARFCN + the band's offset from TS 36.101 table 5.7.3-1
 *   (band 66: DL 66786 -> UL 132322). Null for TDD bands, supplemental-downlink bands, unknown bands
 *   and NR, and also when the DL EARFCN is not inside the band or the result falls outside the band's
 *   UL range (the top of band 66, 70 and similar asymmetric bands has no paired uplink).
 * - [isPlausible]: `pci` and `dlEarfcn` both present and inside [com.fieldtap.format.Ranges] for the RAT.
 * - [bandwidthMhz]: kHz / 1000; null for null or out of range.
 * - [inRange]: the value when inside the range (or when range is null), else null.
 *
 * The FDD table (DL and UL EARFCN ranges per band) was checked against TS 36.101 table 5.7.3-1 as
 * published on sqimway.com on 2026-09-10. Bands not listed are unknown and give null.
 *
 * Owner: workstream `radio-core`.
 */
object CellIdentityMath {
    private class FddBand(val dlLow: Int, val dlHigh: Int, val ulLow: Int, val ulHigh: Int)

    private val LTE_FDD_BANDS: Map<Int, FddBand> = mapOf(
        1 to FddBand(0, 599, 18_000, 18_599),
        2 to FddBand(600, 1_199, 18_600, 19_199),
        3 to FddBand(1_200, 1_949, 19_200, 19_949),
        4 to FddBand(1_950, 2_399, 19_950, 20_399),
        5 to FddBand(2_400, 2_649, 20_400, 20_649),
        6 to FddBand(2_650, 2_749, 20_650, 20_749),
        7 to FddBand(2_750, 3_449, 20_750, 21_449),
        8 to FddBand(3_450, 3_799, 21_450, 21_799),
        9 to FddBand(3_800, 4_149, 21_800, 22_149),
        10 to FddBand(4_150, 4_749, 22_150, 22_749),
        11 to FddBand(4_750, 4_949, 22_750, 22_949),
        12 to FddBand(5_010, 5_179, 23_010, 23_179),
        13 to FddBand(5_180, 5_279, 23_180, 23_279),
        14 to FddBand(5_280, 5_379, 23_280, 23_379),
        17 to FddBand(5_730, 5_849, 23_730, 23_849),
        18 to FddBand(5_850, 5_999, 23_850, 23_999),
        19 to FddBand(6_000, 6_149, 24_000, 24_149),
        20 to FddBand(6_150, 6_449, 24_150, 24_449),
        21 to FddBand(6_450, 6_599, 24_450, 24_599),
        22 to FddBand(6_600, 7_399, 24_600, 25_399),
        23 to FddBand(7_500, 7_699, 25_500, 25_699),
        24 to FddBand(7_700, 8_039, 25_700, 26_039),
        25 to FddBand(8_040, 8_689, 26_040, 26_689),
        26 to FddBand(8_690, 9_039, 26_690, 27_039),
        27 to FddBand(9_040, 9_209, 27_040, 27_209),
        28 to FddBand(9_210, 9_659, 27_210, 27_659),
        30 to FddBand(9_770, 9_869, 27_660, 27_759),
        31 to FddBand(9_870, 9_919, 27_760, 27_809),
        65 to FddBand(65_536, 66_435, 131_072, 131_971),
        66 to FddBand(66_436, 67_335, 131_972, 132_671),
        68 to FddBand(67_536, 67_835, 132_672, 132_971),
        70 to FddBand(68_336, 68_585, 132_972, 133_121),
        71 to FddBand(68_586, 68_935, 133_122, 133_471),
        72 to FddBand(68_936, 68_985, 133_472, 133_521),
        73 to FddBand(68_986, 69_035, 133_522, 133_571),
        74 to FddBand(69_036, 69_465, 133_572, 134_001),
        85 to FddBand(70_366, 70_545, 134_002, 134_181),
        87 to FddBand(70_546, 70_595, 134_182, 134_231),
        88 to FddBand(70_596, 70_645, 134_232, 134_281),
        103 to FddBand(70_646, 70_655, 134_282, 134_291),
        106 to FddBand(70_656, 70_705, 134_292, 134_341),
    )

    fun lteUlEarfcn(dlEarfcn: Int, band: Int?): Int? {
        if (band == null) return null
        val fdd = LTE_FDD_BANDS[band] ?: return null
        if (dlEarfcn < fdd.dlLow || dlEarfcn > fdd.dlHigh) return null
        val ulEarfcn = dlEarfcn - fdd.dlLow + fdd.ulLow
        return if (ulEarfcn in fdd.ulLow..fdd.ulHigh) ulEarfcn else null
    }

    fun isPlausible(rat: ServingRat, pci: Int?, dlEarfcn: Int?): Boolean {
        val pciRange = Ranges.PCI[rat] ?: return false
        val arfcnRange = Ranges.ARFCN[rat] ?: return false
        return pci != null && dlEarfcn != null && pci in pciRange && dlEarfcn in arfcnRange
    }

    fun bandwidthMhz(bandwidthKhz: Int?): Double? {
        val khz = inRange(bandwidthKhz, Ranges.BANDWIDTH_KHZ) ?: return null
        return inRange(khz / 1000.0, Ranges.BW_MHZ)
    }

    fun <T : Comparable<T>> inRange(value: T?, range: ClosedRange<T>?): T? = when {
        value == null -> null
        range == null -> value
        value in range -> value
        else -> null
    }
}
