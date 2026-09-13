package com.fieldtap.ui.probe

import com.fieldtap.core.capability.CapabilityMessages
import com.fieldtap.core.capability.CaptureAnswer
import com.fieldtap.core.capability.CaptureVerdict
import com.fieldtap.core.capability.CapabilitySnapshot
import com.fieldtap.core.capability.CapabilityVerdict
import com.fieldtap.core.capability.CaptureTooling
import com.fieldtap.core.capability.CellularReadout
import com.fieldtap.core.capability.DeepDiagnostics
import com.fieldtap.core.capability.DiagNodeStat
import com.fieldtap.core.capability.KernelConfigProbe
import com.fieldtap.core.capability.KernelInfo
import com.fieldtap.core.capability.Layer3OnDevice
import com.fieldtap.core.capability.ModemInterfaces
import com.fieldtap.core.capability.OnDeviceLayer3Verdict
import com.fieldtap.core.capability.RadioLogReadout
import com.fieldtap.core.capability.RootConfidence
import com.fieldtap.core.capability.RootDetector
import com.fieldtap.core.capability.RootManagerInfo
import com.fieldtap.core.capability.RootProbeResult
import com.fieldtap.core.capability.SelinuxMode
import com.fieldtap.core.capability.UsbDebugState
import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.RadioListener
import com.fieldtap.core.probe.ProbeNotes
import com.fieldtap.platform.Permissions
import com.fieldtap.ui.theme.Formats
import com.fieldtap.ui.theme.StatusTone
import java.util.Locale

/** A listener outcome as the Probe screen words it, with the tone of its value. */
internal enum class ListenerWord(val tone: StatusTone) {
    REGISTERED(StatusTone.SUCCESS),
    MISSING_PERMISSION(StatusTone.WARNING),

    /** Refused although an ordinary app should get it. */
    REFUSED(StatusTone.ERROR),

    /** One of the three listeners that need READ_PRECISE_PHONE_STATE, refused as every ordinary app is. */
    REFUSED_EXPECTED(StatusTone.NEUTRAL),
    NOT_REGISTERED(StatusTone.WARNING),
    FAILED(StatusTone.ERROR),
}

/** An answer that can also be "not enough data". */
internal enum class ProbeAnswer { YES, NO, NOT_ENOUGH }

/** The permissions the probe reports, by the words the screen uses. */
internal enum class ProbePermissionLabel { PRECISE_LOCATION, APPROXIMATE_LOCATION, NOTIFICATIONS, PHONE, OTHER }

/**
 * The Probe screen's decisions, pure so they are unit-tested. The findings and the capability verdict
 * sentences themselves come from `com.fieldtap.core.probe` and `com.fieldtap.core.capability`; this only
 * decides how the report's values are worded and toned, and folds the passive capability snapshot with an
 * optional root-check result. Honesty is the feature: layer-3 being "not possible" on a phone is a normal,
 * neutral fact (grey), never an error, and this screen never implies the app decodes signalling.
 *
 * Owner: workstream `ui-setup`.
 */
internal object ProbePresentation {
    fun listenerWord(listener: RadioListener, outcome: ListenerOutcome): ListenerWord {
        val restricted = listener in ProbeNotes.RESTRICTED
        return when (outcome) {
            ListenerOutcome.REGISTERED -> ListenerWord.REGISTERED
            ListenerOutcome.MISSING_PERMISSION -> ListenerWord.MISSING_PERMISSION
            ListenerOutcome.REFUSED_BY_PLATFORM -> if (restricted) ListenerWord.REFUSED_EXPECTED else ListenerWord.REFUSED
            ListenerOutcome.UNREGISTERED -> if (restricted) ListenerWord.REFUSED_EXPECTED else ListenerWord.NOT_REGISTERED
            ListenerOutcome.FAILED -> ListenerWord.FAILED
        }
    }

    /** Every listener in [RadioListener] order; one the report does not name counts as never registered. */
    fun listenerRows(listeners: Map<RadioListener, ListenerOutcome>): List<Pair<RadioListener, ListenerOutcome>> =
        RadioListener.entries.map { listener -> listener to (listeners[listener] ?: ListenerOutcome.UNREGISTERED) }

    fun timestamps(advance: Boolean?): ProbeAnswer = when (advance) {
        true -> ProbeAnswer.YES
        false -> ProbeAnswer.NO
        null -> ProbeAnswer.NOT_ENOUGH
    }

    /** The range between [min] and [max], or null unless both are known. */
    fun range(min: Int?, max: Int?): IntRange? {
        if (min == null || max == null) return null
        return if (min <= max) min..max else max..min
    }

    /** Milliseconds as seconds with one decimal, for "30.0 s". */
    fun seconds(ms: Long, locale: Locale = Locale.getDefault()): String = Formats.oneDecimal(ms / MS_PER_SECOND, locale)

    fun permissionLabel(name: String): ProbePermissionLabel = when (name) {
        Permissions.FINE_LOCATION -> ProbePermissionLabel.PRECISE_LOCATION
        Permissions.COARSE_LOCATION -> ProbePermissionLabel.APPROXIMATE_LOCATION
        Permissions.POST_NOTIFICATIONS -> ProbePermissionLabel.NOTIFICATIONS
        Permissions.READ_PHONE_STATE -> ProbePermissionLabel.PHONE
        else -> ProbePermissionLabel.OTHER
    }

    /** The values joined with a middle dot, or null when there are none. */
    fun joined(values: List<String>): String? = values.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)

    // ---- Capability panel (the tiered "what this phone can capture" verdict + root/diag/USB) ----

    /**
     * The tiered verdict to show now: the passive snapshot's when no root check has run, otherwise the
     * snapshot folded with the root-check result (which decides the layer-3 answer). All the copy inside
     * lives in `com.fieldtap.core.capability.CapabilityMessages`.
     */
    fun verdict(snapshot: CapabilitySnapshot, rootProbe: RootProbeResult?): CaptureVerdict =
        if (rootProbe == null) {
            snapshot.verdict
        } else {
            CapabilityVerdict.withRootProbe(snapshot.root, snapshot.usb, snapshot.cellular, rootProbe)
        }

    /**
     * Whether the phone can measure a serving cell right now. Public-API measurement is always possible, but
     * it returns nothing without precise location and location services, so the top verdict chip warns when
     * either is missing. It never blocks anything: the tiers below still say the app can measure on any phone.
     */
    fun canMeasureNow(cellular: CellularReadout): Boolean =
        cellular.preciseLocationGranted && cellular.locationServicesEnabled

    /** Tier 1/2 dot tone: YES is good, NO is advice (a permission is missing), UNKNOWN is neutral. */
    fun captureAnswerTone(answer: CaptureAnswer): StatusTone = when (answer) {
        CaptureAnswer.YES -> StatusTone.SUCCESS
        CaptureAnswer.NO -> StatusTone.WARNING
        CaptureAnswer.UNKNOWN -> StatusTone.NEUTRAL
    }

    /**
     * Layer-3 dot tone. NOT_POSSIBLE is grey, not red: most phones (the lead's OnePlus included) simply have
     * no diag device, and that is a normal fact, not a fault. Only POSSIBLE earns a success dot.
     */
    fun layer3Tone(l3: Layer3OnDevice): StatusTone = when (l3) {
        Layer3OnDevice.POSSIBLE -> StatusTone.SUCCESS
        Layer3OnDevice.NOT_POSSIBLE -> StatusTone.NEUTRAL
        Layer3OnDevice.UNKNOWN -> StatusTone.NEUTRAL
    }

    /**
     * The layer-3 tier's detail line: the honest per-phone sentence, plus the USB-debugging line only when
     * capture is definitively NOT_POSSIBLE (so the "use a laptop over USB" advice comes with whether ADB is
     * ready). For UNKNOWN (root likely but untested) the USB line would be noise, so it is left off.
     */
    fun layer3Detail(verdict: CaptureVerdict): String {
        val line = verdict.lines.getOrNull(TIER_LAYER3).orEmpty()
            // The root-hiding caveat is stated once, in the Root & diagnostics card below; the Layer-3 row need not
            // repeat it word for word (the no-root layer-3 line carries it, so drop it here).
            .replace(RootDetector.CAVEAT, "")
            .replace("  ", " ")
            .trim()
        val laptop = verdict.laptopPath?.takeIf { verdict.layer3Signalling == Layer3OnDevice.NOT_POSSIBLE }
        return listOfNotNull(line.ifEmpty { null }, laptop).joinToString(" ")
    }

    fun tier1Detail(verdict: CaptureVerdict): String = verdict.lines.getOrNull(TIER_PUBLIC_API).orEmpty()

    fun tier2Detail(verdict: CaptureVerdict): String = verdict.lines.getOrNull(TIER_PUSH).orEmpty()

    /**
     * The passive root confidence stays calm: HIGH/MEDIUM are informative (the accent), LOW/NONE neutral. The
     * confidence word is the cue, not a loud colour; the always-present caveat keeps "no root" from reading
     * as proof.
     */
    fun rootConfidenceTone(confidence: RootConfidence): StatusTone = when (confidence) {
        RootConfidence.HIGH, RootConfidence.MEDIUM -> StatusTone.INFO
        RootConfidence.LOW, RootConfidence.NONE -> StatusTone.NEUTRAL
    }

    // ---- Deep diagnostics (deep-root-spec §5): the pure mapping from the :core model to labelled rows ----

    /**
     * One row of the Deep diagnostics readout: a sentence-case [label], its [value] (a fact — never a log
     * line, packet byte, diag dump or identifier; see the privacy invariants), and the [tone] it carries.
     * Every plain fact is [StatusTone.NEUTRAL]; only a found root manager is [StatusTone.INFO]. The
     * not-possible on-device layer-3 verdict is a separate row and stays neutral grey ([layer3SubVerdictTone]).
     */
    internal data class DeepRow(val label: String, val value: String, val tone: StatusTone)

    /**
     * The structural labels and value words the Deep diagnostics rows need, resolved from the screen's string
     * resources. This workstream adds only structural labels; every verdict/consequence sentence stays in
     * `:core` [CapabilityMessages]. The bundle is built in the composable and passed to the pure [deepRows]
     * mapping, so the mapping stays free of Android and is JVM-tested.
     */
    internal data class DeepDiagnosticsLabels(
        val rootManager: String,
        val kernel: String,
        val selinux: String,
        val diagDevice: String,
        val kernelDiagConfig: String,
        val modemInterfaces: String,
        val captureTooling: String,
        val radioLog: String,
        val usbDebugging: String,
        val rootManagerNone: String,
        val unknown: String,
        val smp: String,
        val preempt: String,
        val selinuxEnforcing: String,
        val selinuxPermissive: String,
        val selinuxDisabled: String,
        val selinuxUnknown: String,
        val diagAbsent: String,
        /** "Present · %1$s", the %1$s being the node's mode and owner (e.g. "660 radio:radio"). */
        val diagPresentMeta: String,
        val diagPresentDenied: String,
        val kernelDiagPresent: String,
        val kernelDiagAbsent: String,
        val kernelDiagUnavailable: String,
        val modemNone: String,
        /** "%1$d — %2$s", the count and the comma-joined interface names. */
        val modemValue: String,
        /** "tcpdump present (%1$s)", the %1$s being the path(s). */
        val capturePresent: String,
        val captureNone: String,
        /** "Readable (%1$d lines sampled)". */
        val radioReadable: String,
        val radioNotReadable: String,
        val usbOn: String,
        val usbOffDevOptions: String,
        val usbOff: String,
    )

    /**
     * The nine Deep diagnostics rows in spec order (deep-root-spec §5), each a plain fact. Pure and
     * JVM-tested. Every row is [StatusTone.NEUTRAL] except a found root manager ([StatusTone.INFO]); the
     * on-device layer-3 verdict is rendered separately (see [layer3SubVerdictTone]). No row can carry a log
     * line or diag byte — the [DeepDiagnostics] model has no such field.
     */
    internal fun deepRows(
        deep: DeepDiagnostics,
        rootManagerVersions: List<RootManagerInfo>,
        usb: UsbDebugState,
        labels: DeepDiagnosticsLabels,
    ): List<DeepRow> = listOf(
        rootManagerRow(rootManagerVersions, labels),
        DeepRow(labels.kernel, kernelValue(deep.kernel, labels), StatusTone.NEUTRAL),
        DeepRow(labels.selinux, selinuxWord(deep.selinux.mode, labels), StatusTone.NEUTRAL),
        DeepRow(labels.diagDevice, diagNodeValue(deep.diagNodes.primary, labels), StatusTone.NEUTRAL),
        DeepRow(labels.kernelDiagConfig, kernelDiagWord(deep.kernelDiagConfig, labels), StatusTone.NEUTRAL),
        DeepRow(labels.modemInterfaces, modemValue(deep.modemInterfaces, labels), StatusTone.NEUTRAL),
        DeepRow(labels.captureTooling, captureValue(deep.captureTooling, labels), StatusTone.NEUTRAL),
        DeepRow(labels.radioLog, radioLogValue(deep.radioLog, labels), StatusTone.NEUTRAL),
        usbRow(usb, labels),
    )

    /**
     * The passive rows shown before "Check with root" (or when su was unavailable, so `deep` is null): the
     * root manager and USB debugging, both readable with no su. Pure.
     */
    internal fun deepPassiveRows(
        rootManagerVersions: List<RootManagerInfo>,
        usb: UsbDebugState,
        labels: DeepDiagnosticsLabels,
    ): List<DeepRow> = listOf(rootManagerRow(rootManagerVersions, labels), usbRow(usb, labels))

    private fun rootManagerRow(versions: List<RootManagerInfo>, labels: DeepDiagnosticsLabels): DeepRow =
        DeepRow(
            label = labels.rootManager,
            value = rootManagerLine(versions, labels.rootManagerNone),
            tone = if (versions.isEmpty()) StatusTone.NEUTRAL else StatusTone.INFO,
        )

    /**
     * The root managers found as "Magisk 27.0, KernelSU 0.9.5", or [noneWord] when none. Display names come
     * from the stable [RootDetector.ROOT_MANAGER_PACKAGES] ids; an unknown package shows its id verbatim, and
     * a missing version drops the version. Pure.
     */
    fun rootManagerLine(versions: List<RootManagerInfo>, noneWord: String): String {
        if (versions.isEmpty()) return noneWord
        return versions.joinToString(", ") { info ->
            val name = ROOT_MANAGER_NAMES[info.pkg] ?: info.pkg
            val version = info.versionName?.takeIf { it.isNotBlank() }
            if (version != null) "$name $version" else name
        }
    }

    private fun kernelValue(kernel: KernelInfo, labels: DeepDiagnosticsLabels): String {
        val release = kernel.release?.takeIf { it.isNotBlank() } ?: labels.unknown
        val flags = listOfNotNull(labels.smp.takeIf { kernel.smp }, labels.preempt.takeIf { kernel.preempt })
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
        return listOfNotNull(release, kernel.architecture?.takeIf { it.isNotBlank() }, flags).joinToString(SEPARATOR)
    }

    private fun selinuxWord(mode: SelinuxMode, labels: DeepDiagnosticsLabels): String = when (mode) {
        SelinuxMode.ENFORCING -> labels.selinuxEnforcing
        SelinuxMode.PERMISSIVE -> labels.selinuxPermissive
        SelinuxMode.DISABLED -> labels.selinuxDisabled
        SelinuxMode.UNKNOWN -> labels.selinuxUnknown
    }

    private fun diagNodeValue(node: DiagNodeStat, labels: DeepDiagnosticsLabels): String = when {
        !node.exists -> labels.diagAbsent
        // Present but denied: the node exists yet its mode could not be read (SELinux hid it). Never a fault here.
        node.octalMode == null -> labels.diagPresentDenied
        else -> {
            val owner = listOfNotNull(node.ownerUser, node.ownerGroup).joinToString(":").takeIf { it.isNotBlank() }
            val meta = listOfNotNull(node.octalMode, owner).joinToString(" ")
            String.format(labels.diagPresentMeta, meta)
        }
    }

    private fun kernelDiagWord(config: KernelConfigProbe, labels: DeepDiagnosticsLabels): String = when (config) {
        KernelConfigProbe.DIAG_PRESENT -> labels.kernelDiagPresent
        KernelConfigProbe.DIAG_ABSENT -> labels.kernelDiagAbsent
        KernelConfigProbe.CONFIG_UNAVAILABLE -> labels.kernelDiagUnavailable
    }

    private fun modemValue(interfaces: ModemInterfaces, labels: DeepDiagnosticsLabels): String =
        if (interfaces.names.isEmpty()) {
            labels.modemNone
        } else {
            String.format(labels.modemValue, interfaces.count, interfaces.names.joinToString(", "))
        }

    private fun captureValue(tooling: CaptureTooling, labels: DeepDiagnosticsLabels): String =
        if (tooling.tcpdumpPresent) String.format(labels.capturePresent, tooling.tcpdumpPaths.joinToString(", ")) else labels.captureNone

    private fun radioLogValue(radio: RadioLogReadout, labels: DeepDiagnosticsLabels): String =
        if (radio.readable) String.format(labels.radioReadable, radio.lineCount ?: 0) else labels.radioNotReadable

    private fun usbRow(usb: UsbDebugState, labels: DeepDiagnosticsLabels): DeepRow =
        DeepRow(labels.usbDebugging, usbValue(usb, labels), StatusTone.NEUTRAL)

    private fun usbValue(usb: UsbDebugState, labels: DeepDiagnosticsLabels): String = when {
        usb.adbEnabled -> labels.usbOn
        !usb.developerOptionsEnabled -> labels.usbOffDevOptions
        else -> labels.usbOff
    }

    /**
     * The on-device layer-3 sub-verdict's mark tone: SUCCESS when viable, otherwise **neutral grey** — a
     * phone that cannot do on-device diag is a normal fact, never an error (deep-root-spec §5). Reuses
     * [layer3Tone], so the sub-verdict and the tier row can never disagree.
     */
    fun layer3SubVerdictTone(outcome: Layer3OnDevice): StatusTone = layer3Tone(outcome)

    /**
     * The sub-verdict's detail sentence, assembled from the `:core` [OnDeviceLayer3Verdict] copy: the reason,
     * and — when not viable — the laptop-over-USB path and the USB-debugging line (both `:core`). No verdict
     * copy is authored here; only the leading capital and the joining are this screen's. Pure.
     */
    fun layer3SubVerdictDetail(verdict: OnDeviceLayer3Verdict, usb: UsbDebugState): String {
        val reason = verdict.reason.replaceFirstChar { it.uppercaseChar() }
        val reasonSentence = if (reason.endsWith('.')) reason else "$reason."
        return if (verdict.viable) {
            reasonSentence
        } else {
            listOf(reasonSentence, verdict.laptopPath, CapabilityMessages.laptopPath(usb)).joinToString(" ")
        }
    }

    /** Short display names for the known root managers, keyed by [RootDetector.ROOT_MANAGER_PACKAGES] ids. */
    private val ROOT_MANAGER_NAMES: Map<String, String> = mapOf(
        "com.topjohnwu.magisk" to "Magisk",
        "me.weishu.kernelsu" to "KernelSU",
        "me.bmax.apatch" to "APatch",
        "eu.chainfire.supersu" to "SuperSU",
    )

    private const val TIER_PUBLIC_API = 0
    private const val TIER_PUSH = 1
    private const val TIER_LAYER3 = 2
    private const val MS_PER_SECOND = 1000.0
    private const val SEPARATOR = " · "
}
