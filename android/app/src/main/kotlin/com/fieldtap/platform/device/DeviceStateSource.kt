package com.fieldtap.platform.device

import android.content.Context
import com.fieldtap.core.input.DeviceConditions
import kotlinx.coroutines.flow.StateFlow

/**
 * Screen, charging and Wi-Fi state, the three inputs to Android's cell-info interval.
 *
 * - Screen: `PowerManager.isInteractive`, updated by `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF`.
 * - Charging: the sticky `ACTION_BATTERY_CHANGED` `EXTRA_STATUS` is CHARGING or FULL, or `EXTRA_PLUGGED`
 *   is non-zero; updated by `ACTION_POWER_CONNECTED`/`DISCONNECTED`.
 * - Wi-Fi connected: a `ConnectivityManager` network callback for `TRANSPORT_WIFI`. No SSID, BSSID or
 *   MAC is read, and ACCESS_WIFI_STATE is not declared.
 *
 * [state] starts its receivers when [start] is called and keeps them until [stop]; [current] is a
 * synchronous read, safe from any thread (the telephony callback uses it).
 *
 * Owner: workstream `platform-adapters`.
 */
class DeviceStateSource(private val context: Context) {
    val state: StateFlow<DeviceConditions> get() = TODO("platform-adapters")

    fun current(): DeviceConditions = TODO("platform-adapters")

    fun start(): Unit = TODO("platform-adapters")

    fun stop(): Unit = TODO("platform-adapters")
}
