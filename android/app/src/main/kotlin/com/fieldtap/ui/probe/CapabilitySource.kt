package com.fieldtap.ui.probe

import android.content.Context
import android.os.Build
import com.fieldtap.app.AppInfo
import com.fieldtap.core.capability.CapabilityJson
import com.fieldtap.core.capability.CapabilityReport
import com.fieldtap.core.capability.CapabilityReports
import com.fieldtap.core.capability.CapabilitySnapshot
import com.fieldtap.core.capability.RootProbeResult
import com.fieldtap.core.time.Clock
import com.fieldtap.platform.capability.AndroidCapabilityInspector
import com.fieldtap.platform.capability.CapabilityInspector
import com.fieldtap.platform.telephony.HandsetInfoReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything the Capability screen needs from the app: the passive/active capability inspection and the
 * `fieldtap-capability/1` export. Split behind this interface so [ProbeViewModel] can be driven by a fake
 * in tests, and so nothing platform-bound leaks into the view-model's own logic.
 *
 * [passive] and [checkWithRoot] are the [CapabilityInspector] facade (capability spec §1). [exportCapability]
 * assembles the report from the snapshot and the optional root probe with `CapabilityReports.build`, encodes
 * it with `CapabilityJson`, and writes the JSON to a shareable file — the same conventions the telephony
 * `CapabilityProbe.export` follows. Every call does its work off the main thread.
 *
 * Owner: workstream `screens-setup`.
 */
internal interface CapabilitySource {
    /** Read-only, no su call; safe to run when the screen opens. */
    suspend fun passive(): CapabilitySnapshot

    /**
     * Runs the read-only root check through an existing su on an explicit tap. May raise the superuser grant
     * prompt. Never gains root and never runs an exploit.
     */
    suspend fun checkWithRoot(): RootProbeResult

    /**
     * Builds the `fieldtap-capability/1` report from [snapshot] (folded with [rootProbe] when one ran),
     * writes it as JSON to a shareable file, and returns the file. Throws [IOException] when it cannot be
     * written.
     */
    suspend fun exportCapability(snapshot: CapabilitySnapshot, rootProbe: RootProbeResult?): File
}

/**
 * The platform-backed [CapabilitySource]. Delegates inspection to an [AndroidCapabilityInspector] and writes
 * the export to `<cacheDir>/exports`, the FileProvider root the share sheet serves, through a synced `.tmp`
 * file that is renamed into place so a half-written file is never shared.
 *
 * It reads the handset metadata with [HandsetInfoReader] (already identifier-free) and the app/SDK from
 * [appInfo] and `Build.VERSION.SDK_INT`; the report carries no identifier and no location.
 *
 * Owner: workstream `screens-setup`.
 */
internal class AndroidCapabilitySource(
    context: Context,
    private val appInfo: AppInfo,
    private val clock: Clock,
    private val inspector: CapabilityInspector = AndroidCapabilityInspector(context.applicationContext, appInfo),
) : CapabilitySource {
    private val appContext = context.applicationContext
    private val exportDir = File(appContext.cacheDir, EXPORTS_DIR)
    private val handsetReader = HandsetInfoReader(appContext)

    override suspend fun passive(): CapabilitySnapshot = inspector.passive()

    override suspend fun checkWithRoot(): RootProbeResult = inspector.checkWithRoot()

    override suspend fun exportCapability(snapshot: CapabilitySnapshot, rootProbe: RootProbeResult?): File =
        withContext(Dispatchers.IO) {
            val report = CapabilityReports.build(
                createdUtcMs = clock.wallMillis(),
                appVersion = appInfo.versionName,
                versionCode = appInfo.versionCode,
                sdkInt = Build.VERSION.SDK_INT,
                handset = handsetReader.read(),
                snapshot = snapshot,
                rootProbe = rootProbe,
            )
            write(report)
        }

    private fun write(report: CapabilityReport): File {
        if (!exportDir.isDirectory && !exportDir.mkdirs() && !exportDir.isDirectory) {
            throw IOException("Cannot create the export directory ${exportDir.path}")
        }
        val target = File(exportDir, CapabilityJson.fileName(report))
        val temporary = File(exportDir, target.name + ".tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(CapabilityJson.encode(report).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) throw IOException("Cannot move ${temporary.name} to ${target.name}")
        } catch (e: IOException) {
            temporary.delete()
            throw e
        }
        return target
    }

    private companion object {
        /** Matches DefaultAppGraph.EXPORTS_DIR and the FileProvider `exports` root in res/xml/file_paths.xml. */
        const val EXPORTS_DIR = "exports"
    }
}
