package com.fieldtap.platform.probe

import android.content.Context
import android.os.Build
import com.fieldtap.app.AppInfo
import com.fieldtap.app.CapabilityProbe
import com.fieldtap.core.probe.ProbeJson
import com.fieldtap.core.probe.ProbeRecorder
import com.fieldtap.core.probe.ProbeReport
import com.fieldtap.core.probe.ProbeText
import com.fieldtap.core.time.Clock
import com.fieldtap.platform.Permissions
import com.fieldtap.platform.telephony.HandsetInfoReader
import com.fieldtap.platform.telephony.TelephonySource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The capability probe: collects `TelephonySource.inputs()` for `durationMs` while the Probe screen is
 * visible, feeds `CellInfoProbeAccumulator` and `ServiceStateProbeAccumulator` (through
 * `com.fieldtap.core.probe.ProbeRecorder`, where every rule lives), adds `probeRestrictedListeners()`, the
 * permission snapshot, SDK level and handset, and notes such as "NR SINR outside -23..40 dB". [export] writes
 * `ProbeJson.encode(report)` to [exportDir].
 *
 * - [run] first tries the three restricted listeners, then listens for `durationMs`, or less when the telephony
 *   flow ends by itself (a phone without a telephony service). `onProgress` gets `ProbeText.progress` about
 *   once a second, on the caller's dispatcher. Cancelling the caller unregisters every listener at once. Binder
 *   calls run on `Dispatchers.IO`. The report's `durationMs` is the time actually listened.
 * - [export] writes `ProbeJson.fileName(report)` through a `.tmp` file that is synced and renamed, so a
 *   half-written file is never shared; it throws [IOException] when the directory or file cannot be written.
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
    override suspend fun run(durationMs: Long, onProgress: (String) -> Unit): ProbeReport {
        require(durationMs > 0) { "durationMs must be positive, was $durationMs" }
        val recorder = ProbeRecorder()

        onProgress(TRYING_RESTRICTED)
        telephony.probeRestrictedListeners().forEach { recorder.record(it) }

        val startedElapsedMs = clock.elapsedRealtimeMillis()
        onProgress(ProbeText.progress(0, durationMs, 0))
        coroutineScope {
            val collector = launch {
                telephony.inputs(onRequest = recorder::onRequest).collect { recorder.record(it) }
            }
            val ticker = launch {
                while (true) {
                    delay(PROGRESS_PERIOD_MS)
                    val elapsedMs = clock.elapsedRealtimeMillis() - startedElapsedMs
                    onProgress(ProbeText.progress(elapsedMs, durationMs, recorder.answers()))
                }
            }
            // The whole duration, unless the flow ends by itself first.
            withTimeoutOrNull(durationMs) { collector.join() }
            ticker.cancelAndJoin()
            collector.cancelAndJoin()
        }
        val listenedMs = (clock.elapsedRealtimeMillis() - startedElapsedMs).coerceAtLeast(0)

        val report = withContext(Dispatchers.IO) {
            recorder.report(
                createdUtcMs = clock.wallMillis(),
                durationMs = listenedMs,
                appVersion = appInfo.versionName,
                versionCode = appInfo.versionCode,
                sdkInt = Build.VERSION.SDK_INT,
                handset = handset.read(fallbackNetworkType = recorder.lastDisplayNetworkType()),
                permissions = Permissions.snapshot(context),
            )
        }
        onProgress(ProbeText.progress(listenedMs, durationMs, report.cellInfo.answers))
        return report
    }

    override suspend fun export(report: ProbeReport): File = withContext(Dispatchers.IO) {
        if (!exportDir.isDirectory && !exportDir.mkdirs() && !exportDir.isDirectory) {
            throw IOException("Cannot create the export directory ${exportDir.path}")
        }
        val target = File(exportDir, ProbeJson.fileName(report))
        val temporary = File(exportDir, target.name + ".tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(ProbeJson.encode(report).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) throw IOException("Cannot move ${temporary.name} to ${target.name}")
        } catch (e: IOException) {
            temporary.delete()
            throw e
        }
        target
    }

    private companion object {
        const val PROGRESS_PERIOD_MS = 1_000L
        const val TRYING_RESTRICTED = "Trying the restricted listeners"
    }
}
