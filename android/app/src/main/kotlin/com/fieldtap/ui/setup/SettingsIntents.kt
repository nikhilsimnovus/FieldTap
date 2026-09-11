package com.fieldtap.ui.setup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.fieldtap.core.readiness.SettingsTarget

/**
 * [SettingsTarget] -> the system settings Intent: APP_DETAILS `ACTION_APPLICATION_DETAILS_SETTINGS`
 * with `package:`; LOCATION_SOURCE `ACTION_LOCATION_SOURCE_SETTINGS`; APP_NOTIFICATIONS
 * `ACTION_APP_NOTIFICATION_SETTINGS`; BATTERY_OPTIMISATION `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
 * (the list screen, never the request dialog, whose permission is not declared); WIFI
 * `Settings.Panel.ACTION_WIFI`; NONE nothing. Falls back to APP_DETAILS when a screen cannot be started.
 *
 * Each target has an ordered list of candidate screens ([candidates]); the last is always the app's details
 * screen. Wi-Fi tries the full Wi-Fi settings list between the panel and the app's details, because the app's
 * details say nothing about Wi-Fi. Candidates are started in turn rather than resolved first, so a maker's
 * Settings app that is not visible to FieldTap (the manifest declares no `<queries>`) still opens. Readiness
 * links open system settings only: the app never asks for a battery-optimisation exemption.
 *
 * Owner: workstream `ui-setup`.
 */
object SettingsIntents {
    private val APP_DETAILS = SettingsIntentSpec(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri = true)

    /**
     * Opens [target]'s settings screen, trying each candidate in turn until Android starts one. Returns false for
     * [SettingsTarget.NONE], or when no candidate could be started, so the screen can say so. Never throws for a
     * missing or unexported settings activity.
     */
    fun open(context: Context, target: SettingsTarget): Boolean {
        val newTask = context.findActivity() == null
        for (spec in candidates(target)) {
            val intent = spec.toIntent(context.packageName)
            if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                // This phone has no such screen: try the next candidate.
            } catch (e: SecurityException) {
                // A maker's settings activity that is not exported to apps: try the next candidate.
            }
        }
        return false
    }

    /** The screens tried for [target], most specific first; empty for [SettingsTarget.NONE]. Pure, for tests. */
    internal fun candidates(target: SettingsTarget): List<SettingsIntentSpec> = when (target) {
        SettingsTarget.APP_DETAILS -> listOf(APP_DETAILS)
        SettingsTarget.LOCATION_SOURCE -> listOf(SettingsIntentSpec(Settings.ACTION_LOCATION_SOURCE_SETTINGS), APP_DETAILS)
        SettingsTarget.APP_NOTIFICATIONS -> listOf(
            SettingsIntentSpec(Settings.ACTION_APP_NOTIFICATION_SETTINGS, appPackageExtra = true),
            APP_DETAILS,
        )
        SettingsTarget.BATTERY_OPTIMISATION -> listOf(
            SettingsIntentSpec(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            APP_DETAILS,
        )
        SettingsTarget.WIFI -> listOf(
            SettingsIntentSpec(Settings.Panel.ACTION_WIFI),
            SettingsIntentSpec(Settings.ACTION_WIFI_SETTINGS),
            APP_DETAILS,
        )
        SettingsTarget.NONE -> emptyList()
    }
}

/**
 * One settings screen as plain data, so the mapping is testable without Android: the intent [action], whether the
 * data is this app's `package:` URI, and whether `Settings.EXTRA_APP_PACKAGE` names this app.
 */
internal data class SettingsIntentSpec(
    val action: String,
    val packageUri: Boolean = false,
    val appPackageExtra: Boolean = false,
) {
    fun toIntent(packageName: String): Intent {
        val intent = if (packageUri) {
            Intent(action, Uri.fromParts(PACKAGE_SCHEME, packageName, null))
        } else {
            Intent(action)
        }
        if (appPackageExtra) intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        return intent
    }

    private companion object {
        const val PACKAGE_SCHEME = "package"
    }
}

/** The activity behind this context, unwrapping context wrappers, or null (for example the application context). */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    var depth = 0
    while (current != null && depth < MAX_CONTEXT_DEPTH) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
        depth++
    }
    return null
}

/** A context chain deeper than this is not a real wrapper chain; stop looking. */
private const val MAX_CONTEXT_DEPTH = 32
