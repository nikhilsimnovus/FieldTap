package com.fieldtap.core.radio

import com.fieldtap.format.CellRow
import com.fieldtap.format.ServingRat

/**
 * cells.csv and `summary.plmns`, accumulated from kpi.csv rows as they are written (never from
 * rows that were dropped inside a privacy zone).
 *
 * - A cell is distinct by `rat`, `plmn`, `cell_id`, `pci` and `dl_earfcn`; [rows] are in the order
 *   first written. A leg Android reports without `pci` or `arfcn` is still a row, with those blank and
 *   `plausible` False (docs/SESSION-FORMAT.md, cells.csv): leaving it out would leave kpi.csv rows that
 *   no cell accounts for.
 * - `first_seen_utc` is the measurement time of the first written row. Identity fields come from the
 *   first row; a blank is filled by a later row, a known value is never overwritten. The fields that
 *   make up the key (`rat`, `plmn`, `cell_id`, `pci`, `dl_earfcn`) never change, so a fill of `mcc` or
 *   `mnc` that would change `plmn` is not made.
 * - `samples` counts written rows; `rsrp_min`/`rsrp_max` over their `rsrp_dbm`.
 * - `ul_earfcn`, bandwidths and `plausible` via [CellIdentityMath]; `ul_bw_mhz` equals `dl_bw_mhz` for
 *   LTE FDD (a known paired `ul_earfcn`) and is blank otherwise. An NSA NR leg usually has only pci,
 *   arfcn and band: nothing is guessed.
 * - [plmns]: each PLMN mapped to the number of written kpi.csv rows whose serving cell has it,
 *   in first-seen order. NR NSA rows without a PLMN count toward nothing.
 *
 * Not thread-safe: called on the session dispatcher only.
 *
 * Tests: the golden cells.csv (3 rows: LTE sector 1 with 32 samples, NR leg with 46, LTE sector 2
 * with 22) and `plmns == {"311480": 54}` from the golden kpi rows.
 *
 * Owner: workstream `radio-core`.
 */
class ServingCellTable {
    private val entries = LinkedHashMap<TableKey, Entry>()
    private val plmnCounts = LinkedHashMap<String, Int>()

    fun onKpiWritten(candidate: KpiCandidate) {
        val identity = candidate.identity
        identity.plmn?.let { plmn -> plmnCounts[plmn] = (plmnCounts[plmn] ?: 0) + 1 }
        val key = TableKey(identity.rat, identity.plmn, identity.cellId, identity.pci, identity.arfcn)
        val entry = entries.getOrPut(key) { Entry(key, candidate.row.timeEpochMs, identity) }
        entry.add(candidate)
    }

    fun rows(): List<CellRow> = entries.values.map { it.toRow() }

    fun plmns(): Map<String, Int> = LinkedHashMap(plmnCounts)

    private data class TableKey(
        val rat: ServingRat,
        val plmn: String?,
        val cellId: Long?,
        val pci: Int?,
        val arfcn: Int?,
    )

    private class Entry(
        private val key: TableKey,
        private val firstSeenUtcMs: Long,
        first: ServingCellIdentity,
    ) {
        private var mcc: String? = first.mcc
        private var mnc: String? = first.mnc
        private var tac: Int? = first.tac
        private var band: Int? = first.band
        private var bandwidthKhz: Int? = first.bandwidthKhz
        private var operator: String? = first.operator
        private var additionalPlmns: List<String> = first.additionalPlmns
        private var samples: Int = 0
        private var rsrpMin: Int? = null
        private var rsrpMax: Int? = null

        fun add(candidate: KpiCandidate) {
            val identity = candidate.identity
            if (tac == null) tac = identity.tac
            if (band == null) band = identity.band
            if (bandwidthKhz == null) bandwidthKhz = identity.bandwidthKhz
            if (operator == null) operator = identity.operator
            if (additionalPlmns.isEmpty()) additionalPlmns = identity.additionalPlmns
            val filledMcc = mcc ?: identity.mcc
            val filledMnc = mnc ?: identity.mnc
            val filledPlmn = if (filledMcc != null && filledMnc != null) filledMcc + filledMnc else null
            if (filledPlmn == key.plmn) {
                mcc = filledMcc
                mnc = filledMnc
            }
            samples += 1
            val rsrp = candidate.row.rsrpDbm
            if (rsrp != null) {
                rsrpMin = minOf(rsrpMin ?: rsrp, rsrp)
                rsrpMax = maxOf(rsrpMax ?: rsrp, rsrp)
            }
        }

        fun toRow(): CellRow {
            val lte = key.rat == ServingRat.LTE
            val ulEarfcn = if (lte && key.arfcn != null) CellIdentityMath.lteUlEarfcn(key.arfcn, band) else null
            val dlBwMhz = if (lte) CellIdentityMath.bandwidthMhz(bandwidthKhz) else null
            return CellRow(
                firstSeenUtcMs = firstSeenUtcMs,
                rat = key.rat,
                mcc = mcc,
                mnc = mnc,
                tac = tac,
                cellId = key.cellId,
                pci = key.pci,
                band = band,
                dlEarfcn = key.arfcn,
                ulEarfcn = ulEarfcn,
                dlBwMhz = dlBwMhz,
                ulBwMhz = if (ulEarfcn != null) dlBwMhz else null,
                plausible = CellIdentityMath.isPlausible(key.rat, key.pci, key.arfcn),
                operator = operator,
                additionalPlmns = additionalPlmns,
                samples = samples,
                rsrpMin = rsrpMin,
                rsrpMax = rsrpMax,
            )
        }
    }
}
