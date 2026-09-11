package com.fieldtap.platform

import android.content.Context
import com.fieldtap.app.AppInfo

/**
 * Runtime permission names and checks, shared by the adapters, the readiness checker and the
 * permission screen.
 *
 * Owner: workstream `platform-adapters`.
 */
object Permissions {
    const val FINE_LOCATION: String = "android.permission.ACCESS_FINE_LOCATION"
    const val COARSE_LOCATION: String = "android.permission.ACCESS_COARSE_LOCATION"
    const val POST_NOTIFICATIONS: String = "android.permission.POST_NOTIFICATIONS"
    const val READ_PHONE_STATE: String = "android.permission.READ_PHONE_STATE"

    /** Requested together: Android 12+ lets the user pick approximate, which returns no cell info. */
    val LOCATION: List<String> = listOf(FINE_LOCATION, COARSE_LOCATION)

    fun preciseLocationGranted(context: Context): Boolean = TODO("platform-adapters")

    fun notificationsGranted(context: Context): Boolean = TODO("platform-adapters")

    fun phoneGranted(context: Context): Boolean = TODO("platform-adapters")

    /** `LocationManager.isLocationEnabled()`. */
    fun locationEnabled(context: Context): Boolean = TODO("platform-adapters")

    /** Permission name -> granted, for the probe report. */
    fun snapshot(context: Context): Map<String, Boolean> = TODO("platform-adapters")
}

/**
 * Version name and code from `PackageManager.getPackageInfo`, and the debuggable flag. No BuildConfig.
 *
 * Owner: workstream `platform-adapters`.
 */
object AppInfoReader {
    fun read(context: Context): AppInfo = TODO("platform-adapters")
}
