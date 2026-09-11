package com.fieldtap.platform

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import com.fieldtap.app.AppInfo

/**
 * Runtime permission names and checks, shared by the adapters, the readiness checker and the
 * permission screen. The names are literal strings, so no API-33 constant is referenced on API 31.
 * Every check is a quick local read and safe from any thread.
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

    /** ACCESS_FINE_LOCATION is granted: the user chose precise, not approximate, location. */
    fun preciseLocationGranted(context: Context): Boolean = granted(context, FINE_LOCATION)

    /**
     * The session notification can show: POST_NOTIFICATIONS is granted (a runtime permission from
     * Android 13) and the user has not switched the app's notifications off.
     */
    fun notificationsGranted(context: Context): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, POST_NOTIFICATIONS)
        return permitted && context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }

    fun phoneGranted(context: Context): Boolean = granted(context, READ_PHONE_STATE)

    /** Both permissions `CellInfoListener` needs ("Instant cell updates"): READ_PHONE_STATE and precise location. */
    fun instantCellUpdatesAllowed(context: Context): Boolean = phoneGranted(context) && preciseLocationGranted(context)

    /** `LocationManager.isLocationEnabled()`. */
    fun locationEnabled(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    /**
     * Permission name -> granted, for the probe report, in a fixed order. POST_NOTIFICATIONS reports
     * [notificationsGranted], so it is meaningful below Android 13 too.
     */
    fun snapshot(context: Context): Map<String, Boolean> = linkedMapOf(
        FINE_LOCATION to granted(context, FINE_LOCATION),
        COARSE_LOCATION to granted(context, COARSE_LOCATION),
        POST_NOTIFICATIONS to notificationsGranted(context),
        READ_PHONE_STATE to granted(context, READ_PHONE_STATE),
    )

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Version name and code from `PackageManager.getPackageInfo`, and the debuggable flag. No BuildConfig.
 *
 * Owner: workstream `platform-adapters`.
 */
object AppInfoReader {
    /** Written when the package declares no version name; `transport.app_version` must not be blank. */
    const val UNKNOWN_VERSION: String = "unknown"

    /**
     * Reads this app's own package. Throws [IllegalStateException] only if Android cannot find the app's
     * own package, which cannot happen while the app runs.
     */
    fun read(context: Context): AppInfo {
        val packageName = context.packageName
        val info: PackageInfo = try {
            packageInfo(context.packageManager, packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            throw IllegalStateException("Android cannot find this app's own package $packageName", e)
        }
        return AppInfo(
            versionName = versionNameOf(info.versionName),
            versionCode = info.longVersionCode,
            applicationId = packageName,
            debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
    }

    /** The package's version name, or [UNKNOWN_VERSION] when it declares none or a blank one. */
    internal fun versionNameOf(raw: String?): String = raw?.takeIf { it.isNotBlank() } ?: UNKNOWN_VERSION

    private fun packageInfo(manager: PackageManager, packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(packageName, 0)
        }
}
