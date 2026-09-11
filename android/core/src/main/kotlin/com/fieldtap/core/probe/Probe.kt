package com.fieldtap.core.probe

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.RadioListener
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.format.HandsetMeta

/** What `requestCellInfoUpdate` and `CellInfoListener` returned during the probe. */
data class CellInfoProbe(
    val requests: Int,
    val answers: Int,
    val errors: Int,
    val maxCellsPerAnswer: Int,
    val neighboursSeen: Boolean,
    /** `rat` wire names of primary serving cells seen, in first-seen order. */
    val servingRats: List<String>,
    val bandListsPresent: Boolean,
    val connectionStatusReported: Boolean,
    val nsaSecondarySeen: Boolean,
    /** True when the primary's `timestampMs` advanced at least once; null with fewer than two answers. */
    val timestampsAdvance: Boolean?,
    val minFreshIntervalMs: Long?,
    val rsrpMin: Int?,
    val rsrpMax: Int?,
    val sinrMin: Int?,
    val sinrMax: Int?,
)

data class ServiceStateProbe(
    val snapshots: Int,
    val operatorNumericPresent: Boolean,
    val emergencyOnlySeen: Boolean,
    /** `ServiceRegState` names seen. */
    val states: List<String>,
)

/**
 * The capability probe's result: what each API returns on this handset, exportable as JSON to
 * qualify pilot phones. Contains no subscriber or device identifier and no location.
 */
data class ProbeReport(
    val createdUtcMs: Long,
    val durationMs: Long,
    val appVersion: String,
    val versionCode: Long,
    val sdkInt: Int,
    val handset: HandsetMeta,
    /** Permission name -> granted. */
    val permissions: Map<String, Boolean>,
    val listeners: Map<RadioListener, ListenerOutcome>,
    val cellInfo: CellInfoProbe,
    val serviceState: ServiceStateProbe,
    /** Override network type names seen from the display-info listener. */
    val displayOverridesSeen: List<String>,
    val notes: List<String>,
) {
    companion object {
        const val FORMAT: String = "fieldtap-probe/1"
    }
}

/**
 * Pure accumulation of probe observations, fed by com.fieldtap.platform.probe.CapabilityProbeRunner.
 *
 * Owner: workstream `platform-adapters`.
 */
class CellInfoProbeAccumulator {
    fun onRequest(): Unit = TODO("platform-adapters")

    fun onError(): Unit = TODO("platform-adapters")

    fun onAnswer(answer: CellInfoAnswer): Unit = TODO("platform-adapters")

    fun result(): CellInfoProbe = TODO("platform-adapters")
}

/** Owner: workstream `platform-adapters`. */
class ServiceStateProbeAccumulator {
    fun onServiceState(state: ServiceStateSnapshot): Unit = TODO("platform-adapters")

    fun result(): ServiceStateProbe = TODO("platform-adapters")
}

/**
 * The probe export: `{"format": "fieldtap-probe/1", ...}` rendered with `com.fieldtap.format.JsonText`,
 * keys snake_case in [ProbeReport] field order, enums by name.
 *
 * Owner: workstream `platform-adapters`.
 */
object ProbeJson {
    fun encode(report: ProbeReport): String = TODO("platform-adapters")
}
