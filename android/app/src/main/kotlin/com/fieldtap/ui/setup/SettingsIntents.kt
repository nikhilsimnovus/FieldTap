package com.fieldtap.ui.setup

import android.content.Context
import android.content.Intent
import com.fieldtap.core.readiness.SettingsTarget

/**
 * [SettingsTarget] -> the system settings Intent: APP_DETAILS `ACTION_APPLICATION_DETAILS_SETTINGS`
 * with `package:`; LOCATION_SOURCE `ACTION_LOCATION_SOURCE_SETTINGS`; APP_NOTIFICATIONS
 * `ACTION_APP_NOTIFICATION_SETTINGS`; BATTERY_OPTIMISATION `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
 * (the list screen, never the request dialog, whose permission is not declared); WIFI
 * `Settings.Panel.ACTION_WIFI`; NONE null. Falls back to APP_DETAILS when an intent does not resolve.
 *
 * Owner: workstream `ui-setup`.
 */
object SettingsIntents {
    fun intentFor(context: Context, target: SettingsTarget): Intent? = TODO("ui-setup")
}
