package com.fieldtap.core.capability

import com.fieldtap.format.HandsetMeta

/**
 * Assembles the exportable [CapabilityReport] from a [CapabilitySnapshot] and the optional root probe, so
 * the screen never has to fold the verdict or build the notes itself. The verdict is the snapshot's when
 * no probe ran, else the root-folded one; the notes come from [CapabilityMessages].
 *
 * Owner: workstream `capability-core`.
 */
object CapabilityReports {
    fun build(
        createdUtcMs: Long,
        appVersion: String,
        versionCode: Long,
        sdkInt: Int,
        handset: HandsetMeta,
        snapshot: CapabilitySnapshot,
        rootProbe: RootProbeResult?,
    ): CapabilityReport {
        val verdict = if (rootProbe == null) {
            snapshot.verdict
        } else {
            CapabilityVerdict.withRootProbe(snapshot.root, snapshot.usb, snapshot.cellular, rootProbe)
        }
        // The honest on-device layer-3 sub-verdict: from the folded probe once one ran, else from the
        // passive confidence via the unchanged rule. Always present in a built report.
        val onDeviceLayer3 = if (rootProbe == null) {
            OnDeviceLayer3.verdict(snapshot.root, snapshot.usb)
        } else {
            OnDeviceLayer3.verdict(rootProbe, snapshot.usb)
        }
        val base = CapabilityReport(
            createdUtcMs = createdUtcMs,
            appVersion = appVersion,
            versionCode = versionCode,
            sdkInt = sdkInt,
            handset = handset,
            root = snapshot.root,
            rootProbe = rootProbe,
            usb = snapshot.usb,
            cellular = snapshot.cellular,
            verdict = verdict,
            onDeviceLayer3 = onDeviceLayer3,
            notes = emptyList(),
        )
        return base.copy(notes = CapabilityMessages.notes(base))
    }
}
