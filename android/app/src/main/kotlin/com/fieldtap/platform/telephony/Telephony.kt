package com.fieldtap.platform.telephony

import android.content.Context
import android.telephony.CellInfo
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyDisplayInfo
import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.DataStateSnapshot
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.input.DisplayInfoSnapshot
import com.fieldtap.core.input.ListenerReport
import com.fieldtap.core.input.MeasurementInput
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.core.input.SignalSnapshot
import com.fieldtap.core.radio.CadencePolicy
import com.fieldtap.core.time.Clock
import com.fieldtap.format.HandsetMeta
import kotlinx.coroutines.flow.Flow

/**
 * The telephony adapter. Thin: every decision lives in :core.
 *
 * [inputs] is a cold `callbackFlow` that, while collected:
 * - uses the `TelephonyManager` of `SubscriptionManager.getDefaultDataSubscriptionId()` (follows the
 *   default data SIM, records which one as `subId`);
 * - emits `getAllCellInfo()` once as a `request` answer for the first screen, then calls
 *   `requestCellInfoUpdate(executor, callback)` every [requestPeriodMs]; each `onCellInfo` becomes a
 *   `CellInfoAnswer` (source `request`, cells via [CellInfoMapper], conditions from [conditions] read at
 *   the callback), each `onError` a `CellInfoRequestFailed`;
 * - registers each listener as its own `TelephonyCallback` object, separately, and emits a
 *   `ListenerReport` for each: SignalStrengthsListener, ServiceStateListener, DisplayInfoListener,
 *   DataConnectionStateListener always; CellInfoListener (source `push`) only when READ_PHONE_STATE and
 *   precise location are granted. A `SecurityException` from any one registration or request is caught,
 *   reported, and costs only that input;
 * - stamps every input with [clock] at the callback; unregisters everything on cancellation.
 * It never reads IMEI, IMSI, ICCID, phone number, serial or any subscriber identifier.
 *
 * [probeRestrictedListeners] tries PhysicalChannelConfigListener, BarringInfoListener and
 * RegistrationFailedListener once each and reports the outcome (expected: refused), unregistering
 * immediately. Used only by the capability probe.
 *
 * Owner: workstream `platform-adapters`.
 */
class TelephonySource(
    private val context: Context,
    private val clock: Clock,
    private val conditions: () -> DeviceConditions,
    private val requestPeriodMs: Long = CadencePolicy.REQUEST_PERIOD_MS,
) {
    fun inputs(): Flow<MeasurementInput> = TODO("platform-adapters")

    suspend fun probeRestrictedListeners(): List<ListenerReport> = TODO("platform-adapters")
}

/**
 * `CellInfo` -> [CellSnapshot] for every subclass (Nr, Lte, Wcdma, Gsm, Tdscdma, Cdma), field sources as
 * in CellSnapshot's KDoc, every sentinel through `com.fieldtap.core.CellValues`. Returns null only for
 * an unknown subclass. Unit tests use the pure part (value mapping); the subclass switch is exercised
 * on the emulator.
 *
 * Owner: workstream `platform-adapters`.
 */
object CellInfoMapper {
    fun map(info: CellInfo): CellSnapshot? = TODO("platform-adapters")

    fun mapAll(infos: List<CellInfo>): List<CellSnapshot> = TODO("platform-adapters")
}

/**
 * Service state, data state, display info and signal strength -> :core snapshots.
 * Emergency-only: see `ServiceStateSnapshot.emergencyOnly` (no public `isEmergencyOnly()` exists).
 * Data state ints are `TelephonyManager.DATA_*`.
 *
 * Owner: workstream `platform-adapters`.
 */
object TelephonyStateMapper {
    fun serviceState(state: ServiceState, wallMs: Long, elapsedMs: Long): ServiceStateSnapshot =
        TODO("platform-adapters")

    fun dataState(state: Int, networkType: Int, wallMs: Long, elapsedMs: Long): DataStateSnapshot =
        TODO("platform-adapters")

    fun displayInfo(info: TelephonyDisplayInfo, wallMs: Long, elapsedMs: Long): DisplayInfoSnapshot =
        TODO("platform-adapters")

    fun signal(strength: SignalStrength, wallMs: Long, elapsedMs: Long): SignalSnapshot = TODO("platform-adapters")
}

/**
 * `handset` for session.json, from `Build` and the default data SIM's `TelephonyManager`, with the
 * sources listed in docs/SESSION-FORMAT.md. Blank strings become null. `network_type` through
 * `NetworkTypeNames`. Never `Build.getSerial()` or any identifier.
 *
 * Owner: workstream `platform-adapters`.
 */
class HandsetInfoReader(private val context: Context) {
    fun read(): HandsetMeta = TODO("platform-adapters")
}
