package com.fieldtap.app

import android.app.Application
import android.os.Process
import android.util.Log
import com.fieldtap.core.export.ExportResult
import com.fieldtap.core.export.SessionExporter
import com.fieldtap.core.input.MeasurementInput
import com.fieldtap.core.probe.ProbeReport
import com.fieldtap.core.session.SessionPaths
import com.fieldtap.core.session.SessionRecovery
import com.fieldtap.core.session.SessionStore
import com.fieldtap.core.session.StoragePolicy
import com.fieldtap.core.session.StorageStatus
import com.fieldtap.core.time.Clock
import com.fieldtap.data.FileSessionRepository
import com.fieldtap.format.LocationPrecision
import com.fieldtap.nettest.CellularNetworks
import com.fieldtap.nettest.CellularTestTransport
import com.fieldtap.platform.AppInfoReader
import com.fieldtap.platform.capability.AndroidCapabilityInspector
import com.fieldtap.platform.capability.CapabilityInspector
import com.fieldtap.platform.clock.AndroidClock
import com.fieldtap.platform.device.DeviceStateSource
import com.fieldtap.platform.exit.ExitReasonReader
import com.fieldtap.platform.location.LocationSource
import com.fieldtap.platform.probe.CapabilityProbeRunner
import com.fieldtap.platform.readiness.AndroidReadinessChecker
import com.fieldtap.platform.settings.DataStoreSettingsRepository
import com.fieldtap.platform.telephony.HandsetInfoReader
import com.fieldtap.platform.telephony.TelephonySource
import com.fieldtap.recovery.LaunchRecovery
import com.fieldtap.service.AndroidSessionPlatform
import com.fieldtap.service.DefaultSessionFactory
import com.fieldtap.service.ServiceSessionControl
import com.fieldtap.service.ServiceSoakControl
import com.fieldtap.service.SessionRuntime
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The manual dependency graph (no DI framework). Builds, once each: AndroidClock, AppInfoReader,
 * DataStoreSettingsRepository, SessionPaths (`<externalFilesDir>/sessions`, `<filesDir>/session-state`),
 * SessionStore, SessionExporter, FileSessionRepository, DeviceStateSource, TelephonySource,
 * LocationSource, MeasurementHub, HubLiveFeed, SessionRuntime + ServiceSessionControl,
 * AndroidReadinessChecker, CapabilityProbeRunner, ServiceSoakControl, LaunchRecovery.
 * Its scope is `SupervisorJob() + Dispatchers.Default` for the process lifetime, with a handler that logs
 * an uncaught background failure instead of killing the process (and with it a running session).
 *
 * Constructing it must not touch disk or platform services; each member is built on first use. Members
 * whose construction reads the disk (the session paths, the repository, the probe's export directory) are
 * built inside an IO dispatcher by the facades that use them, so a view model on the main thread never
 * triggers disk access.
 *
 * The device state source runs exactly while the telephony source does, because the telephony adapter reads
 * it at every callback.
 *
 * Owner: workstream `service-and-tests`.
 */
class DefaultAppGraph(private val application: Application) : AppGraph {
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, error -> Log.e(TAG, "A background task failed", error) },
    )

    private val storagePolicy = StoragePolicy()

    override val clock: Clock = AndroidClock

    override val appInfo: AppInfo by lazy { AppInfoReader.read(application) }

    override val settings: SettingsRepository by lazy { DataStoreSettingsRepository(application) }

    private val paths: SessionPaths by lazy {
        val external = application.getExternalFilesDir(null)
        if (external == null) {
            Log.w(TAG, "App-specific external storage is unavailable; sessions are kept in internal storage")
        }
        SessionPaths(
            root = File(external ?: application.filesDir, SESSIONS_DIR),
            stateDir = File(application.filesDir, STATE_DIR),
        )
    }

    private val store: SessionStore by lazy { SessionStore(paths, clock) }

    private val exportDir: File by lazy { File(application.cacheDir, EXPORTS_DIR) }

    private val repository: FileSessionRepository by lazy {
        FileSessionRepository(
            paths = paths,
            store = store,
            exporter = SessionExporter(),
            storagePolicy = storagePolicy,
            exportDir = exportDir,
            closeStoppedSessions = { sessionRecovery.closeStoppedSessions(activeDirName()) },
            activeDirName = { activeDirName() },
        )
    }

    override val sessions: SessionRepository = object : SessionRepository {
        override suspend fun list(): List<SessionSummary> = repositoryOnIo().list()

        override suspend fun detail(dirName: String): SessionDetail? = repositoryOnIo().detail(dirName)

        override suspend fun delete(dirName: String): Boolean = repositoryOnIo().delete(dirName)

        override suspend fun export(dirName: String, precision: LocationPrecision): ExportResult =
            repositoryOnIo().export(dirName, precision)

        override suspend fun storage(): StorageStatus = repositoryOnIo().storage()
    }

    private val device: DeviceStateSource by lazy { DeviceStateSource(application) }

    private val telephony: TelephonySource by lazy { TelephonySource(application, clock, { device.current() }) }

    private val location: LocationSource by lazy { LocationSource(application, clock) }

    private val hub: MeasurementHub by lazy {
        MeasurementHub(
            scope = scope,
            radioSource = radioSource(),
            locationSource = flow { emitAll(location.inputs()) },
            clock = clock,
            onSourceError = { error -> Log.w(TAG, "A measurement source failed; restarting it", error) },
        )
    }

    override val live: LiveFeed by lazy { HubLiveFeed(scope, hub, clock) }

    private val transport: CellularTestTransport by lazy { CellularTestTransport(CellularNetworks(application), clock) }

    /** The session lifecycle owner, shared with [com.fieldtap.service.SessionService]. */
    internal val runtime: SessionRuntime by lazy {
        SessionRuntime(
            scope = scope,
            clock = clock,
            platform = AndroidSessionPlatform(application),
            settings = settings,
            storage = { repositoryOnIo().storage() },
            sessions = DefaultSessionFactory(
                paths = { paths },
                store = { store },
                settings = settings,
                clock = clock,
                appInfo = { appInfo },
                // getDataNetworkType() needs the Phone permission; without it, the newest display info's
                // network type stands in. Called by prepare(), off the main thread.
                handset = {
                    HandsetInfoReader(application).read(
                        fallbackNetworkType = hub.currentDisplayInfo()?.networkType ?: live.state.value.display?.networkType,
                    )
                },
                pid = { Process.myPid() },
                storagePolicy = storagePolicy,
            ),
            // A session starts from the Live screen, which already holds the sources: it must still learn the
            // service, data and display state Android delivered when they registered.
            inputs = hub.inputsWithCurrentState,
            radioInputs = hub.radioInputs,
            transport = transport,
            recoveryGate = { launchRecovery.run() },
        )
    }

    override val sessionControl: SessionControl by lazy { ServiceSessionControl(runtime) }

    override val readiness: ReadinessChecker by lazy { AndroidReadinessChecker(application, clock, device, settings) }

    private val probeRunner: CapabilityProbeRunner by lazy {
        CapabilityProbeRunner(application, clock, telephony, HandsetInfoReader(application), appInfo, exportDir)
    }

    override val probe: CapabilityProbe = object : CapabilityProbe {
        override suspend fun run(durationMs: Long, onProgress: (String) -> Unit): ProbeReport =
            withContext(Dispatchers.IO) { probeRunner }.run(durationMs, onProgress)

        override suspend fun export(report: ProbeReport): File =
            withContext(Dispatchers.IO) { probeRunner }.export(report)
    }

    override val capability: CapabilityInspector by lazy { AndroidCapabilityInspector(application, appInfo) }

    override val soak: SoakControl by lazy { ServiceSoakControl(runtime) }

    private val sessionRecovery: SessionRecovery by lazy { SessionRecovery(store, paths) }

    private val launchRecovery: LaunchRecovery by lazy {
        LaunchRecovery(
            findOpen = { sessionRecovery.findOpen() },
            exitRecords = { ExitReasonReader(application).recent() },
            close = { action -> sessionRecovery.close(action) },
            activeDirName = { activeDirName() },
        )
    }

    override val recovery: RecoveryNotices get() = launchRecovery

    private val recoveryJob: Job by lazy { scope.launch { launchRecovery.run() } }

    /** Starts launch recovery in the background, once. Called from `FieldTapApplication.onCreate`. */
    internal fun startLaunchRecovery() {
        recoveryJob.start()
    }

    private fun activeDirName(): String? = runtime.activeDirName()

    private suspend fun repositoryOnIo(): FileSessionRepository = withContext(Dispatchers.IO) { repository }

    private fun radioSource(): Flow<MeasurementInput> = flow {
        try {
            device.start()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start the device state source", e)
        }
        try {
            emitAll(telephony.inputs())
        } finally {
            try {
                device.stop()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not stop the device state source", e)
            }
        }
    }

    private companion object {
        const val TAG = "FieldTapGraph"
        const val SESSIONS_DIR = "sessions"
        const val STATE_DIR = "session-state"
        const val EXPORTS_DIR = "exports"
    }
}
