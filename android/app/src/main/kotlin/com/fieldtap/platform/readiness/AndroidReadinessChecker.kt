package com.fieldtap.platform.readiness

import android.content.Context
import com.fieldtap.app.ReadinessChecker
import com.fieldtap.app.SettingsRepository
import com.fieldtap.core.readiness.ReadinessFacts
import com.fieldtap.core.readiness.ReadinessReport
import com.fieldtap.core.time.Clock
import com.fieldtap.platform.device.DeviceStateSource

/**
 * Gathers [ReadinessFacts] from the platform (sources listed in ReadinessFacts' KDoc) and evaluates
 * them with `ReadinessPolicy`; [check] also stores `readinessLastRunUtcMs`.
 *
 * Owner: workstream `platform-adapters`.
 */
class AndroidReadinessChecker(
    private val context: Context,
    private val clock: Clock,
    private val device: DeviceStateSource,
    private val settings: SettingsRepository,
) : ReadinessChecker {
    fun facts(): ReadinessFacts = TODO("platform-adapters")

    override suspend fun check(): ReadinessReport = TODO("platform-adapters")
}
