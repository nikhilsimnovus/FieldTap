package com.fieldtap.platform.probe

import android.content.Context
import com.fieldtap.app.AppInfo
import com.fieldtap.app.CapabilityProbe
import com.fieldtap.core.probe.ProbeReport
import com.fieldtap.core.time.Clock
import com.fieldtap.platform.telephony.HandsetInfoReader
import com.fieldtap.platform.telephony.TelephonySource
import java.io.File

/**
 * The capability probe: collects `TelephonySource.inputs()` for `durationMs` while the Probe screen is
 * visible, feeds `CellInfoProbeAccumulator` and `ServiceStateProbeAccumulator`, adds
 * `probeRestrictedListeners()`, the permission snapshot, SDK level and handset, and notes such as
 * "SINR outside -20..30 seen". [export] writes `ProbeJson.encode(report)` to [exportDir].
 *
 * Owner: workstream `platform-adapters`.
 */
class CapabilityProbeRunner(
    private val context: Context,
    private val clock: Clock,
    private val telephony: TelephonySource,
    private val handset: HandsetInfoReader,
    private val appInfo: AppInfo,
    private val exportDir: File,
) : CapabilityProbe {
    override suspend fun run(durationMs: Long, onProgress: (String) -> Unit): ProbeReport = TODO("platform-adapters")

    override suspend fun export(report: ProbeReport): File = TODO("platform-adapters")
}
