package com.fieldtap.core.capability

import com.fieldtap.format.HandsetMeta
import com.fieldtap.format.JsonArr
import com.fieldtap.format.JsonBool
import com.fieldtap.format.JsonInt
import com.fieldtap.format.JsonNode
import com.fieldtap.format.JsonNul
import com.fieldtap.format.JsonObj
import com.fieldtap.format.JsonStr
import com.fieldtap.format.JsonText
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The capability export: `{"format": "fieldtap-capability/1", ...}` rendered with
 * `com.fieldtap.format.JsonText`, the same conventions as `com.fieldtap.core.probe.ProbeJson` — keys
 * snake_case in [CapabilityReport] field order, enums by name.
 *
 * - `handset` leaves out keys with no value, as session.json does; other nullable values are `null`.
 * - `root_probe` is `null` when "Check with root" was never run.
 * - [fileName]: `capability-<manufacturer>-<model>-<yyyyMMdd-HHmmss of createdUtcMs, UTC>.json`, where each
 *   name keeps only `A-Z a-z 0-9 . _ -` (other runs become one `-`), is cut at 40 characters, and is
 *   `unknown` when nothing is left — exactly as `ProbeJson.fileName`.
 *
 * The export carries no identifier and no location.
 *
 * Owner: workstream `capability-core`.
 */
object CapabilityJson {
    private const val MAX_NAME_PART = 40
    private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]+")
    private val FILE_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun encode(report: CapabilityReport): String = JsonText.render(tree(report))

    /** The JSON tree [encode] renders. */
    fun tree(report: CapabilityReport): JsonObj = obj(
        "format" to JsonStr(CapabilityReport.FORMAT),
        "created_utc_ms" to JsonInt(report.createdUtcMs),
        "app_version" to JsonStr(report.appVersion),
        "version_code" to JsonInt(report.versionCode),
        "sdk_int" to JsonInt(report.sdkInt.toLong()),
        "handset" to handset(report.handset),
        "root" to root(report.root),
        "root_probe" to (report.rootProbe?.let { rootProbe(it) } ?: JsonNul),
        "usb" to usb(report.usb),
        "cellular" to cellular(report.cellular),
        "verdict" to verdict(report.verdict),
        "notes" to JsonArr(report.notes.map { JsonStr(it) }),
    )

    fun fileName(report: CapabilityReport): String {
        val stamp = FILE_STAMP.format(Instant.ofEpochMilli(report.createdUtcMs))
        return "capability-${namePart(report.handset.manufacturer)}-${namePart(report.handset.model)}-$stamp.json"
    }

    private fun handset(handset: HandsetMeta): JsonObj = JsonObj(
        listOf(
            "manufacturer" to handset.manufacturer,
            "model" to handset.model,
            "device" to handset.device,
            "android_version" to handset.androidVersion,
            "android_build" to handset.androidBuild,
            "security_patch" to handset.securityPatch,
            "baseband" to handset.baseband,
            "soc" to handset.soc,
            "platform" to handset.platform,
            "hardware" to handset.hardware,
            "operator_mccmnc" to handset.operatorMccmnc,
            "operator_name" to handset.operatorName,
            "sim_mccmnc" to handset.simMccmnc,
            "sim_operator_name" to handset.simOperatorName,
            "network_type" to handset.networkType,
        ).mapNotNull { (key, value) -> value?.let { key to JsonStr(it) } },
    )

    private fun root(root: RootSignals): JsonObj = obj(
        "confidence" to JsonStr(root.confidence.name),
        "su_binaries_present" to JsonArr(root.suBinariesPresent.map { JsonStr(it) }),
        "root_manager_packages" to JsonArr(root.rootManagerPackages.map { JsonStr(it) }),
        "build_tags_test_keys" to JsonBool(root.buildTagsTestKeys),
        "debuggable" to JsonBool(root.debuggable),
        "secure_off" to JsonBool(root.secureOff),
        "writable_system_paths" to JsonArr(root.writableSystemPaths.map { JsonStr(it) }),
    )

    private fun rootProbe(probe: RootProbeResult): JsonObj = obj(
        "su_status" to JsonStr(probe.suStatus.name),
        "is_root" to JsonBool(probe.isRoot),
        "selinux" to JsonStr(probe.selinux.name),
        "diag_device" to JsonStr(probe.diagDevice.name),
        "kernel_diag" to JsonStr(probe.kernelDiag.name),
        "layer3" to JsonStr(probe.layer3.name),
        "elapsed_ms" to JsonInt(probe.elapsedMs),
    )

    private fun usb(usb: UsbDebugState): JsonObj = obj(
        "adb_enabled" to JsonBool(usb.adbEnabled),
        "wireless_debug_enabled" to JsonBool(usb.wirelessDebugEnabled),
        "developer_options_enabled" to JsonBool(usb.developerOptionsEnabled),
    )

    private fun cellular(cellular: CellularReadout): JsonObj = obj(
        "read_phone_state_granted" to JsonBool(cellular.readPhoneStateGranted),
        "precise_location_granted" to JsonBool(cellular.preciseLocationGranted),
        "location_services_enabled" to JsonBool(cellular.locationServicesEnabled),
        "sim_ready" to JsonBool(cellular.simReady),
        "mock_location_app_set" to JsonBool(cellular.mockLocationAppSet),
        "build_accepts_mock_locations" to JsonBool(cellular.buildAcceptsMockLocations),
    )

    private fun verdict(verdict: CaptureVerdict): JsonObj = obj(
        "public_api_measurements" to JsonStr(verdict.publicApiMeasurements.name),
        "push_cell_updates" to JsonStr(verdict.pushCellUpdates.name),
        "layer3_signalling" to JsonStr(verdict.layer3Signalling.name),
    )

    private fun obj(vararg members: Pair<String, JsonNode>): JsonObj = JsonObj(members.toList())

    private fun namePart(text: String?): String =
        text.orEmpty()
            .replace(UNSAFE_NAME_CHARS, "-")
            .trim('-')
            .take(MAX_NAME_PART)
            .trimEnd('-')
            .ifEmpty { "unknown" }
}
