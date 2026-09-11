package com.fieldtap.app

import android.app.Application
import com.fieldtap.core.time.Clock

/**
 * The manual dependency graph (no DI framework). Builds, once each: AndroidClock, AppInfoReader,
 * DataStoreSettingsRepository, SessionPaths (`<externalFilesDir>/sessions`, `<filesDir>/session-state`),
 * SessionStore, SessionExporter, FileSessionRepository, DeviceStateSource, TelephonySource,
 * LocationSource, MeasurementHub, HubLiveFeed, SessionRuntime + ServiceSessionControl,
 * AndroidReadinessChecker, CapabilityProbeRunner, ServiceSoakControl, LaunchRecovery.
 * Its scope is `SupervisorJob() + Dispatchers.Default` for the process lifetime.
 *
 * Constructing it must not touch disk or platform services; each member is built on first use.
 *
 * Owner: workstream `service-and-tests`.
 */
class DefaultAppGraph(private val application: Application) : AppGraph {
    override val clock: Clock get() = TODO("service-and-tests")
    override val appInfo: AppInfo get() = TODO("service-and-tests")
    override val settings: SettingsRepository get() = TODO("service-and-tests")
    override val sessions: SessionRepository get() = TODO("service-and-tests")
    override val sessionControl: SessionControl get() = TODO("service-and-tests")
    override val live: LiveFeed get() = TODO("service-and-tests")
    override val readiness: ReadinessChecker get() = TODO("service-and-tests")
    override val probe: CapabilityProbe get() = TODO("service-and-tests")
    override val soak: SoakControl get() = TODO("service-and-tests")
    override val recovery: RecoveryNotices get() = TODO("service-and-tests")
}
