package com.fieldtap.core.capability

/**
 * The tiered verdict logic — the heart of the feature. Pure and exhaustively table-tested.
 *
 * [layer3] answers whether the DEVICE has a usable diag path for layer-3 signalling capture (never about
 * this app decoding anything). Its table (capability spec §3.5):
 *
 * | Root state | `/dev/diag` | kernel config | ⇒ Layer3OnDevice |
 * | --- | --- | --- | --- |
 * | su GRANTED, isRoot | PRESENT | (any) | POSSIBLE (ls listed the node, so access works even under enforcing) |
 * | su GRANTED, isRoot | PERMISSION_DENIED | (any) | NOT_POSSIBLE (the node is blocked) |
 * | su GRANTED, isRoot | ABSENT | (any) | NOT_POSSIBLE (no node — the OnePlus case) |
 * | su GRANTED, isRoot | UNKNOWN | DIAG_ABSENT | NOT_POSSIBLE (kernel has no diag) |
 * | su GRANTED, isRoot | UNKNOWN | else | UNKNOWN (node not confirmed) |
 * | su DENIED / TIMED_OUT / ERROR | — | — | UNKNOWN (the device could not be tested) |
 * | su NOT_PRESENT | — | — | NOT_POSSIBLE (no working root) |
 * | no probe, passive HIGH | — | — | UNKNOWN (root likely; run Check with root) |
 * | no probe, passive MEDIUM/LOW/NONE | — | — | NOT_POSSIBLE (no su or manager found — no root path) |
 *
 * A `PRESENT` node means `ls -l /dev/diag` succeeded as root, so access is possible even under
 * `ENFORCING`; only an explicit `PERMISSION_DENIED` on the node marks it blocked. `getenforce` is reported
 * for the engineer but does not by itself flip POSSIBLE → NOT_POSSIBLE when the node was readable.
 *
 * Owner: workstream `capability-core`.
 */
object CapabilityVerdict {
    /** Before/without the root check: layer-3 is UNKNOWN when root is likely, NOT_POSSIBLE when there is no root path. */
    fun snapshot(root: RootSignals, usb: UsbDebugState, cellular: CellularReadout): CaptureVerdict =
        build(root, usb, cellular, probe = null)

    /** After "Check with root": fold the root probe into the verdict. */
    fun withRootProbe(
        root: RootSignals,
        usb: UsbDebugState,
        cellular: CellularReadout,
        probe: RootProbeResult,
    ): CaptureVerdict = build(root, usb, cellular, probe)

    /** The layer-3 conclusion. See the table on this object. */
    fun layer3(root: RootSignals, probe: RootProbeResult?): Layer3OnDevice {
        if (probe == null) {
            return if (root.confidence == RootConfidence.HIGH) Layer3OnDevice.UNKNOWN else Layer3OnDevice.NOT_POSSIBLE
        }
        return when (probe.suStatus) {
            SuStatus.DENIED, SuStatus.TIMED_OUT, SuStatus.ERROR -> Layer3OnDevice.UNKNOWN
            SuStatus.NOT_PRESENT -> Layer3OnDevice.NOT_POSSIBLE
            SuStatus.GRANTED ->
                if (!probe.isRoot) {
                    Layer3OnDevice.NOT_POSSIBLE
                } else {
                    when (probe.diagDevice) {
                        DiagDevice.PRESENT -> Layer3OnDevice.POSSIBLE
                        DiagDevice.ABSENT -> Layer3OnDevice.NOT_POSSIBLE
                        DiagDevice.PERMISSION_DENIED -> Layer3OnDevice.NOT_POSSIBLE
                        DiagDevice.UNKNOWN ->
                            if (probe.kernelDiag == KernelConfigProbe.DIAG_ABSENT) {
                                Layer3OnDevice.NOT_POSSIBLE
                            } else {
                                Layer3OnDevice.UNKNOWN
                            }
                    }
                }
        }
    }

    private fun build(
        root: RootSignals,
        usb: UsbDebugState,
        cellular: CellularReadout,
        probe: RootProbeResult?,
    ): CaptureVerdict {
        val l3 = layer3(root, probe)
        val push = if (cellular.readPhoneStateGranted) CaptureAnswer.YES else CaptureAnswer.NO
        val lines = listOf(
            CapabilityMessages.tier1(),
            CapabilityMessages.tier2(cellular.readPhoneStateGranted),
            CapabilityMessages.tier3(l3, root, probe),
        )
        val laptopPath = if (l3 == Layer3OnDevice.POSSIBLE) null else CapabilityMessages.laptopPath(usb)
        return CaptureVerdict(
            publicApiMeasurements = CaptureAnswer.YES,
            pushCellUpdates = push,
            layer3Signalling = l3,
            lines = lines,
            laptopPath = laptopPath,
        )
    }
}
