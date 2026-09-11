package com.fieldtap.platform.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.util.Log
import androidx.core.content.ContextCompat
import com.fieldtap.core.input.FixSample
import com.fieldtap.core.input.GnssSnapshot
import com.fieldtap.core.input.LocationAvailability
import com.fieldtap.core.input.MeasurementInput
import com.fieldtap.core.time.Clock
import com.fieldtap.format.FixProvider
import com.fieldtap.platform.AdapterExecutor
import com.fieldtap.platform.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

private const val TAG = "FieldTapLocation"

/**
 * The location adapter, platform `LocationManager` only (no Google Play services).
 *
 * [inputs] is a cold `callbackFlow` that, while collected and while precise location is granted:
 * - requests `GPS_PROVIDER` updates with `LocationRequest.Builder(intervalMs)` quality high accuracy, and
 *   `FUSED_PROVIDER` and `NETWORK_PROVIDER` updates at the same interval where `hasProvider` says they
 *   exist, each through the executor overload. Each provider is requested on its own: one that Android fails
 *   to start is logged and the others still run;
 * - turns each `Location` into a `FixSample` (provider from the provider name; wall time translated from
 *   the fix's elapsed-realtime clock), leaving provider selection and mock rejection to
 *   `com.fieldtap.core.location.FixSelector`. Fixes from any other provider (passive, vendor ones) are dropped;
 * - registers a `GnssStatus.Callback` and emits `GnssSnapshot`;
 * - emits `LocationAvailability` on start and whenever location is switched on or off, a provider is
 *   enabled or disabled, or the permission is found revoked; an unchanged availability is not repeated;
 * - tolerates a `SecurityException` (permission revoked mid-flow) by emitting `LocationAvailability`
 *   with `preciseLocationGranted = false` and ending the flow normally. Without precise location at the
 *   start, it emits that availability and ends at once.
 * Callbacks run on the adapter's own thread and the flow is produced on `Dispatchers.IO`, so no collector's
 * thread makes a binder call. The buffer is unlimited: a slow collector delays fixes but never loses one.
 * Everything registered is removed when collection ends.
 *
 * Owner: workstream `platform-adapters`.
 */
class LocationSource(
    private val context: Context,
    private val clock: Clock,
    private val intervalMs: Long = 1_000,
) {
    init {
        require(intervalMs > 0) { "intervalMs must be positive, was $intervalMs" }
    }

    fun inputs(): Flow<MeasurementInput> = callbackFlow<MeasurementInput> {
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null) {
            trySend(
                LocationAvailability(
                    locationEnabled = false,
                    preciseLocationGranted = Permissions.preciseLocationGranted(context),
                    providers = emptySet(),
                    observedWallMs = clock.wallMillis(),
                    observedElapsedMs = clock.elapsedRealtimeMillis(),
                ),
            )
            close()
            return@callbackFlow
        }
        val tracking = Tracking(manager, this)
        try {
            if (tracking.start()) awaitClose()
        } finally {
            tracking.stop()
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    /** One collection of [inputs]. */
    private inner class Tracking(
        private val manager: LocationManager,
        private val out: SendChannel<MeasurementInput>,
    ) {
        private val executor = AdapterExecutor(THREAD_NAME)
        private val lock = Any()
        private var lastAvailability: LocationAvailability? = null
        private var gnssRegistered = false
        private var receiverRegistered = false

        private val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onFix(location)
            }

            override fun onProviderEnabled(provider: String) {
                publishAvailability()
            }

            override fun onProviderDisabled(provider: String) {
                publishAvailability()
            }
        }

        private val gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val wallMs = clock.wallMillis()
                val elapsedMs = clock.elapsedRealtimeMillis()
                val visible = status.satelliteCount
                out.trySend(GnssSnapshot(visible, LocationValues.usedInFix(visible, status::usedInFix), wallMs, elapsedMs))
            }
        }

        /** Broadcasts arrive on the main thread; the work moves to the adapter's thread at once. */
        private val modeReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                executor.execute { publishAvailability() }
            }
        }

        /** Emits the first availability and registers everything; false when the flow has ended already. */
        fun start(): Boolean {
            val first = publishAvailability()
            if (!first.preciseLocationGranted) return false
            val request = LocationRequest.Builder(intervalMs)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .build()
            try {
                var requested = 0
                for ((name, _) in LocationValues.PROVIDERS) {
                    if (requestProvider(name, request)) requested++
                }
                if (requested == 0) Log.w(TAG, "No location provider could be requested; no fixes will arrive")
                gnssRegistered = registerGnss()
            } catch (e: SecurityException) {
                Log.w(TAG, "Location permission was revoked while starting", e)
                revoked()
                return false
            }
            registerModeReceiver()
            return true
        }

        fun stop() {
            quietly("remove location updates") { manager.removeUpdates(listener) }
            if (gnssRegistered) quietly("unregister the GNSS callback") { manager.unregisterGnssStatusCallback(gnssCallback) }
            if (receiverRegistered) quietly("unregister the location mode receiver") { context.unregisterReceiver(modeReceiver) }
            executor.shutdown()
        }

        /** Sends the availability when it changed; ends the flow when precise location is gone. */
        fun publishAvailability(): LocationAvailability {
            val now = currentAvailability()
            synchronized(lock) {
                val previous = lastAvailability
                if (previous == null || !LocationValues.sameAvailability(previous, now)) {
                    lastAvailability = now
                    out.trySend(now)
                }
            }
            if (!now.preciseLocationGranted) out.close()
            return now
        }

        /**
         * Requests [name] when this phone has it. False when it does not, or when Android failed the request
         * for a reason other than the permission; a [SecurityException] propagates, because it ends the flow.
         */
        private fun requestProvider(name: String, request: LocationRequest): Boolean = try {
            if (manager.hasProvider(name)) {
                manager.requestLocationUpdates(name, request, executor, listener)
                true
            } else {
                false
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "The $name location provider could not be requested", e)
            false
        }

        /** False when Android declines or fails the satellite status registration; a [SecurityException] propagates. */
        private fun registerGnss(): Boolean = try {
            manager.registerGnssStatusCallback(executor, gnssCallback)
        } catch (e: SecurityException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "Satellite status will not be followed", e)
            false
        }

        private fun registerModeReceiver() {
            try {
                ContextCompat.registerReceiver(
                    context,
                    modeReceiver,
                    IntentFilter(LocationManager.MODE_CHANGED_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                receiverRegistered = true
            } catch (e: RuntimeException) {
                Log.w(TAG, "Location on and off changes will not be followed", e)
            }
        }

        private fun revoked() {
            val now = currentAvailability().copy(preciseLocationGranted = false)
            synchronized(lock) {
                lastAvailability = now
                out.trySend(now)
            }
            out.close()
        }

        private fun currentAvailability(): LocationAvailability {
            val granted = Permissions.preciseLocationGranted(context)
            val enabled = safely(false) { manager.isLocationEnabled }
            val providers = LocationValues.PROVIDERS
                .filter { (name, _) -> safely(false) { manager.isProviderEnabled(name) } }
                .map { (_, provider) -> provider }
                .toSet()
            return LocationAvailability(
                locationEnabled = enabled,
                preciseLocationGranted = granted,
                providers = providers,
                observedWallMs = clock.wallMillis(),
                observedElapsedMs = clock.elapsedRealtimeMillis(),
            )
        }

        private fun onFix(location: Location) {
            val wallMs = clock.wallMillis()
            val elapsedMs = clock.elapsedRealtimeMillis()
            val fix = LocationValues.fix(
                providerName = location.provider,
                fixElapsedNanos = location.elapsedRealtimeNanos,
                lat = location.latitude,
                lon = location.longitude,
                accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                altitudeM = if (location.hasAltitude()) location.altitude else null,
                speedMps = if (location.hasSpeed()) location.speed else null,
                mock = location.isMock,
                observedWallMs = wallMs,
                observedElapsedMs = elapsedMs,
            ) ?: return
            out.trySend(fix)
        }
    }

    private companion object {
        const val THREAD_NAME = "fieldtap-location"
    }
}

/**
 * The pure rules behind the location adapter. Provider names are Android's `LocationManager` constants,
 * pinned by LocationValuesTest.
 *
 * Owner: workstream `platform-adapters`.
 */
internal object LocationValues {
    /** The providers requested, in order: GPS, fused, network. */
    val PROVIDERS: List<Pair<String, FixProvider>> = listOf(
        "gps" to FixProvider.GPS,
        "fused" to FixProvider.FUSED,
        "network" to FixProvider.NETWORK,
    )

    /** `track.csv` `provider` for a provider name; null for any other provider (passive, vendor ones). */
    fun providerOf(name: String?): FixProvider? = PROVIDERS.firstOrNull { it.first == name }?.second

    /**
     * `Location.getElapsedRealtimeNanos()` in milliseconds. `getElapsedRealtimeMillis()` is the same
     * value but API 33, and the app runs from API 31.
     */
    fun elapsedMillis(elapsedRealtimeNanos: Long): Long = elapsedRealtimeNanos / 1_000_000

    /** The fix's instant on the app's wall clock: `observedWallMs - (observedElapsedMs - fixElapsedMs)`. */
    fun wallTimeOf(fixElapsedMs: Long, observedWallMs: Long, observedElapsedMs: Long): Long =
        observedWallMs - (observedElapsedMs - fixElapsedMs)

    /** How far a fix time may run ahead of the callback that delivered it, for the step between two clocks' readings. */
    const val FIX_CLOCK_TOLERANCE_MS: Long = 1_000

    /** The oldest a delivered fix may be: far beyond Android's location batching and the 60 s GPS join buffer. */
    const val MAX_FIX_AGE_MS: Long = 3_600_000

    /**
     * The fix time on the monotonic clock, in milliseconds. A `getElapsedRealtimeNanos()` that is not positive, runs more
     * than [FIX_CLOCK_TOLERANCE_MS] ahead of the callback, or is more than [MAX_FIX_AGE_MS] older is not a time since
     * boot: the API 31 emulator's GNSS reports wall-clock nanoseconds there, which put every fix 57 years ahead and left
     * every measurement without a position. Such a fix takes the callback's elapsed time, the nearest instant the app can
     * trust, so the track and the GPS join stay on one clock. A plausible time, even an old one, is kept as reported.
     */
    fun fixElapsedMillis(fixElapsedNanos: Long, observedElapsedMs: Long): Long {
        val reported = elapsedMillis(fixElapsedNanos)
        val plausible = reported > 0 &&
            reported <= observedElapsedMs + FIX_CLOCK_TOLERANCE_MS &&
            observedElapsedMs - reported <= MAX_FIX_AGE_MS
        return if (plausible) reported else observedElapsedMs
    }

    /** A float as the decimal it prints as, so 4.7f becomes 4.7 and not 4.699999809265137. */
    fun decimal(value: Float): Double = value.toString().toDouble()

    /**
     * A `Location`'s values as a [FixSample], stamped with the clocks read at the callback; null for a provider
     * the track does not record. [fixElapsedNanos] is `getElapsedRealtimeNanos()`. [accuracyM], [altitudeM] and
     * [speedMps] are passed only when `hasAccuracy()`, `hasAltitude()` and `hasSpeed()` say Android has them.
     */
    fun fix(
        providerName: String?,
        fixElapsedNanos: Long,
        lat: Double,
        lon: Double,
        accuracyM: Float?,
        altitudeM: Double?,
        speedMps: Float?,
        mock: Boolean,
        observedWallMs: Long,
        observedElapsedMs: Long,
    ): FixSample? {
        val provider = providerOf(providerName) ?: return null
        val fixElapsedMs = fixElapsedMillis(fixElapsedNanos, observedElapsedMs)
        return FixSample(
            elapsedMs = fixElapsedMs,
            wallMs = wallTimeOf(fixElapsedMs, observedWallMs, observedElapsedMs),
            lat = lat,
            lon = lon,
            accuracyM = accuracyM?.let { decimal(it) },
            altitudeM = altitudeM,
            speedMps = speedMps?.let { decimal(it) },
            provider = provider,
            mock = mock,
            observedWallMs = observedWallMs,
            observedElapsedMs = observedElapsedMs,
        )
    }

    /** How many of the [visible] satellites were used in the fix, asking [usedInFix] for each index. */
    fun usedInFix(visible: Int, usedInFix: (Int) -> Boolean): Int = (0 until visible).count(usedInFix)

    /** Equal apart from when they were observed. */
    fun sameAvailability(a: LocationAvailability, b: LocationAvailability): Boolean =
        a.locationEnabled == b.locationEnabled &&
            a.preciseLocationGranted == b.preciseLocationGranted &&
            a.providers == b.providers
}

private inline fun <T> safely(fallback: T, read: () -> T): T = try {
    read()
} catch (e: RuntimeException) {
    Log.w(TAG, "A location setting could not be read", e)
    fallback
}

private inline fun quietly(what: String, action: () -> Unit) {
    try {
        action()
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not $what", e)
    }
}
