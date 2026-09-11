package com.fieldtap.platform.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import com.fieldtap.core.input.DeviceConditions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "FieldTapDevice"

/**
 * Screen, charging and Wi-Fi state, the three inputs to Android's cell-info interval, read from the signals Android's
 * own `DeviceStateMonitor` uses, so the interval the app assumes, and the sampling gaps it times against it, are the
 * ones Android applies:
 *
 * - Screen on: any display in `Display.STATE_ON`, followed with a `DisplayManager.DisplayListener`. Not
 *   `PowerManager.isInteractive` or the SCREEN_ON/OFF broadcasts: they stay "on" while the proximity sensor blanks the
 *   display during a call, when Android has already moved to the 10 s interval.
 * - Charging: `BatteryManager.isCharging()`, then `BatteryManager.ACTION_CHARGING` and `ACTION_DISCHARGING`. Not the
 *   plug: plugged in below 90 %, Android counts the phone as charging only once the battery level has risen, and
 *   reports it 15 minutes after that; a charger that cannot keep up never counts.
 * - Wi-Fi connected: a `ConnectivityManager` network callback for `TRANSPORT_WIFI` with `NET_CAPABILITY_INTERNET`,
 *   restricted networks included, as Android requests it. Where that request is refused, a plain `TRANSPORT_WIFI`
 *   request instead. No SSID, BSSID or MAC is read, and ACCESS_WIFI_STATE is not declared.
 *
 * [state] follows these from [start] until the matching [stop]. Calls are counted, so the Live screen and the service
 * can each start and stop it; the listeners run while at least one start is unmatched. [current] is a synchronous
 * read, safe from any thread (the telephony callback uses it): while started it returns [state]'s value, otherwise it
 * reads the platform directly, where Wi-Fi means "the active network is Wi-Fi with internet". Constructing it touches
 * nothing; [start] makes a few quick binder calls.
 *
 * Owner: workstream `platform-adapters`.
 */
class DeviceStateSource(private val context: Context) {
    private val lock = Any()
    private var starts = 0
    private var receiver: BroadcastReceiver? = null
    private var displayListener: DisplayManager.DisplayListener? = null
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
            registerChargingBroadcasts()
            registerDisplayListener()
            registerWifiCallback()
        }
    }

    fun stop() {
        synchronized(lock) {
            if (starts == 0) return
            starts--
            if (starts > 0) return
            receiver?.let { registered ->
                quietly("unregister the charging receiver") { context.unregisterReceiver(registered) }
            }
            receiver = null
            displayListener?.let { registered ->
                quietly("unregister the display listener") { displays()?.unregisterDisplayListener(registered) }
            }
            displayListener = null
            wifiCallback?.let { registered ->
                quietly("unregister the Wi-Fi callback") { connectivity()?.unregisterNetworkCallback(registered) }
            }
            wifiCallback = null
            wifiNetworks.clear()
        }
    }

    private fun readNow(): DeviceConditions = DeviceConditions(
        screenOn = safely(false) { anyDisplayOn() },
        charging = safely(false) { context.getSystemService(BatteryManager::class.java)?.isCharging == true },
        wifiConnected = safely(false) { activeNetworkIsWifi() },
    )

    private fun anyDisplayOn(): Boolean {
        val displays = displays()?.displays ?: return false
        return DeviceValues.screenOn(displays.map { it.state })
    }

    private fun activeNetworkIsWifi(): Boolean {
        val manager = connectivity() ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun connectivity(): ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    private fun displays(): DisplayManager? = context.getSystemService(DisplayManager::class.java)

    /** Called with [lock] held. */
    private fun registerChargingBroadcasts() {
        val filter = IntentFilter(BatteryManager.ACTION_CHARGING).apply { addAction(BatteryManager.ACTION_DISCHARGING) }
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
            Log.w(TAG, "Charging changes will not be followed", e)
        }
    }

    /** Called with [lock] held. Display changes arrive on the main thread, which waits for [lock]. */
    private fun registerDisplayListener() {
        val manager = displays() ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = onDisplaysChanged(this)

            override fun onDisplayRemoved(displayId: Int) = onDisplaysChanged(this)

            override fun onDisplayChanged(displayId: Int) = onDisplaysChanged(this)
        }
        try {
            manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            displayListener = listener
        } catch (e: RuntimeException) {
            Log.w(TAG, "Screen changes will not be followed", e)
        }
    }

    private fun onDisplaysChanged(listener: DisplayManager.DisplayListener) {
        val on = safely(false) { anyDisplayOn() }
        synchronized(lock) {
            if (displayListener !== listener) return
            mutableState.update { it.copy(screenOn = on) }
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
        val androidsRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        try {
            manager.registerNetworkCallback(androidsRequest, callback)
            wifiCallback = callback
            return
        } catch (e: RuntimeException) {
            Log.w(TAG, "Android's Wi-Fi request was refused; following Wi-Fi networks without it", e)
        }
        try {
            manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
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
    /**
     * Screen on as Android's cell-info interval counts it: some display is `Display.STATE_ON`. A display that dozes, is
     * suspended, or is blanked by the proximity sensor is off.
     */
    fun screenOn(displayStates: List<Int>): Boolean = displayStates.any { it == Display.STATE_ON }

    /**
     * [conditions] after `BatteryManager.ACTION_CHARGING` or `ACTION_DISCHARGING`; null for any other action, plugging in
     * and unplugging included: Android decides charging from the battery level, not from the plug.
     */
    fun afterBroadcast(action: String, conditions: DeviceConditions): DeviceConditions? = when (action) {
        BatteryManager.ACTION_CHARGING -> conditions.copy(charging = true)
        BatteryManager.ACTION_DISCHARGING -> conditions.copy(charging = false)
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
