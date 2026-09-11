package com.fieldtap.platform.location

import android.content.Context
import com.fieldtap.core.input.MeasurementInput
import com.fieldtap.core.time.Clock
import kotlinx.coroutines.flow.Flow

/**
 * The location adapter, platform `LocationManager` only (no Google Play services).
 *
 * [inputs] is a cold `callbackFlow` that, while collected and while precise location is granted:
 * - requests `GPS_PROVIDER` updates with `LocationRequest.Builder(intervalMs)` quality high accuracy, and
 *   `FUSED_PROVIDER` and `NETWORK_PROVIDER` updates at the same interval where `hasProvider` says they
 *   exist, each through the executor overload;
 * - turns each `Location` into a `FixSample` (provider from the provider name; wall time translated from
 *   `getElapsedRealtimeMillis()`), leaving provider selection to `com.fieldtap.core.location.FixSelector`;
 * - registers a `GnssStatus.Callback` and emits `GnssSnapshot`;
 * - emits `LocationAvailability` on start and whenever location is switched on or off;
 * - tolerates a `SecurityException` (permission revoked mid-flow) by emitting `LocationAvailability`
 *   with `preciseLocationGranted = false` and ending the flow normally.
 *
 * Owner: workstream `platform-adapters`.
 */
class LocationSource(
    private val context: Context,
    private val clock: Clock,
    private val intervalMs: Long = 1_000,
) {
    fun inputs(): Flow<MeasurementInput> = TODO("platform-adapters")
}
