package com.fieldtap.core.capability

import com.fieldtap.format.HandsetMeta

/**
 * The capability model: what 5gto6G FieldTap can measure on a handset, whether the phone is rooted,
 * whether it has a usable diag path for layer-3 signalling, and whether USB debugging is on for the
 * FieldTap-on-a-laptop path. Every type here is a plain `data class`/`enum`, immutable, and free of any
 * Android type, so every rule that reads it can be unit-tested on a JVM (this mirrors
 * `com.fieldtap.core.probe`).
 *
 * Honesty is the feature (see the capability spec, §0). Nothing here decodes signalling, and no field
 * carries an identifier: no IMEI, IMSI, ICCID, phone number, ANDROID_ID or advertising id.
 *
 * Owner: workstream `capability-core`.
 */

/** How strongly the passive signals point at root. See [RootDetector] for the table. */
enum class RootConfidence { NONE, LOW, MEDIUM, HIGH }

/** SELinux mode from `getenforce`. */
enum class SelinuxMode { ENFORCING, PERMISSIVE, DISABLED, UNKNOWN }

/** What `ls -l /dev/diag` said about the diag character device. */
enum class DiagDevice { PRESENT, ABSENT, PERMISSION_DENIED, UNKNOWN }

/**
 * Whether the DEVICE has a usable diag path for layer-3 signalling capture. This is about the phone's
 * kernel and node, never about this app decoding anything — 5gto6G FieldTap does not decode signalling.
 */
enum class Layer3OnDevice { POSSIBLE, NOT_POSSIBLE, UNKNOWN }

/** How the active "Check with root" run ended. */
enum class SuStatus { NOT_PRESENT, DENIED, TIMED_OUT, GRANTED, ERROR }

/**
 * Result of the read-only kernel-config check (`zcat /proc/config.gz | grep -i diag`, still through su).
 * `DIAG_PRESENT` when a diag config line matched, `DIAG_ABSENT` when the config was read and nothing
 * matched, `CONFIG_UNAVAILABLE` when `/proc/config.gz` was absent or unreadable.
 */
enum class KernelConfigProbe { DIAG_PRESENT, DIAG_ABSENT, CONFIG_UNAVAILABLE }

/** A tier's yes/no/unknown answer in the tiered verdict. */
enum class CaptureAnswer { YES, NO, UNKNOWN }

/**
 * USB-debugging state, read with no permission and no identifier. It matters because FieldTap on a laptop
 * captures diag over ADB, so USB debugging must be on for that path.
 */
data class UsbDebugState(
    /** `Settings.Global.getInt(cr, ADB_ENABLED "adb_enabled", 0) == 1`. */
    val adbEnabled: Boolean,
    /** `Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1`, API 30+. */
    val wirelessDebugEnabled: Boolean,
    /** `Settings.Global.getInt(cr, DEVELOPMENT_SETTINGS_ENABLED "development_settings_enabled", 0) == 1`. */
    val developerOptionsEnabled: Boolean,
)

/**
 * The permission/SIM/location snapshot the verdict reads. SIM state only — never the SIM's number or
 * ICCID.
 */
data class CellularReadout(
    val readPhoneStateGranted: Boolean,
    val preciseLocationGranted: Boolean,
    val locationServicesEnabled: Boolean,
    /** `TelephonyManager.getSimState() == SIM_STATE_READY` on any active modem slot. */
    val simReady: Boolean,
    /** `Settings.Secure "mock_location"` set, or `ALLOW_MOCK_LOCATION` on old APIs; the app name is not read. */
    val mockLocationAppSet: Boolean,
    /** This build rejects mock fixes (release) or accepts them (debug) — `FixSelector`'s own rule. */
    val buildAcceptsMockLocations: Boolean,
)

/** Passive inputs — gathered with no su call, safe to read whenever the screen opens. */
data class PassiveInputs(
    /** `getprop` map, only the keys we read ([RootDetector.PropKeys]); a missing key is absent from the map. */
    val props: Map<String, String>,
    /** `Build.TAGS`, verbatim (e.g. "release-keys", "test-keys"). */
    val buildTags: String,
    /** Which of [RootDetector.SU_PATHS] (plus `/debug_ram*` matches) exist on disk (`File.exists`, no exec). */
    val suBinariesPresent: List<String>,
    /** Which known root-manager packages `PackageManager` found. */
    val rootManagerPackages: List<String>,
    /** Which of [RootDetector.WRITABLE_PATHS] were writable (`File.canWrite`). */
    val writableSystemPaths: List<String>,
    val usb: UsbDebugState,
    val cellular: CellularReadout,
)

/**
 * Active inputs — the read-only output of one "Check with root" run. String fields are null when su never
 * answered (absent, denied, timed out).
 */
data class RootProbeRaw(
    val suStatus: SuStatus,
    /** `id` (or `whoami`). */
    val idOutput: String?,
    /** `getenforce`. */
    val getenforceOutput: String?,
    /** `ls -l /dev/diag`. */
    val diagLsOutput: String?,
    /** Result of the `/proc/config.gz` diag check. */
    val kernelConfigDiag: KernelConfigProbe,
    val elapsedMs: Long,
)

/**
 * The passive root assessment. [caveat] is always present, because root-hiding can hide a rooted phone.
 */
data class RootSignals(
    val suBinariesPresent: List<String>,
    val rootManagerPackages: List<String>,
    /** `Build.TAGS` contains "test-keys". */
    val buildTagsTestKeys: Boolean,
    /** `ro.debuggable == "1"`. */
    val debuggable: Boolean,
    /** `ro.secure == "0"`. */
    val secureOff: Boolean,
    val writableSystemPaths: List<String>,
    val confidence: RootConfidence,
    /** Always the root-hiding caveat, so "no root detected" is never presented as proof. */
    val caveat: String,
)

/** The folded result of one "Check with root" run. */
data class RootProbeResult(
    val suStatus: SuStatus,
    /** Parsed from `id`: `uid=0`. */
    val isRoot: Boolean,
    val selinux: SelinuxMode,
    val diagDevice: DiagDevice,
    val kernelDiag: KernelConfigProbe,
    val layer3: Layer3OnDevice,
    val elapsedMs: Long,
    /** One plain sentence, e.g. the OnePlus verdict. */
    val message: String,
)

/**
 * The tiered on-device verdict shown as "What 5gto6G FieldTap can capture on this phone". [lines] holds
 * one plain sentence per tier, honest and never implying the app decodes signalling.
 */
data class CaptureVerdict(
    /** Always YES. */
    val publicApiMeasurements: CaptureAnswer,
    /** YES iff `READ_PHONE_STATE` is granted. */
    val pushCellUpdates: CaptureAnswer,
    /** From the root probe, or UNKNOWN before the root check (unless there is no root path at all). */
    val layer3Signalling: Layer3OnDevice,
    /** One plain sentence per tier, in tier order. */
    val lines: List<String>,
    /** When layer-3 is NOT_POSSIBLE/UNKNOWN, the laptop path with whether USB debugging is on; else null. */
    val laptopPath: String?,
)

/** What the screen shows before/without the root check. */
data class CapabilitySnapshot(
    val root: RootSignals,
    val usb: UsbDebugState,
    val cellular: CellularReadout,
    /** `layer3Signalling` is UNKNOWN when root is likely and untested, NOT_POSSIBLE when there is no root path. */
    val verdict: CaptureVerdict,
)

/**
 * The exportable report. Contains no identifier and no location. `rootProbe` is present only after
 * "Check with root"; null when it was never run.
 */
data class CapabilityReport(
    val createdUtcMs: Long,
    val appVersion: String,
    val versionCode: Long,
    val sdkInt: Int,
    /** Reuses `:format`'s already-identifier-free handset metadata. */
    val handset: HandsetMeta,
    val root: RootSignals,
    val rootProbe: RootProbeResult?,
    val usb: UsbDebugState,
    val cellular: CellularReadout,
    val verdict: CaptureVerdict,
    val notes: List<String>,
) {
    companion object {
        const val FORMAT: String = "fieldtap-capability/1"
    }
}
