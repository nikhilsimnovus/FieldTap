package com.fieldtap.core.radio

import com.fieldtap.core.input.DataStateSnapshot
import com.fieldtap.core.input.DisplayInfoSnapshot
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.format.EventRow

/**
 * Radio events: `serving_cell`, `rat_change`, `service_lost`, `emergency_only`, `service_restored`,
 * `data_state`, `nr_display`. Never anything RRC or handover.
 *
 * Called only for input that is being written (the pipeline withholds input inside a privacy zone),
 * so its baselines are always "what the files last said". Rules, in the wording of
 * docs/SESSION-FORMAT.md "events.csv":
 *
 * - [onAccepted], per answer with accepted KPI candidates:
 *   - `rat_change` first, when the primary serving RAT differs from the last primary RAT (both LTE or
 *     NR): `rat` the new RAT, severity `warn` for NR -> LTE else `info`, title `RAT changed`, detail
 *     `NR to LTE`, pci and arfcn of the new cell, time = the sample's measurement time.
 *   - `serving_cell` at the first accepted primary sample (title `Serving cell`) and whenever the
 *     primary's identity (rat, plmn, cell_id, pci, arfcn) changes (title `Serving cell changed`).
 *     Time = measurement time of the first sample from the new cell; detail from
 *     [RadioEventText.servingDetail]. A change of only the NSA NR leg is not an event.
 *   - A pending `emergency_only` or `service_restored` (see below) is emitted with this cell.
 * - [onServiceState]:
 *   - out of service (from any other state) -> `service_lost`, `rat` the last primary RAT or `-`,
 *     severity `error`, title `Service lost`, detail `out of service` or `radio off`, observed time.
 *   - entering emergency-only -> `emergency_only`, severity `error`, title `Emergency calls only`, with
 *     rat, pci and arfcn of the last primary cell if it was measured within 11 s of the observed time;
 *     otherwise pending until the next accepted primary sample.
 *   - in service after lost or emergency-only -> `service_restored`, severity `ok`, title
 *     `Service restored`, same cell rule and pending rule.
 *   - The first snapshot is a baseline: no event, unless it is already out of service or
 *     emergency-only, which emits that event.
 * - [onDataState]: an event when state or network type changes from the previous snapshot (none for
 *   the first): `rat` `-`, title `Mobile data <state words>`, detail like `connected, LTE`, severity
 *   `warn` when it became disconnected, else `info`.
 * - [onDisplayInfo]: an event when "5G icon shown" ([NetworkTypeNames.shows5g]) changes, starting from
 *   "not shown": `rat` `nr`, title `5G icon on` or `5G icon off`, detail like
 *   `override NR_NSA, network LTE`.
 *
 * Tests: the golden events.csv serving_cell and nr_display rows rebuilt; NSA leg drop without event;
 * NR SA -> LTE fallback gives rat_change warn then serving_cell; SIM-less emergency start; pending
 * service_restored resolved by the next sample.
 *
 * Owner: workstream `radio-core`.
 */
class RadioEventDeriver {
    fun onAccepted(classified: ClassifiedAnswer, accepted: List<KpiCandidate>): List<EventRow> = TODO("radio-core")

    fun onServiceState(state: ServiceStateSnapshot): List<EventRow> = TODO("radio-core")

    fun onDataState(state: DataStateSnapshot): List<EventRow> = TODO("radio-core")

    fun onDisplayInfo(info: DisplayInfoSnapshot): List<EventRow> = TODO("radio-core")
}

/**
 * Event wording. Parts whose value is unknown are left out, never written as `None` or `null`.
 *
 * - [servingDetail] LTE: `PLMN 311480 TAC 18704 eNB 84532 sector 1 PCI 212 EARFCN 66786 band 66`.
 * - [servingDetail] NR: `PLMN 311480 TAC 18704 NCI 123456789 PCI 393 NR-ARFCN 650000 band 77`.
 *
 * Owner: workstream `radio-core`.
 */
object RadioEventText {
    fun servingDetail(identity: ServingCellIdentity): String = TODO("radio-core")

    fun dataDetail(state: DataStateSnapshot): String = TODO("radio-core")

    fun displayDetail(info: DisplayInfoSnapshot): String = TODO("radio-core")
}
