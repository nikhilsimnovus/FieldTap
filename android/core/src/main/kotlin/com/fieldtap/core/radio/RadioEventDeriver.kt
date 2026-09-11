package com.fieldtap.core.radio

import com.fieldtap.core.input.DataConnState
import com.fieldtap.core.input.DataStateSnapshot
import com.fieldtap.core.input.DisplayInfoSnapshot
import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.format.EventKind
import com.fieldtap.format.EventRat
import com.fieldtap.format.EventRow
import com.fieldtap.format.ServingRat
import com.fieldtap.format.Severity
import kotlin.math.abs

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
 *   - A pending `emergency_only` or `service_restored` (see below) is emitted with this cell, after the
 *     cell events, at the later of the sample's measurement time and the time the state was observed.
 *   An answer whose primary sample was not accepted (stale, too old, or not LTE or NR) changes nothing.
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
 *   - At most one event waits for a cell. A newer transition replaces a pending one; a return to what
 *     the files last said (or, before any service event, to in service) drops it without an event, so
 *     the files never show a restore without the loss before it. `UNKNOWN` states say nothing.
 * - [onDataState]: an event when state or network type changes from the previous snapshot (none for
 *   the first): `rat` `-`, title `Mobile data <state words>`, detail like `connected, LTE`, severity
 *   `warn` when it became disconnected, else `info`.
 * - [onDisplayInfo]: an event when "5G icon shown" ([NetworkTypeNames.shows5g]) changes, starting from
 *   "not shown": `rat` `nr`, title `5G icon on` or `5G icon off`, detail like
 *   `override NR_NSA, network LTE`.
 *
 * Not thread-safe: called on the session dispatcher only.
 *
 * Tests: the golden events.csv serving_cell and nr_display rows rebuilt; NSA leg drop without event;
 * NR SA -> LTE fallback gives rat_change warn then serving_cell; SIM-less emergency start; pending
 * service_restored resolved by the next sample.
 *
 * Owner: workstream `radio-core`.
 */
class RadioEventDeriver {
    private var lastPrimary: PrimarySample? = null
    private var written: ServiceCondition? = null
    private var pending: PendingService? = null
    private var lastData: DataStateSnapshot? = null
    private var iconShown: Boolean = false

    fun onAccepted(classified: ClassifiedAnswer, accepted: List<KpiCandidate>): List<EventRow> {
        val primary = classified.primary ?: return emptyList()
        val primaryRat = primary.cell.rat.servingRat ?: return emptyList()
        val sample = accepted.firstOrNull {
            it.row.rat == primaryRat && it.measurementElapsedMs == primary.cell.timestampMs
        } ?: return emptyList()

        val identity = sample.identity
        val key = ServingKey.of(identity)
        val timeMs = sample.row.timeEpochMs
        val previous = lastPrimary
        val events = ArrayList<EventRow>(3)
        if (previous != null && previous.identity.rat != identity.rat) {
            val fallback = previous.identity.rat == ServingRat.NR && identity.rat == ServingRat.LTE
            events.add(
                EventRow(
                    timeUtcMs = timeMs,
                    rat = identity.rat.eventRat,
                    kind = EventKind.RAT_CHANGE,
                    severity = if (fallback) Severity.WARN else Severity.INFO,
                    title = "RAT changed",
                    detail = "${previous.identity.rat.name} to ${identity.rat.name}",
                    pci = identity.pci,
                    arfcn = identity.arfcn,
                ),
            )
        }
        if (previous == null || previous.key != key) {
            events.add(
                EventRow(
                    timeUtcMs = timeMs,
                    rat = identity.rat.eventRat,
                    kind = EventKind.SERVING_CELL,
                    severity = Severity.INFO,
                    title = if (previous == null) "Serving cell" else "Serving cell changed",
                    detail = RadioEventText.servingDetail(identity).ifEmpty { null },
                    pci = identity.pci,
                    arfcn = identity.arfcn,
                ),
            )
        }
        lastPrimary = PrimarySample(identity, key, sample.measurementElapsedMs)

        val held = pending
        if (held != null) {
            events.add(announcement(held.condition, maxOf(held.observedWallMs, timeMs), identity))
            written = held.condition
            pending = null
        }
        return events
    }

    fun onServiceState(state: ServiceStateSnapshot): List<EventRow> {
        val condition = conditionOf(state) ?: return emptyList()
        val effective = pending?.condition ?: written
        if (condition == effective) return emptyList()
        if (pending != null) {
            val filesSay = written ?: ServiceCondition.IN_SERVICE
            if (condition == filesSay) {
                pending = null
                written = filesSay
                return emptyList()
            }
        }
        return when (condition) {
            ServiceCondition.LOST -> {
                pending = null
                written = ServiceCondition.LOST
                listOf(
                    EventRow(
                        timeUtcMs = state.observedWallMs,
                        rat = lastPrimary?.identity?.rat?.eventRat ?: EventRat.NONE,
                        kind = EventKind.SERVICE_LOST,
                        severity = Severity.ERROR,
                        title = "Service lost",
                        detail = if (state.state == ServiceRegState.POWER_OFF) "radio off" else "out of service",
                    ),
                )
            }

            ServiceCondition.EMERGENCY_ONLY -> announceOrHold(condition, state)

            ServiceCondition.IN_SERVICE -> if (effective == null) {
                written = ServiceCondition.IN_SERVICE
                emptyList()
            } else {
                announceOrHold(condition, state)
            }
        }
    }

    fun onDataState(state: DataStateSnapshot): List<EventRow> {
        val previous = lastData
        lastData = state
        if (previous == null) return emptyList()
        if (previous.state == state.state && previous.networkType == state.networkType) return emptyList()
        val disconnected = state.state == DataConnState.DISCONNECTED && previous.state != DataConnState.DISCONNECTED
        return listOf(
            EventRow(
                timeUtcMs = state.observedWallMs,
                rat = EventRat.NONE,
                kind = EventKind.DATA_STATE,
                severity = if (disconnected) Severity.WARN else Severity.INFO,
                title = "Mobile data ${RadioEventText.dataStateWords(state.state)}",
                detail = RadioEventText.dataDetail(state),
            ),
        )
    }

    fun onDisplayInfo(info: DisplayInfoSnapshot): List<EventRow> {
        val shown = NetworkTypeNames.shows5g(info)
        if (shown == iconShown) return emptyList()
        iconShown = shown
        return listOf(
            EventRow(
                timeUtcMs = info.observedWallMs,
                rat = EventRat.NR,
                kind = EventKind.NR_DISPLAY,
                severity = Severity.INFO,
                title = if (shown) "5G icon on" else "5G icon off",
                detail = RadioEventText.displayDetail(info).ifEmpty { null },
            ),
        )
    }

    private fun announceOrHold(condition: ServiceCondition, state: ServiceStateSnapshot): List<EventRow> {
        val last = lastPrimary
        if (last != null && abs(state.observedElapsedMs - last.measurementElapsedMs) <= RECENT_CELL_MS) {
            pending = null
            written = condition
            return listOf(announcement(condition, state.observedWallMs, last.identity))
        }
        pending = PendingService(condition, state.observedWallMs)
        return emptyList()
    }

    private fun announcement(condition: ServiceCondition, timeMs: Long, cell: ServingCellIdentity): EventRow =
        when (condition) {
            ServiceCondition.EMERGENCY_ONLY -> EventRow(
                timeUtcMs = timeMs,
                rat = cell.rat.eventRat,
                kind = EventKind.EMERGENCY_ONLY,
                severity = Severity.ERROR,
                title = "Emergency calls only",
                pci = cell.pci,
                arfcn = cell.arfcn,
            )

            ServiceCondition.IN_SERVICE -> EventRow(
                timeUtcMs = timeMs,
                rat = cell.rat.eventRat,
                kind = EventKind.SERVICE_RESTORED,
                severity = Severity.OK,
                title = "Service restored",
                pci = cell.pci,
                arfcn = cell.arfcn,
            )

            ServiceCondition.LOST -> throw IllegalStateException("service_lost never waits for a cell")
        }

    private fun conditionOf(state: ServiceStateSnapshot): ServiceCondition? = when {
        ServingCellSelector.isEmergencyOnly(state) -> ServiceCondition.EMERGENCY_ONLY
        ServingCellSelector.isOutOfService(state) -> ServiceCondition.LOST
        state.state == ServiceRegState.IN_SERVICE -> ServiceCondition.IN_SERVICE
        else -> null
    }

    private enum class ServiceCondition { IN_SERVICE, LOST, EMERGENCY_ONLY }

    private data class PendingService(val condition: ServiceCondition, val observedWallMs: Long)

    private data class PrimarySample(
        val identity: ServingCellIdentity,
        val key: ServingKey,
        val measurementElapsedMs: Long,
    )

    private data class ServingKey(
        val rat: ServingRat,
        val plmn: String?,
        val cellId: Long?,
        val pci: Int?,
        val arfcn: Int?,
    ) {
        companion object {
            fun of(identity: ServingCellIdentity): ServingKey =
                ServingKey(identity.rat, identity.plmn, identity.cellId, identity.pci, identity.arfcn)
        }
    }
}

/** A primary cell measured this close to a service-state change names the cell of its event. */
private const val RECENT_CELL_MS: Long = CadencePolicy.LONG_MAX_AGE_MS

/**
 * Event wording. Parts whose value is unknown are left out, never written as `None` or `null`.
 *
 * - [servingDetail] LTE: `PLMN 311480 TAC 18704 eNB 84532 sector 1 PCI 212 EARFCN 66786 band 66`.
 * - [servingDetail] NR: `PLMN 311480 TAC 18704 NCI 123456789 PCI 393 NR-ARFCN 650000 band 77`.
 * - [dataDetail]: `connected, LTE`; the network type is left out when Android reports it unknown.
 * - [displayDetail]: `override NR_NSA, network LTE`; unknown values are left out, so the text can be
 *   empty.
 *
 * Owner: workstream `radio-core`.
 */
object RadioEventText {
    fun servingDetail(identity: ServingCellIdentity): String {
        val parts = ArrayList<String>(8)
        identity.plmn?.let { parts.add("PLMN $it") }
        identity.tac?.let { parts.add("TAC $it") }
        val cellId = identity.cellId
        if (cellId != null) {
            when (identity.rat) {
                ServingRat.LTE -> {
                    parts.add("eNB ${cellId shr 8}")
                    parts.add("sector ${cellId and 0xFFL}")
                }

                ServingRat.NR -> parts.add("NCI $cellId")
            }
        }
        identity.pci?.let { parts.add("PCI $it") }
        identity.arfcn?.let { parts.add(if (identity.rat == ServingRat.LTE) "EARFCN $it" else "NR-ARFCN $it") }
        identity.band?.let { parts.add("band $it") }
        return parts.joinToString(" ")
    }

    fun dataDetail(state: DataStateSnapshot): String {
        val words = dataStateWords(state.state)
        val network = NetworkTypeNames.networkType(state.networkType)
        return if (network == NetworkTypeNames.UNKNOWN) words else "$words, $network"
    }

    fun displayDetail(info: DisplayInfoSnapshot): String {
        val parts = ArrayList<String>(2)
        val override = NetworkTypeNames.overrideType(info.overrideNetworkType)
        if (override != NetworkTypeNames.UNKNOWN) parts.add("override $override")
        val network = NetworkTypeNames.networkType(info.networkType)
        if (network != NetworkTypeNames.UNKNOWN) parts.add("network $network")
        return parts.joinToString(", ")
    }

    /** The words after `Mobile data` in a data_state title, and before the network type in its detail. */
    internal fun dataStateWords(state: DataConnState): String = when (state) {
        DataConnState.DISCONNECTED -> "disconnected"
        DataConnState.CONNECTING -> "connecting"
        DataConnState.CONNECTED -> "connected"
        DataConnState.SUSPENDED -> "suspended"
        DataConnState.DISCONNECTING -> "disconnecting"
        DataConnState.HANDOVER_IN_PROGRESS -> "moving between networks"
        DataConnState.UNKNOWN -> "state unknown"
    }
}
