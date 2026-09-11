package com.fieldtap.platform.readiness

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import com.fieldtap.app.ReadinessChecker
import com.fieldtap.app.SettingsRepository
import com.fieldtap.core.readiness.ReadinessFacts
import com.fieldtap.core.readiness.ReadinessPolicy
import com.fieldtap.core.readiness.ReadinessReport
import com.fieldtap.core.time.Clock
import com.fieldtap.platform.Permissions
import com.fieldtap.platform.device.DeviceStateSource
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gathers [ReadinessFacts] from the platform (sources listed in ReadinessFacts' KDoc) and evaluates
 * them with `ReadinessPolicy`; [check] also stores `readinessLastRunUtcMs`.
 *
 * - [facts] makes binder calls; [check] runs it on `Dispatchers.IO`. A fact Android fails to report is
 *   logged and read as the value that shows advice (not ignoring battery optimisation, no ready SIM) or,
 *   for the two blockers, as not granted and off; an unreadable standby bucket is null.
 * - SIM: ready when any active modem slot reports `SIM_STATE_READY`, so a dual-SIM phone with its SIM in
 *   the second slot counts.
 * - A failure to store the run time is logged and the report is still returned; the check will simply be
 *   due again.
 *
 * Owner: workstream `platform-adapters`.
 */
class AndroidReadinessChecker(
    private val context: Context,
    private val clock: Clock,
    private val device: DeviceStateSource,
    private val settings: SettingsRepository,
) : ReadinessChecker {
    fun facts(): ReadinessFacts {
        val conditions = device.current()
        return ReadinessFacts(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            preciseLocationGranted = read(false) { Permissions.preciseLocationGranted(context) },
            locationEnabled = read(false) { Permissions.locationEnabled(context) },
            notificationsGranted = read(false) { Permissions.notificationsGranted(context) },
            phonePermissionGranted = read(false) { Permissions.phoneGranted(context) },
            ignoringBatteryOptimisations = read(false) {
                context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
            },
            backgroundRestricted = read(false) {
                context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
            },
            standbyBucket = read(null) { context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket },
            simReady = read(false) { anySimReady() },
            wifiConnected = conditions.wifiConnected,
            charging = conditions.charging,
        )
    }

    override suspend fun check(): ReadinessReport = withContext(Dispatchers.IO) {
        val report = ReadinessPolicy.evaluate(facts(), clock.wallMillis())
        try {
            settings.update { it.copy(readinessLastRunUtcMs = report.checkedUtcMs) }
        } catch (e: IOException) {
            Log.w(TAG, "The readiness check ran, but its time could not be saved", e)
        }
        report
    }

    private fun anySimReady(): Boolean {
        val manager = context.getSystemService(TelephonyManager::class.java) ?: return false
        val slots = manager.activeModemCount.coerceAtLeast(1)
        return (0 until slots).any { slot -> manager.getSimState(slot) == TelephonyManager.SIM_STATE_READY }
    }

    private inline fun <T> read(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        Log.w(TAG, "A readiness fact could not be read", e)
        fallback
    }

    private companion object {
        const val TAG = "FieldTapReadiness"
    }
}
