package com.fieldtap.core.radio

import com.fieldtap.format.CellRow

/**
 * cells.csv and `summary.plmns`, accumulated from kpi.csv rows as they are written (never from
 * rows that were dropped inside a privacy zone).
 *
 * - A cell is distinct by `rat`, `plmn`, `cell_id`, `pci` and `dl_earfcn`; [rows] are in the order
 *   first written. A candidate without `pci` or `arfcn` cannot be a cells.csv row (both are
 *   required columns) and is skipped here, but still counts toward [plmns].
 * - `first_seen_utc` is the measurement time of the first written row. Identity fields come from the
 *   first row; a blank is filled by a later row, a known value is never overwritten.
 * - `samples` counts written rows; `rsrp_min`/`rsrp_max` over their `rsrp_dbm`.
 * - `ul_earfcn`, bandwidths and `plausible` via [CellIdentityMath]; `ul_bw_mhz` equals `dl_bw_mhz` for
 *   LTE FDD and is blank otherwise. An NSA NR leg usually has only pci, arfcn and band: nothing is
 *   guessed.
 * - [plmns]: each PLMN mapped to the number of written kpi.csv rows whose serving cell has it,
 *   in first-seen order. NR NSA rows without a PLMN count toward nothing.
 *
 * Tests: the golden cells.csv (3 rows: LTE sector 1 with 32 samples, NR leg with 46, LTE sector 2
 * with 22) and `plmns == {"311480": 54}` from the golden kpi rows.
 *
 * Owner: workstream `radio-core`.
 */
class ServingCellTable {
    fun onKpiWritten(candidate: KpiCandidate): Unit = TODO("radio-core")

    fun rows(): List<CellRow> = TODO("radio-core")

    fun plmns(): Map<String, Int> = TODO("radio-core")
}
