package com.fieldtap.core.radio

import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.ServiceStateSnapshot

/** The serving cells of one answer. */
data class ServingSelection(
    val primary: CellSnapshot?,
    val nsaSecondary: CellSnapshot?,
)

/**
 * Picks the serving cells and reads service state.
 *
 * [select]:
 * - Primary: the first cell with `connectionStatus == 1` (primary serving). When no cell reports a
 *   connection status at all (every value null), the first registered LTE or NR cell. Otherwise none.
 * - NSA secondary: when the primary is LTE, the first NR cell with `connectionStatus == 2`
 *   (secondary serving). Never when the primary is NR (SA) or absent.
 * - A GSM, WCDMA, TD-SCDMA or CDMA primary is returned, but never yields a kpi.csv row.
 *
 * [isEmergencyOnly] comes from service state only (`state == EMERGENCY_ONLY` or `emergencyOnly`),
 * never from a cell: the SIM-less OnePlus reports its emergency-camped NR cell as registered.
 * Samples from an emergency-camped cell are still real measurements and still go to kpi.csv.
 *
 * [isOutOfService]: `OUT_OF_SERVICE` or `POWER_OFF`, and not emergency-only.
 *
 * Tests: NSA (LTE status 1, NR status 2, LTE neighbour status 0); SA; vendor without statuses;
 * two primaries (first wins); emergency-only with a registered NR cell.
 *
 * Owner: workstream `radio-core`.
 */
object ServingCellSelector {
    fun select(cells: List<CellSnapshot>): ServingSelection = TODO("radio-core")

    fun isEmergencyOnly(state: ServiceStateSnapshot): Boolean = TODO("radio-core")

    fun isOutOfService(state: ServiceStateSnapshot): Boolean = TODO("radio-core")
}
