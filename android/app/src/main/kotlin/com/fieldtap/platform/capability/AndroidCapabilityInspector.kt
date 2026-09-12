package com.fieldtap.platform.capability

import android.content.Context
import com.fieldtap.app.AppInfo
import com.fieldtap.core.capability.CapabilityMessages
import com.fieldtap.core.capability.CapabilitySnapshot
import com.fieldtap.core.capability.CapabilityVerdict
import com.fieldtap.core.capability.DeepDiagnostics
import com.fieldtap.core.capability.DiagNodes
import com.fieldtap.core.capability.DiagNodesParser
import com.fieldtap.core.capability.DiagParser
import com.fieldtap.core.capability.DiagStatParser
import com.fieldtap.core.capability.KernelParser
import com.fieldtap.core.capability.Layer3OnDevice
import com.fieldtap.core.capability.ModemInterfaceParser
import com.fieldtap.core.capability.PassiveInputs
import com.fieldtap.core.capability.RadioLogReadout
import com.fieldtap.core.capability.RootDetector
import com.fieldtap.core.capability.RootProbeRaw
import com.fieldtap.core.capability.RootProbeResult
import com.fieldtap.core.capability.SelinuxAssessor
import com.fieldtap.core.capability.SelinuxParser
import com.fieldtap.core.capability.SuOutputParser
import com.fieldtap.core.capability.SuStatus
import com.fieldtap.core.capability.TcpdumpParser
import com.fieldtap.core.capability.UsbDebugState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The [CapabilityInspector]: gathers the raw inputs the pure `:core` logic decides on. It decides nothing
 * `:core` could decide — each adapter hands on a raw value.
 *
 * - [passive] runs the passive adapters (no su call), then `RootDetector.assess` + `CapabilityVerdict.snapshot`.
 *   The passive facts now also include the root-manager `versionName`s (via [PackageScanner]) and the
 *   allow-listed modem props (via [PropReader]).
 * - [checkWithRoot] runs [SuperuserRunner], parses the `/1` sections with `SelinuxParser`/`DiagParser`/
 *   `SuOutputParser`, folds the layer-3 conclusion with `CapabilityVerdict.layer3`, and — only when su was
 *   **granted** — parses the deep read-only sections into a [DeepDiagnostics] with the `:core` deep parsers.
 *   When su is absent/denied/timed-out/errored, `deep` is null (graceful degradation, deep-root-spec §4).
 *   The `CapabilityMessages` sentence is attached exactly as before.
 *
 * Both calls do their work on [Dispatchers.IO]; the active check is cancellable (see [SuperuserRunner]).
 * No identifier is ever read; the deep parse touches only capability facts, never a raw log line, a packet,
 * a diag byte or a subscriber id (deep-root-spec §0, §7).
 *
 * Owner: workstream `deep-root-core`.
 */
class AndroidCapabilityInspector(
    context: Context,
    appInfo: AppInfo,
    private val superuserRunner: SuperuserRunner = SuperuserRunner(),
) : CapabilityInspector {
    private val appContext = context.applicationContext
    private val propReader = PropReader()
    private val suBinaryScanner = SuBinaryScanner()
    private val packageScanner = PackageScanner(appContext.packageManager)
    private val writablePathProbe = WritablePathProbe()
    private val settingsReader = SettingsReader(appContext)
    private val cellularReadoutReader = CellularReadoutReader(appContext, appInfo)
    private val netInterfaceScanner = NetInterfaceScanner()
    private val tcpdumpScanner = TcpdumpScanner()

    override suspend fun passive(): CapabilitySnapshot = withContext(Dispatchers.IO) {
        val inputs = gatherPassive()
        val root = RootDetector.assess(inputs)
        CapabilitySnapshot(
            root = root,
            usb = inputs.usb,
            cellular = inputs.cellular,
            verdict = CapabilityVerdict.snapshot(root, inputs.usb, inputs.cellular),
        )
    }

    override suspend fun checkWithRoot(): RootProbeResult = withContext(Dispatchers.IO) {
        val inputs = gatherPassive()
        val root = RootDetector.assess(inputs)
        val raw = superuserRunner.run()
        val provisional = RootProbeResult(
            suStatus = raw.suStatus,
            isRoot = SuOutputParser.isRoot(raw.idOutput),
            selinux = SelinuxParser.parse(raw.getenforceOutput),
            diagDevice = DiagParser.device(raw.diagLsOutput),
            kernelDiag = raw.kernelConfigDiag,
            layer3 = Layer3OnDevice.UNKNOWN,
            elapsedMs = raw.elapsedMs,
            message = "",
        )
        val deep = if (raw.suStatus == SuStatus.GRANTED) buildDeep(raw, provisional) else null
        val folded = provisional.copy(layer3 = CapabilityVerdict.layer3(root, provisional), deep = deep)
        folded.copy(message = CapabilityMessages.rootProbeMessage(root, folded, inputs.usb))
    }

    /**
     * Folds the raw deep sections into a [DeepDiagnostics] with the pure `:core` parsers. The net list and
     * tcpdump presence fall back to / union with the passive readers so the result is complete even when a
     * su-side command produced nothing. Only facts are read — never node/log/packet content.
     */
    private fun buildDeep(raw: RootProbeRaw, provisional: RootProbeResult): DeepDiagnostics {
        val netList = raw.netListOutput ?: netInterfaceScanner.list()
        return DeepDiagnostics(
            kernel = KernelParser.parse(raw.unameOutput, raw.unameMachineOutput, raw.procVersionOutput),
            selinux = SelinuxAssessor.assess(provisional.selinux, provisional.diagDevice),
            diagNodes = DiagNodes(
                primary = DiagStatParser.parse(raw.diagStatOutput),
                others = DiagNodesParser.parse(raw.diagNodesLsOutput),
            ),
            kernelDiagConfig = raw.kernelConfigDiag,
            modemInterfaces = ModemInterfaceParser.parse(netList),
            captureTooling = TcpdumpParser.parse(
                whichOutput = raw.tcpdumpWhichOutput,
                knownPathHits = (raw.tcpdumpPathHits + tcpdumpScanner.knownPathHits()).distinct(),
                anyCaptureInterface = ModemInterfaceParser.anyCaptureInterface(netList),
            ),
            radioLog = RadioLogReadout(readable = raw.radioLogReadable, lineCount = raw.radioLogLineCount),
        )
    }

    private fun gatherPassive(): PassiveInputs {
        val propsAndTags = propReader.read()
        val managerVersions = packageScanner.scanManagerVersions()
        val mockLocationSet = try {
            settingsReader.readMockLocation()
        } catch (e: RuntimeException) {
            false
        }
        val usb = try {
            settingsReader.readUsb()
        } catch (e: RuntimeException) {
            UsbDebugState(adbEnabled = false, wirelessDebugEnabled = false, developerOptionsEnabled = false)
        }
        return PassiveInputs(
            props = propsAndTags.props,
            buildTags = propsAndTags.buildTags,
            suBinariesPresent = suBinaryScanner.scan(),
            rootManagerPackages = managerVersions.map { it.pkg },
            writableSystemPaths = writablePathProbe.scanWritable(),
            usb = usb,
            cellular = cellularReadoutReader.read(mockLocationSet),
            rootManagerVersions = managerVersions,
        )
    }
}
