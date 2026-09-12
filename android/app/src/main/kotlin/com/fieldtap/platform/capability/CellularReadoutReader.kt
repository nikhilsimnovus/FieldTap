package com.fieldtap.platform.capability

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.fieldtap.app.AppInfo
import com.fieldtap.core.capability.CellularReadout
import com.fieldtap.platform.Permissions

/**
 * The permission/SIM/location snapshot the verdict reads. SIM state only — never the SIM's number or
 * ICCID, and no other identifier. `buildAcceptsMockLocations` mirrors `FixSelector`'s own rule: a debug
 * build accepts mock fixes, a release build rejects them (so it equals [AppInfo.debuggable]).
 *
 * SIM readiness uses any active modem slot (a dual-SIM phone with its SIM in the second slot counts),
 * matching `AndroidReadinessChecker`. A fact Android fails to report is logged and read as the safe value
 * (not granted, off, no ready SIM). Binder reads must run off the main thread.
 *
 * Owner: workstream `capability-core`.
 */
class CellularReadoutReader(
    private val context: Context,
    private val appInfo: AppInfo,
) {
    /** [mockLocationAppSet] comes from [SettingsReader.readMockLocation]. */
    fun read(mockLocationAppSet: Boolean): CellularReadout = CellularReadout(
        readPhoneStateGranted = read(false) { Permissions.phoneGranted(context) },
        preciseLocationGranted = read(false) { Permissions.preciseLocationGranted(context) },
        locationServicesEnabled = read(false) { Permissions.locationEnabled(context) },
        simReady = read(false) { anySimReady() },
        mockLocationAppSet = mockLocationAppSet,
        buildAcceptsMockLocations = appInfo.debuggable,
    )

    private fun anySimReady(): Boolean {
        val manager = context.getSystemService(TelephonyManager::class.java) ?: return false
        val slots = manager.activeModemCount.coerceAtLeast(1)
        return (0 until slots).any { slot -> manager.getSimState(slot) == TelephonyManager.SIM_STATE_READY }
    }

    private inline fun <T> read(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        Log.w(TAG, "A cellular readout fact could not be read", e)
        fallback
    }

    private companion object {
        const val TAG = "FieldTapCapability"
    }
}
