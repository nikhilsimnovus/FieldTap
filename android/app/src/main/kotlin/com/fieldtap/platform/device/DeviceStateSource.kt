package com.fieldtap.platform.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.fieldtap.core.input.DeviceConditions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "FieldTapDevice"

/**
 * Screen, charging and Wi-Fi state, the three inputs to Android's cell-info interval.
 *
 * - Screen: `PowerManager.isInteractive`, updated by `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF`.
 * - Charging: the sticky `ACTION_BATTERY_CHANGED` `EXTRA_STATUS` is CHARGING or FULL, or `EXTRA_PLUGGED`
 *   is non-zero; updated by `ACTION_POWER_CONNECTED`/`DISCONNECTED`.
 * - Wi-Fi connected: a `ConnectivityManager` network callback for `TRANSPORT_WIFI`, the same request
 *   Android's own cell-info interval logic uses. No SSID, BSSID or MAC is read, and ACCESS_WIFI_STATE is
 *   not declared.
 *
 * [state] follows the receivers from [start] until the matching [stop]. Calls are counted, so the Live
 * screen and the service can each start and stop it; receivers run while at least one start is
 * unmatched. [current] is a synchronous read, safe from any thread (the telephony callback uses it):
 * while started it returns [state]'s value, otherwise it reads the platform directly, where Wi-Fi means
 * "the active network is Wi-Fi". Constructing it touches nothing; [start] makes a few quick binder calls.
 *
 * Owner: workstream `platform-adapters`.
 */
class DeviceStateSource(private val context: Context) {
    private val lock = Any()
    private var starts = 0
    private var receiver: BroadcastReceiver? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private val wifiNetworks = HashSet<Network>()
    private val mutableState =
        MutableStateFlow(DeviceConditions(screenOn = false, charging = false, wifiConnected = false))

    val state: StateFlow<DeviceConditions> = mutableState.asStateFlow()

    fun current(): DeviceConditions {
        if (synchronized(lock) { starts > 0 }) return mutableState.value
        val now = readNow()
        synchronized(lock) {
            // A start() that raced this read owns the state; only an idle source records the read.
            val idle = starts == 0
            if (idle) mutableState.value = now
            idle
        }
        return now
    }

    fun start() {
        synchronized(lock) {
            starts++
            if (starts > 1) return
            mutableState.value = readNow()
            registerBroadcasts()
            registerWifiCallback()
        }
    }

    fun stop() {
        synchronized(lock) {
            if (starts == 0) return
            starts--
            if (starts > 0) return
            receiver?.let { registered ->
                quietly("unregister the screen and power receiver") { context.unregisterReceiver(registered) }
            }
            receiver = null
            wifiCallback?.let { registered ->
                quietly("unregister the Wi-Fi callback") { connectivity()?.unregisterNetworkCallback(registered) }
            }
            wifiCallback = null
            wifiNetworks.clear()
        }
    }

    private fun readNow(): DeviceConditions = DeviceConditions(
        screenOn = safely(false) { context.getSystemService(PowerManager::class.java)?.isInteractive == true },
        charging = safely(false) { stickyCharging() },
        wifiConnected = safely(false) { activeNetworkIsWifi() },
    )

    private fun stickyCharging(): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return DeviceValues.charging(
            status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        )
    }

    private fun activeNetworkIsWifi(): Boolean {
        val manager = connectivity() ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun connectivity(): ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    /** Called with [lock] held. */
    private fun registerBroadcasts() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val created = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val action = intent.action ?: return
                mutableState.update { conditions -> DeviceValues.afterBroadcast(action, conditions) ?: conditions }
            }
        }
        try {
            ContextCompat.registerReceiver(context, created, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = created
        } catch (e: RuntimeException) {
            Log.w(TAG, "Screen and charging changes will not be followed", e)
        }
    }

    /**
     * Called with [lock] held. Android reports every Wi-Fi network that already exists right after
     * registering, on its own thread, which waits for [lock] and so sees [wifiCallback] set.
     */
    private fun registerWifiCallback() {
        val manager = connectivity() ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onWifiChange(this, network, available = true)
            }

            override fun onLost(network: Network) {
                onWifiChange(this, network, available = false)
            }
        }
        try {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            manager.registerNetworkCallback(request, callback)
            wifiCallback = callback
        } catch (e: RuntimeException) {
            Log.w(TAG, "Wi-Fi changes will not be followed", e)
        }
    }

    private fun onWifiChange(callback: ConnectivityManager.NetworkCallback, network: Network, available: Boolean) {
        synchronized(lock) {
            if (wifiCallback !== callback) return
            if (available) wifiNetworks.add(network) else wifiNetworks.remove(network)
            val connected = wifiNetworks.isNotEmpty()
            mutableState.update { it.copy(wifiConnected = connected) }
        }
    }
}

/**
 * The pure rules behind [DeviceStateSource].
 *
 * Owner: workstream `platform-adapters`.
 */
internal object DeviceValues {
    /** Charging: `EXTRA_STATUS` is CHARGING or FULL, or the phone is plugged in at all. */
    fun charging(status: Int, plugged: Int): Boolean =
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0

    /** [conditions] after a screen or power broadcast; null for any other action. */
    fun afterBroadcast(action: String, conditions: DeviceConditions): DeviceConditions? = when (action) {
        Intent.ACTION_SCREEN_ON -> conditions.copy(screenOn = true)
        Intent.ACTION_SCREEN_OFF -> conditions.copy(screenOn = false)
        Intent.ACTION_POWER_CONNECTED -> conditions.copy(charging = true)
        Intent.ACTION_POWER_DISCONNECTED -> conditions.copy(charging = false)
        else -> null
    }
}

private inline fun <T> safely(fallback: T, read: () -> T): T = try {
    read()
} catch (e: RuntimeException) {
    Log.w(TAG, "A device state could not be read", e)
    fallback
}

private inline fun quietly(what: String, action: () -> Unit) {
    try {
        action()
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not $what", e)
    }
}
