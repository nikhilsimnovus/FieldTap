@file:OptIn(ExperimentalCoroutinesApi::class)

package com.fieldtap.ui.setup

import com.fieldtap.app.AppGraph
import com.fieldtap.app.AppInfo
import com.fieldtap.app.CapabilityProbe
import com.fieldtap.app.LiveFeed
import com.fieldtap.app.ReadinessChecker
import com.fieldtap.app.RecoveryNotices
import com.fieldtap.app.SessionControl
import com.fieldtap.app.SessionDetail
import com.fieldtap.app.SessionRepository
import com.fieldtap.app.SessionStatus
import com.fieldtap.app.SessionSummary
import com.fieldtap.app.SettingsRepository
import com.fieldtap.app.SoakControl
import com.fieldtap.app.SoakState
import com.fieldtap.app.StartResult
import com.fieldtap.core.export.ExportResult
import com.fieldtap.core.input.FixSample
import com.fieldtap.core.live.LiveState
import com.fieldtap.core.privacy.ConsentRecord
import com.fieldtap.core.privacy.PrivacyZone
import com.fieldtap.core.probe.ProbeRecorder
import com.fieldtap.core.probe.ProbeReport
import com.fieldtap.core.readiness.ReadinessFacts
import com.fieldtap.core.readiness.ReadinessPolicy
import com.fieldtap.core.readiness.ReadinessReport
import com.fieldtap.core.session.RecorderSnapshot
import com.fieldtap.core.session.SessionOutcome
import com.fieldtap.core.session.StartRequest
import com.fieldtap.core.session.StorageStatus
import com.fieldtap.core.settings.AppSettings
import com.fieldtap.core.time.ManualClock
import com.fieldtap.format.FixProvider
import com.fieldtap.format.HandsetMeta
import com.fieldtap.format.LocationPrecision
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/*
 * Test doubles for the setup screens' view models: a fake AppGraph whose facades record what they were asked and can
 * be told to fail or wait. Everything runs on the test scheduler through Dispatchers.Main.
 */

/** Makes `Dispatchers.Main` (and so `viewModelScope`) a test dispatcher; `runTest` then shares its scheduler. */
class SetupMainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

class FakeSettingsRepository(initial: AppSettings) : SettingsRepository {
    val stored = MutableStateFlow(initial)

    /** Thrown by the next and every later [update] while set. */
    var failure: Exception? = null

    /** When set, [update] waits for it before writing. */
    var gate: CompletableDeferred<Unit>? = null

    /** Thrown to collectors of [settings] while set, as an unreadable store would. */
    var readFailure: Exception? = null

    /** Writes that reached the store. */
    var updates: Int = 0
        private set

    override val settings: Flow<AppSettings>
        get() = flow {
            readFailure?.let { throw it }
            emitAll(stored)
        }

    override suspend fun current(): AppSettings = stored.value

    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        gate?.await()
        failure?.let { throw it }
        updates++
        val next = transform(stored.value)
        stored.value = next
        return next
    }
}

class FakeSessionControl : SessionControl {
    override val status = MutableStateFlow<SessionStatus>(SessionStatus.Idle)
    override val lastOutcome = MutableStateFlow<SessionOutcome?>(null)

    override suspend fun start(request: StartRequest): StartResult =
        throw UnsupportedOperationException("The setup screens never start a session")

    override fun mark(note: String?): Boolean = false

    override fun stop() = Unit
}

class FakeLiveFeed : LiveFeed {
    override val state = MutableStateFlow(LiveState())
}

class FakeReadinessChecker(var nextReport: () -> ReadinessReport) : ReadinessChecker {
    var calls: Int = 0
        private set

    /** When set, every check waits for it. */
    var gate: CompletableDeferred<Unit>? = null

    /** Thrown by checks while set. */
    var failure: Exception? = null

    override suspend fun check(): ReadinessReport {
        calls++
        gate?.await()
        failure?.let { throw it }
        return nextReport()
    }
}

class FakeCapabilityProbe : CapabilityProbe {
    val durations = mutableListOf<Long>()
    val exported = mutableListOf<ProbeReport>()

    /** Sent to onProgress, in order, as soon as a run starts. */
    var progressLines: List<String> = emptyList()

    /** What the next run returns once completed; replace it before starting another run. */
    var result: CompletableDeferred<ProbeReport> = CompletableDeferred()
    var runFailure: Exception? = null
    var exportFile: File = File("probe-Google-Pixel-9-20260910-143000.json")
    var exportFailure: Exception? = null

    override suspend fun run(durationMs: Long, onProgress: (String) -> Unit): ProbeReport {
        durations += durationMs
        val pending = result
        progressLines.forEach(onProgress)
        runFailure?.let { throw it }
        return pending.await()
    }

    override suspend fun export(report: ProbeReport): File {
        exportFailure?.let { throw it }
        exported += report
        return exportFile
    }
}

class FakeSoakControl : SoakControl {
    override val state = MutableStateFlow<SoakState>(SoakState.Idle)

    /** Behave like Android refusing the foreground service: stay Idle. */
    var refuse: Boolean = false
    var starts: Int = 0
        private set
    var cancels: Int = 0
        private set

    override fun start(durationMs: Long) {
        starts++
        if (!refuse) state.value = SoakState.Running(elapsedMs = 0, durationMs = durationMs)
    }

    override fun cancel() {
        cancels++
        if (state.value is SoakState.Running) state.value = SoakState.Idle
    }
}

class FakeRecoveryNotices : RecoveryNotices {
    override val closed = MutableStateFlow<List<SessionOutcome>>(emptyList())

    override fun acknowledge(dirName: String) = Unit
}

/** The setup screens never read sessions; any call is a test failure. */
class UnusedSessionRepository : SessionRepository {
    override suspend fun list(): List<SessionSummary> = unused()

    override suspend fun detail(dirName: String): SessionDetail? = unused()

    override suspend fun delete(dirName: String): Boolean = unused()

    override suspend fun export(dirName: String, precision: LocationPrecision): ExportResult = unused()

    override suspend fun storage(): StorageStatus = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("The setup screens do not use sessions")
}

class FakeAppGraph(initialSettings: AppSettings = SetupSamples.settings()) : AppGraph {
    override val clock = ManualClock()
    override val appInfo = AppInfo(versionName = "0.1.0", versionCode = 1, applicationId = "com.fieldtap", debuggable = true)
    val fakeSettings = FakeSettingsRepository(initialSettings)
    val fakeSessionControl = FakeSessionControl()
    val fakeLive = FakeLiveFeed()
    val fakeReadiness = FakeReadinessChecker { SetupSamples.readinessReport() }
    val fakeProbe = FakeCapabilityProbe()
    val fakeSoak = FakeSoakControl()

    override val settings: SettingsRepository get() = fakeSettings
    override val sessions: SessionRepository = UnusedSessionRepository()
    override val sessionControl: SessionControl get() = fakeSessionControl
    override val live: LiveFeed get() = fakeLive
    override val readiness: ReadinessChecker get() = fakeReadiness
    override val probe: CapabilityProbe get() = fakeProbe
    override val soak: SoakControl get() = fakeSoak
    override val recovery: RecoveryNotices = FakeRecoveryNotices()
}

object SetupSamples {
    /** 2026-09-10T14:30:00.000Z. */
    const val NOW_UTC_MS: Long = 1_789_050_600_000L

    fun settings(consent: ConsentRecord? = null, zones: List<PrivacyZone> = emptyList()): AppSettings =
        AppSettings(installId = "test-install", consent = consent, zones = zones)

    fun facts(
        manufacturer: String = "Google",
        precise: Boolean = true,
        locationEnabled: Boolean = true,
        notifications: Boolean = true,
        ignoringBatteryOptimisations: Boolean = true,
        simReady: Boolean = true,
        wifiConnected: Boolean = false,
    ): ReadinessFacts = ReadinessFacts(
        manufacturer = manufacturer,
        preciseLocationGranted = precise,
        locationEnabled = locationEnabled,
        notificationsGranted = notifications,
        phonePermissionGranted = false,
        ignoringBatteryOptimisations = ignoringBatteryOptimisations,
        backgroundRestricted = false,
        standbyBucket = 10,
        simReady = simReady,
        wifiConnected = wifiConnected,
        charging = false,
    )

    fun readinessReport(facts: ReadinessFacts = facts(), nowUtcMs: Long = NOW_UTC_MS): ReadinessReport =
        ReadinessPolicy.evaluate(facts, nowUtcMs)

    fun probeReport(model: String = "Pixel 9", createdUtcMs: Long = NOW_UTC_MS): ProbeReport = ProbeRecorder().report(
        createdUtcMs = createdUtcMs,
        durationMs = 30_000,
        appVersion = "0.1.0",
        versionCode = 1,
        sdkInt = 36,
        handset = HandsetMeta(manufacturer = "Google", model = model, androidVersion = "16"),
        permissions = linkedMapOf("android.permission.ACCESS_FINE_LOCATION" to true),
    )

    fun fix(elapsedMs: Long, lat: Double = 52.520008, lon: Double = 13.404954, accuracyM: Double? = 8.0): FixSample = FixSample(
        elapsedMs = elapsedMs,
        wallMs = NOW_UTC_MS,
        lat = lat,
        lon = lon,
        accuracyM = accuracyM,
        altitudeM = null,
        speedMps = null,
        provider = FixProvider.GPS,
        mock = false,
        observedWallMs = NOW_UTC_MS,
        observedElapsedMs = elapsedMs,
    )

    fun recording(): SessionStatus = SessionStatus.Recording(
        RecorderSnapshot(
            dirName = "20260910-143000_walk",
            startedUtcMs = NOW_UTC_MS,
            elapsedMs = 60_000,
            servingRat = null,
            servingRsrpDbm = null,
            newestSampleAgeMs = null,
            paused = false,
            freshSamples = 30,
            repeatsDropped = 0,
            eventsWritten = 1,
            trackRows = 60,
            hasRecentFix = true,
            stopping = false,
        ),
    )
}
