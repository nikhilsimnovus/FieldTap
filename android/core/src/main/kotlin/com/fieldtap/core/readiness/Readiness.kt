package com.fieldtap.core.readiness

import java.util.Locale

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
    /**
     * The screen where this setting changes, whatever the [level]; screens show the link for
     * [ReadinessLevel.ADVICE] and [ReadinessLevel.BLOCKER] items. [SettingsTarget.NONE] when no screen can fix it.
     */
    val target: SettingsTarget,
)

data class ReadinessReport(
    val checkedUtcMs: Long,
    val manufacturer: String,
    /** One item per [ReadinessCheck], in enum order. */
    val items: List<ReadinessItem>,
) {
    /** No item is a [ReadinessLevel.BLOCKER]. */
    val canStart: Boolean get() = items.none { it.level == ReadinessLevel.BLOCKER }

    /** The items that stop a session from starting, in enum order. */
    val blockers: List<ReadinessItem> get() = items.filter { it.level == ReadinessLevel.BLOCKER }

    /** The items the pre-start sheet names, with their fix and "Start anyway", in enum order. */
    val advice: List<ReadinessItem> get() = items.filter { it.level == ReadinessLevel.ADVICE }

    /**
     * The phone maker is known for killing background apps ([ReadinessPolicy.AGGRESSIVE_OEMS]). Decision 7
     * of android/ARCHITECTURE.md puts this on the pre-start sheet next to [advice], with maker-specific guidance.
     */
    val aggressiveOem: Boolean get() = ReadinessPolicy.isAggressiveOem(manufacturer)

    /** The item for [check], or null when the report does not hold one. */
    fun item(check: ReadinessCheck): ReadinessItem? = items.firstOrNull { it.check == check }
}

/**
 * Readiness rules, pure.
 *
 * [evaluate], one item per check in enum order:
 * - BLOCKER: no precise location; location off.
 * - ADVICE: notifications refused (the notification with Stop and Mark will not show); battery
 *   optimisation on; background restricted; standby bucket rare or restricted; no ready SIM (no data
 *   tests, emergency-camped only); Wi-Fi connected while not charging (forces Android's 10 s interval).
 * - OK otherwise. The phone permission is always OK, with a sentence saying whether "Instant cell
 *   updates" are on: decisions 7 and 9 of android/ARCHITECTURE.md make it an opt-in from Settings that
 *   the app works fully without, so it never appears on the pre-start sheet. This overrides the design
 *   stage's "ADVICE for phone permission refused".
 *
 * [requiredBeforeSession]: true when the check has never run; on OnePlus, OPPO and realme
 * ([AGGRESSIVE_OEMS], case-insensitive, surrounding white space ignored) also when it has not run since
 * the last session started. A check at the same millisecond as the start counts as run since.
 *
 * Tests: every check at each level; OEM matching; required-before-session truth table.
 *
 * Owner: workstream `platform-adapters`.
 */
object ReadinessPolicy {
    val AGGRESSIVE_OEMS: Set<String> = setOf("oneplus", "oppo", "realme")

    /** `UsageStatsManager.STANDBY_BUCKET_RARE`: from here on, background work is limited enough to advise. */
    const val STANDBY_BUCKET_RARE: Int = 40

    /** `UsageStatsManager.STANDBY_BUCKET_RESTRICTED`. */
    const val STANDBY_BUCKET_RESTRICTED: Int = 45

    fun isAggressiveOem(manufacturer: String): Boolean =
        manufacturer.trim().lowercase(Locale.ROOT) in AGGRESSIVE_OEMS

    fun evaluate(facts: ReadinessFacts, nowUtcMs: Long): ReadinessReport = ReadinessReport(
        checkedUtcMs = nowUtcMs,
        manufacturer = facts.manufacturer,
        items = ReadinessCheck.entries.map { check -> itemFor(check, facts) },
    )

    fun requiredBeforeSession(manufacturer: String, lastRunUtcMs: Long?, lastSessionStartedUtcMs: Long?): Boolean =
        when {
            lastRunUtcMs == null -> true
            !isAggressiveOem(manufacturer) -> false
            lastSessionStartedUtcMs == null -> false
            else -> lastRunUtcMs < lastSessionStartedUtcMs
        }

    private fun itemFor(check: ReadinessCheck, facts: ReadinessFacts): ReadinessItem = when (check) {
        ReadinessCheck.PRECISE_LOCATION -> if (facts.preciseLocationGranted) {
            ok(check, "Precise location is allowed.", SettingsTarget.APP_DETAILS)
        } else {
            blocker(
                check,
                "Precise location is not allowed, and Android gives apps no cell information without it.",
                SettingsTarget.APP_DETAILS,
            )
        }

        ReadinessCheck.LOCATION_ENABLED -> if (facts.locationEnabled) {
            ok(check, "Location is on.", SettingsTarget.LOCATION_SOURCE)
        } else {
            blocker(
                check,
                "Location is off, so Android returns no cell information and no GPS fixes.",
                SettingsTarget.LOCATION_SOURCE,
            )
        }

        ReadinessCheck.NOTIFICATIONS -> if (facts.notificationsGranted) {
            ok(
                check,
                "Notifications are allowed, so the session notification shows Stop and Mark.",
                SettingsTarget.APP_NOTIFICATIONS,
            )
        } else {
            advice(
                check,
                "Notifications are off, so the session notification with Stop and Mark will not show, " +
                    "although logging still works.",
                SettingsTarget.APP_NOTIFICATIONS,
            )
        }

        ReadinessCheck.PHONE_PERMISSION -> if (facts.phonePermissionGranted) {
            ok(
                check,
                "Instant cell updates are on, so Android also pushes cell changes as they happen.",
                SettingsTarget.APP_DETAILS,
            )
        } else {
            ok(
                check,
                "Instant cell updates are off, which the app works fully without; turn them on in Settings " +
                    "for Android's push updates.",
                SettingsTarget.APP_DETAILS,
            )
        }

        ReadinessCheck.BATTERY_OPTIMISATION -> if (facts.ignoringBatteryOptimisations) {
            ok(check, "Battery optimisation is off for FieldTap.", SettingsTarget.BATTERY_OPTIMISATION)
        } else {
            advice(
                check,
                "Battery optimisation is on for FieldTap, so Android may stop logging while the screen is off.",
                SettingsTarget.BATTERY_OPTIMISATION,
            )
        }

        ReadinessCheck.BACKGROUND_RESTRICTION -> if (facts.backgroundRestricted) {
            advice(
                check,
                "Background use is restricted for FieldTap, so Android may stop a session after you leave the app.",
                SettingsTarget.APP_DETAILS,
            )
        } else {
            ok(check, "Background use is not restricted for FieldTap.", SettingsTarget.APP_DETAILS)
        }

        ReadinessCheck.STANDBY_BUCKET -> standbyBucketItem(facts.standbyBucket)

        ReadinessCheck.SIM_PRESENT -> if (facts.simReady) {
            ok(check, "A SIM is ready.", SettingsTarget.NONE)
        } else {
            advice(
                check,
                "No SIM is ready, so the phone can only camp on a cell for emergency calls, and ping and " +
                    "download tests cannot run.",
                SettingsTarget.NONE,
            )
        }

        ReadinessCheck.WIFI_OFF -> when {
            !facts.wifiConnected -> ok(
                check,
                "Wi-Fi is not connected, so Android can refresh cell information every 2 s while the screen is on.",
                SettingsTarget.WIFI,
            )

            facts.charging -> ok(
                check,
                "Wi-Fi is connected, but charging keeps Android's 2 s cell-information refresh while the " +
                    "screen is on.",
                SettingsTarget.WIFI,
            )

            else -> advice(
                check,
                "Wi-Fi is connected and the phone is not charging, so Android refreshes cell information only " +
                    "every 10 s; turn Wi-Fi off for 2 s updates, because a charger helps only once Android counts the " +
                    "phone as charging, up to 15 minutes after plugging in.",
                SettingsTarget.WIFI,
            )
        }
    }

    private fun standbyBucketItem(bucket: Int?): ReadinessItem {
        val check = ReadinessCheck.STANDBY_BUCKET
        return when {
            bucket == null -> ok(check, "Android did not report FieldTap's standby bucket.", SettingsTarget.APP_DETAILS)

            bucket >= STANDBY_BUCKET_RESTRICTED -> advice(
                check,
                "Android has put FieldTap in the restricted standby bucket, which limits background work until " +
                    "you open the app again.",
                SettingsTarget.APP_DETAILS,
            )

            bucket >= STANDBY_BUCKET_RARE -> advice(
                check,
                "Android has put FieldTap in the rare standby bucket, which delays background work until you " +
                    "open the app more often.",
                SettingsTarget.APP_DETAILS,
            )

            else -> ok(check, bucketSentence(bucket), SettingsTarget.APP_DETAILS)
        }
    }

    private fun bucketSentence(bucket: Int): String = when (bucket) {
        5 -> "FieldTap is in Android's exempt standby bucket."
        10 -> "FieldTap is in Android's active standby bucket."
        20 -> "FieldTap is in Android's working set standby bucket."
        30 -> "FieldTap is in Android's frequent standby bucket."
        else -> "FieldTap is in Android's standby bucket $bucket."
    }

    private fun ok(check: ReadinessCheck, detail: String, target: SettingsTarget) =
        ReadinessItem(check, ReadinessLevel.OK, detail, target)

    private fun advice(check: ReadinessCheck, detail: String, target: SettingsTarget) =
        ReadinessItem(check, ReadinessLevel.ADVICE, detail, target)

    private fun blocker(check: ReadinessCheck, detail: String, target: SettingsTarget) =
        ReadinessItem(check, ReadinessLevel.BLOCKER, detail, target)
}
