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

// ---------------------------------------------------------------------------------------------------------
// Deep root & diagnostics (deep-root-spec.md). All additive; every new field defaults so existing callers,
// tests and the `/1` types keep compiling and keep their meaning. Honesty and privacy are the product:
// none of these types has a field that could hold a raw log line, a packet-capture byte, a diag dump, or a
// third-party identifier — they carry only capability FACTS (booleans, counts, versions, permission modes,
// interface names, device-node metadata). See §0 and §7 of the spec.
// ---------------------------------------------------------------------------------------------------------

/**
 * Whether the radio logcat buffer is readable through su — a fact, never its content. The line count is
 * produced in the device shell (`| wc -l`), so no log line ever crosses into the app process; this type has
 * no field a log line could be stored in (§0.3, §7).
 */
data class RadioLogReadout(
    /** Could `logcat -b radio -d` be read at all through su. */
    val readable: Boolean,
    /** A redacted count from `| wc -l`; `null` when not readable. Never a line, only an `Int`. */
    val lineCount: Int?,
)

/**
 * Kernel facts, identifier-free. Never carries the raw `/proc/version` build stamp (`user@host`, date or
 * build path) — only [redactedVersion], produced by [KernelParser.redactProcVersion].
 */
data class KernelInfo(
    /** `uname -r`, e.g. "5.10.101-android12-9-...". */
    val release: String?,
    /** `uname -m`, e.g. "aarch64". */
    val architecture: String?,
    /** `/proc/version` contains "SMP". */
    val smp: Boolean,
    /** `/proc/version` contains "PREEMPT". */
    val preempt: Boolean,
    /** `/proc/version` with the `user@host`, date tail and build path stripped; `null` when unavailable. */
    val redactedVersion: String?,
)

/**
 * Metadata of one diag-ish device node — never its content. [ownerUser]/[ownerGroup] are the node's own
 * uid/gid (root/radio/system), device metadata, not a subscriber id.
 */
data class DiagNodeStat(
    /** "/dev/diag", "/dev/ttyGS0", ... */
    val path: String,
    val exists: Boolean,
    /** The first `ls`/`stat` type token is a character device (starts with `c`). */
    val charDevice: Boolean,
    /** "660" from `stat -c %a`, or from an `ls -l` symbolic mode; `null` when denied or unknown. */
    val octalMode: String?,
    /** "root"/"radio"/"system" from `%U` or `ls` column 3, or `null`. */
    val ownerUser: String?,
    /** From `%G` or `ls` column 4, or `null`. */
    val ownerGroup: String?,
)

/** All diag-ish nodes probed: `/dev/diag` (the [primary]) plus `/dev/diag*`, `/dev/ttyGS*`, qcqmi. Metadata only. */
data class DiagNodes(val primary: DiagNodeStat, val others: List<DiagNodeStat>)

/** SELinux mode plus a plain-language consequence for an app path to diag. Nothing is changed. */
data class SelinuxAssessment(
    /** Reuses the existing [SelinuxMode] enum. */
    val mode: SelinuxMode,
    /** ENFORCING typically blocks an app-reachable diag path, unless the node was already proven readable. */
    val blocksAppDiagPath: Boolean,
    /** One plain sentence (copy from [CapabilityMessages]). */
    val consequence: String,
)

/** rmnet/QMI modem interfaces by name only — never an address, MAC or route. */
data class ModemInterfaces(val count: Int, val names: List<String>)

/** Capture-tool availability. Presence only; the app never captures. */
data class CaptureTooling(
    val tcpdumpPresent: Boolean,
    /** Which of the known paths / `which` hit. */
    val tcpdumpPaths: List<String>,
    /** A modem or wlan interface exists to capture on. */
    val pcapCapableInterfacePresent: Boolean,
)

/** A root manager and its version, from `PackageManager` (passive) — cheaply available, no su needed. */
data class RootManagerInfo(val pkg: String, val versionName: String?)

/**
 * The folded deep read-only result; `null` before the deep run or when su is unavailable. Every member is a
 * fact type — none can hold raw log/packet/diag content or an identifier.
 */
data class DeepDiagnostics(
    val kernel: KernelInfo,
    val selinux: SelinuxAssessment,
    val diagNodes: DiagNodes,
    /** Reuses the existing `/proc/config.gz` result. */
    val kernelDiagConfig: KernelConfigProbe,
    val modemInterfaces: ModemInterfaces,
    val captureTooling: CaptureTooling,
    val radioLog: RadioLogReadout,
)

/**
 * The single honest on-device layer-3 answer for the sub-verdict row and the export. [outcome] reuses the
 * unchanged [CapabilityVerdict.layer3] rule; deep diagnostics add [reason] and evidence, never a new
 * decision path. [laptopPath] is always offered when not viable.
 */
data class OnDeviceLayer3Verdict(
    /** Reuses [Layer3OnDevice]: POSSIBLE / NOT_POSSIBLE / UNKNOWN. */
    val outcome: Layer3OnDevice,
    /** `outcome == POSSIBLE`. */
    val viable: Boolean,
    /** e.g. "it is rooted, but its kernel has no diag device (/dev/diag is absent, and the kernel config reports no diag support)". */
    val reason: String,
    /** The laptop-over-USB path, always offered when not viable. */
    val laptopPath: String,
    /** From [UsbDebugState.adbEnabled]. */
    val usbDebuggingOn: Boolean,
)

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
    /**
     * Root managers found by `PackageManager` with their versions (passive). The [props] map now also
     * carries the allow-listed, non-identifier modem props (`gsm.version.ril-impl`, `ro.baseband`,
     * `ro.hardware`) besides the root-signal ones. Additive.
     */
    val rootManagerVersions: List<RootManagerInfo> = emptyList(),
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
    // ---- Deep sections (additive; all defaulted so the `/1` positional constructions still compile). ----
    /** `uname -r`. */
    val unameOutput: String? = null,
    /** `uname -m`. */
    val unameMachineOutput: String? = null,
    /**
     * `cat /proc/version`. **Transient**: parsed and redacted into [KernelInfo.redactedVersion] immediately;
     * it is never exported, logged, or otherwise persisted (§7).
     */
    val procVersionOutput: String? = null,
    /** `stat -c '%a %U %G %F' /dev/diag` — metadata only, never a byte of node content. */
    val diagStatOutput: String? = null,
    /** `ls -l /dev/diag* /dev/ttyGS* /dev/qcqmi*` — metadata only. */
    val diagNodesLsOutput: String? = null,
    /** `ls /sys/class/net` — interface names only, never an address. */
    val netListOutput: String? = null,
    /** The combined `which tcpdump` + known-path echo section. */
    val tcpdumpWhichOutput: String? = null,
    /** Path lines the tcpdump section resolved (su-side `which`/known-path hits). */
    val tcpdumpPathHits: List<String> = emptyList(),
    /** `logcat -b radio -d` was readable through su (a fact, never its content). */
    val radioLogReadable: Boolean = false,
    /** A redacted line count from the shell-side `| wc -l`; `null` when not readable. */
    val radioLogLineCount: Int? = null,
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
    /** Root managers found by `PackageManager` and their versions (passive; no su needed). Additive. */
    val rootManagerVersions: List<RootManagerInfo> = emptyList(),
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
    /** The deep read-only diagnostics; `null` when su was absent/denied/timed-out (graceful degradation). Additive. */
    val deep: DeepDiagnostics? = null,
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
    /**
     * The honest on-device layer-3 sub-verdict, beside [verdict]. Additive; `null` only on a directly
     * constructed report that never set it — [CapabilityReports.build] always populates it.
     */
    val onDeviceLayer3: OnDeviceLayer3Verdict? = null,
    val notes: List<String>,
) {
    companion object {
        /**
         * Bumped to `/2` because the shape grows (the `deep` block, `root_manager_versions` and
         * `on_device_layer3`). Every `/1` key is kept with the same name, order and meaning — a strict
         * superset — so a `/1`-era reader still finds every key it knew.
         */
        const val FORMAT: String = "fieldtap-capability/2"
    }
}
