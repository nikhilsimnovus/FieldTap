package com.fieldtap.core.readiness

/** One readiness check (docs/APP-PLAN.md, "Readiness check"). */
enum class ReadinessCheck {
    PRECISE_LOCATION,
    LOCATION_ENABLED,
    NOTIFICATIONS,
    PHONE_PERMISSION,
    BATTERY_OPTIMISATION,
    BACKGROUND_RESTRICTION,
    STANDBY_BUCKET,
    SIM_PRESENT,
    WIFI_OFF,
}

enum class ReadinessLevel {
    OK,

    /** Logging works, but worse: explain and link to the setting. */
    ADVICE,

    /** No session can start until fixed. */
    BLOCKER,
}

/** The settings screen a check links to. :app maps it to an Intent (com.fieldtap.ui.setup.SettingsIntents). */
enum class SettingsTarget { APP_DETAILS, LOCATION_SOURCE, APP_NOTIFICATIONS, BATTERY_OPTIMISATION, WIFI, NONE }

/**
 * Raw facts read by com.fieldtap.platform.readiness.AndroidReadinessChecker. No identifier: the SIM is
 * "ready or not", never its number or ICCID.
 */
data class ReadinessFacts(
    /** `Build.MANUFACTURER`. */
    val manufacturer: String,
    val preciseLocationGranted: Boolean,
    val locationEnabled: Boolean,
    val notificationsGranted: Boolean,
    val phonePermissionGranted: Boolean,
    /** `PowerManager.isIgnoringBatteryOptimizations(packageName)`. */
    val ignoringBatteryOptimisations: Boolean,
    /** `ActivityManager.isBackgroundRestricted()`. */
    val backgroundRestricted: Boolean,
    /** `UsageStatsManager.getAppStandbyBucket()`: 5 exempt, 10 active, 20 working set, 30 frequent, 40 rare, 45 restricted. */
    val standbyBucket: Int?,
    /** `TelephonyManager.getSimState() == SIM_STATE_READY`. */
    val simReady: Boolean,
    val wifiConnected: Boolean,
    val charging: Boolean,
)

data class ReadinessItem(
    val check: ReadinessCheck,
    val level: ReadinessLevel,
    /** One plain sentence for the screen. */
    val detail: String,
    val target: SettingsTarget,
)

data class ReadinessReport(
    val checkedUtcMs: Long,
    val manufacturer: String,
    /** One item per [ReadinessCheck], in enum order. */
    val items: List<ReadinessItem>,
) {
    /** No item is a [ReadinessLevel.BLOCKER]. */
    val canStart: Boolean get() = TODO("platform-adapters")
}

/**
 * Readiness rules, pure.
 *
 * [evaluate]: BLOCKER for no precise location and location off. ADVICE for notifications refused (the
 * notification with Stop and Mark will not show), phone permission refused (no push listener),
 * battery optimisation on, background restricted, standby bucket rare or restricted, no ready SIM
 * (no data tests, emergency-camped only), Wi-Fi connected while not charging (forces Android's 10 s
 * interval). OK otherwise.
 *
 * [requiredBeforeSession]: true when the check has never run; on OnePlus, OPPO and realme
 * ([AGGRESSIVE_OEMS], case-insensitive) also when it has not run since the last session started.
 *
 * Tests: every check at each level; OEM matching; required-before-session truth table.
 *
 * Owner: workstream `platform-adapters`.
 */
object ReadinessPolicy {
    val AGGRESSIVE_OEMS: Set<String> = setOf("oneplus", "oppo", "realme")

    fun evaluate(facts: ReadinessFacts, nowUtcMs: Long): ReadinessReport = TODO("platform-adapters")

    fun requiredBeforeSession(manufacturer: String, lastRunUtcMs: Long?, lastSessionStartedUtcMs: Long?): Boolean =
        TODO("platform-adapters")
}
