package com.fieldtap.platform.capability

import android.content.Context
import com.fieldtap.app.AppInfo
import com.fieldtap.core.capability.CapabilityMessages
import com.fieldtap.core.capability.CapabilitySnapshot
import com.fieldtap.core.capability.CapabilityVerdict
import com.fieldtap.core.capability.DiagParser
import com.fieldtap.core.capability.Layer3OnDevice
import com.fieldtap.core.capability.PassiveInputs
import com.fieldtap.core.capability.RootDetector
import com.fieldtap.core.capability.RootProbeResult
import com.fieldtap.core.capability.SelinuxParser
import com.fieldtap.core.capability.SuOutputParser
import com.fieldtap.core.capability.UsbDebugState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The [CapabilityInspector]: gathers the raw inputs the pure `:core` logic decides on. It decides nothing
 * `:core` could decide — each adapter hands on a raw value.
 *
 * - [passive] runs the passive adapters (no su call), then `RootDetector.assess` + `CapabilityVerdict.snapshot`.
 * - [checkWithRoot] runs [SuperuserRunner], parses with `SelinuxParser`/`DiagParser`/`SuOutputParser`, folds
 *   the layer-3 conclusion with `CapabilityVerdict.layer3`, and attaches the `CapabilityMessages` sentence.
 *   It re-reads the (cheap) passive root signals and USB state so the facade stays stateless and the message
 *   and layer-3 conclusion always reflect the phone as it is at the moment of the check.
 *
 * Both calls do their work on [Dispatchers.IO]; the active check is cancellable (see [SuperuserRunner]).
 * No identifier is ever read.
 *
 * Owner: workstream `capability-core`.
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
        val folded = provisional.copy(layer3 = CapabilityVerdict.layer3(root, provisional))
        folded.copy(message = CapabilityMessages.rootProbeMessage(root, folded, inputs.usb))
    }

    private fun gatherPassive(): PassiveInputs {
        val propsAndTags = propReader.read()
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
            rootManagerPackages = packageScanner.scanInstalled(),
            writableSystemPaths = writablePathProbe.scanWritable(),
            usb = usb,
            cellular = cellularReadoutReader.read(mockLocationSet),
        )
    }
}
