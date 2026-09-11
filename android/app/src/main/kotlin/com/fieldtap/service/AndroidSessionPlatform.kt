package com.fieldtap.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.fieldtap.platform.Permissions

/**
 * [SessionPlatform] on Android: starts [SessionService] in the foreground, reads the location permission and
 * switch through [Permissions], and logs under the tag `FieldTapSession`.
 *
 * Owner: workstream `service-and-tests`.
 */
class AndroidSessionPlatform(private val context: Context) : SessionPlatform {
    override fun startService(action: String) {
        ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java).setAction(action))
    }

    override fun preciseLocationGranted(): Boolean = Permissions.preciseLocationGranted(context)

    override fun locationEnabled(): Boolean = Permissions.locationEnabled(context)

    override fun log(message: String, error: Throwable?) {
        Log.w(TAG, message, error)
    }

    private companion object {
        const val TAG = "FieldTapSession"
    }
}
