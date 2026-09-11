package com.fieldtap.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.fieldtap.core.readiness.SettingsTarget
import com.fieldtap.ui.setup.SettingsIntents

/**
 * Opens system settings screens for the fixes the session screens offer, through ui-setup's
 * [SettingsIntents], so every screen links to the same place. A missing screen is reported as `false`
 * instead of crashing: some phone makers remove settings screens.
 *
 * Owner: workstream `ui-session`.
 */
object SystemSettings {
    /**
     * Opens the settings screen for [target]; false when there is none or no candidate could be started.
     * Delegates to [SettingsIntents.open], which starts each candidate in turn instead of resolving it
     * first, so it works even when a maker's Settings app is not visible to this app.
     */
    fun open(context: Context, target: SettingsTarget): Boolean = SettingsIntents.open(context, target)

    /** Starts [intent]; false when no activity handles it or Android refuses it. */
    fun launch(context: Context, intent: Intent): Boolean {
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}

/** The activity behind a possibly wrapped context, or null (an application or service context). */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
