package com.fieldtap.platform.telephony

import android.content.Context
import android.os.Build
import android.telephony.BarringInfo
import android.telephony.CellIdentity
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.PhysicalChannelConfig
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import com.fieldtap.core.CellValues
import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.CellInfoRequestFailed
import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.DataStateSnapshot
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.input.DisplayInfoSnapshot
import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.ListenerReport
import com.fieldtap.core.input.MeasurementInput
import com.fieldtap.core.input.RadioListener
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.core.input.SignalSnapshot
import com.fieldtap.core.radio.CadencePolicy
import com.fieldtap.core.time.Clock
import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.HandsetMeta
import com.fieldtap.format.Rat
import com.fieldtap.platform.AdapterExecutor
import com.fieldtap.platform.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

private const val TAG = "FieldTapTelephony"
private const val TELEPHONY_UNAVAILABLE = "telephony service unavailable"

/**
 * The telephony adapter. Thin: every decision lives in :core.
 *
 * [inputs] is a cold `callbackFlow` that, while collected:
 * - uses the `TelephonyManager` of `SubscriptionManager.getDefaultDataSubscriptionId()` (follows the
 *   default data SIM, records which one as `subId`); a change of default data SIM applies from the next
 *   collection;
 * - emits `getAllCellInfo()` once as a `cached` answer (source `request`) for the first screen, then calls
 *   `requestCellInfoUpdate(executor, callback)` every [requestPeriodMs]; each `onCellInfo` becomes a
 *   `CellInfoAnswer` (source `request`, cells via [CellInfoMapper], conditions from [conditions] read at
 *   the callback), each `onError` a `CellInfoRequestFailed`;
 * - registers each listener as its own `TelephonyCallback` object, separately, and emits a
 *   `ListenerReport` for each: SignalStrengthsListener, ServiceStateListener, DisplayInfoListener,
 *   DataConnectionStateListener always; CellInfoListener (source `push`) only when READ_PHONE_STATE and
 *   precise location are granted, retried every period until they are, so turning on "Instant cell
 *   updates" takes effect without restarting. A `SecurityException` from any one registration or request
 *   is caught, reported, and costs only that input;
 * - skips the request while precise location is not granted and reports CELL_INFO_REQUEST as
 *   MISSING_PERMISSION; a request refused anyway yields a `CellInfoRequestFailed` too. A report is emitted
 *   when a listener's outcome changes, not on every period;
 * - stamps every input with [clock] at the callback; unregisters everything on cancellation.
 * Platform callbacks run on the adapter's own thread and the flow is produced on `Dispatchers.IO`, so no
 * collector's thread (the main thread included) makes a binder call. The buffer is unlimited: a slow
 * collector delays inputs but never loses one.
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
    init {
        require(requestPeriodMs > 0) { "requestPeriodMs must be positive, was $requestPeriodMs" }
    }

    fun inputs(): Flow<MeasurementInput> = inputs(onRequest = null)

    /**
     * [inputs], calling [onRequest] just before each `requestCellInfoUpdate` call and before the initial
     * `getAllCellInfo` read, so the capability probe can count requests.
     */
    internal fun inputs(onRequest: (() -> Unit)?): Flow<MeasurementInput> = callbackFlow<MeasurementInput> {
        val target = DefaultDataTelephony.resolve(context)
        if (target == null) {
            val wallMs = clock.wallMillis()
            val elapsedMs = clock.elapsedRealtimeMillis()
            for (listener in STANDARD_LISTENERS) {
                trySend(ListenerReport(listener, ListenerOutcome.FAILED, TELEPHONY_UNAVAILABLE, wallMs, elapsedMs))
            }
            close()
            return@callbackFlow
        }
        val listening = Listening(target, this, onRequest)
        try {
            listening.registerStateListeners()
            listening.emitCachedCells()
            while (true) {
                listening.ensurePushListener()
                listening.requestCellInfo()
                delay(requestPeriodMs)
            }
        } finally {
            listening.close()
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    suspend fun probeRestrictedListeners(): List<ListenerReport> = withContext(Dispatchers.IO) {
        val target = DefaultDataTelephony.resolve(context)
        val executor = AdapterExecutor(PROBE_THREAD_NAME)
        try {
            RESTRICTED_LISTENERS.map { listener ->
                if (target == null) {
                    stamped(listener, ListenerOutcome.FAILED, TELEPHONY_UNAVAILABLE)
                } else {
                    tryRestricted(target.manager, executor, listener)
                }
            }
        } finally {
            executor.shutdown()
        }
    }

    private fun tryRestricted(manager: TelephonyManager, executor: AdapterExecutor, listener: RadioListener): ListenerReport {
        val callback: TelephonyCallback = when (listener) {
            RadioListener.PHYSICAL_CHANNEL_CONFIG -> PhysicalChannelConfigProbe()
            RadioListener.BARRING_INFO -> BarringInfoProbe()
            RadioListener.REGISTRATION_FAILED -> RegistrationFailedProbe()
            else -> error("$listener is not a restricted listener")
        }
        return try {
            manager.registerTelephonyCallback(executor, callback)
            try {
                manager.unregisterTelephonyCallback(callback)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not unregister the probe's $listener callback", e)
            }
            stamped(listener, ListenerOutcome.REGISTERED, null)
        } catch (e: SecurityException) {
            stamped(
                listener,
                TelephonyValues.outcomeForSecurityException(listener, e.message),
                TelephonyValues.securityDetail(e.message),
            )
        } catch (e: RuntimeException) {
            stamped(listener, ListenerOutcome.FAILED, TelephonyValues.failureDetail(e.javaClass.simpleName, e.message))
        }
    }

    private fun stamped(listener: RadioListener, outcome: ListenerOutcome, detail: String?): ListenerReport =
        ListenerReport(listener, outcome, detail, clock.wallMillis(), clock.elapsedRealtimeMillis())

    /**
     * One collection of [inputs]. Registration, requests and [close] run sequentially in the producer
     * coroutine; only the platform callbacks run on [executor], and they only convert and send.
     */
    private inner class Listening(
        private val target: DefaultDataTelephony,
        private val out: SendChannel<MeasurementInput>,
        private val onRequest: (() -> Unit)?,
    ) {
        private val manager: TelephonyManager = target.manager
        private val executor = AdapterExecutor(THREAD_NAME)
        private val registered = ArrayList<TelephonyCallback>()
        private val reports = ListenerReportGate()
        private var pushState = PushListenerState.WAITING

        private val requestCallback = object : TelephonyManager.CellInfoCallback() {
            override fun onCellInfo(cellInfo: List<CellInfo>) {
                deliverAnswer(CellInfoSource.REQUEST, cellInfo)
            }

            override fun onError(errorCode: Int, detail: Throwable?) {
                val wallMs = clock.wallMillis()
                val elapsedMs = clock.elapsedRealtimeMillis()
                val cause = detail?.message ?: detail?.javaClass?.simpleName
                deliver(
                    CellInfoRequestFailed(errorCode, TelephonyValues.cellInfoErrorDetail(errorCode, cause), wallMs, elapsedMs),
                )
            }
        }

        fun registerStateListeners() {
            register(
                RadioListener.SIGNAL_STRENGTHS,
                SignalStrengthsCallback { strength ->
                    val wallMs = clock.wallMillis()
                    val elapsedMs = clock.elapsedRealtimeMillis()
                    deliver(TelephonyStateMapper.signal(strength, wallMs, elapsedMs))
                },
            )
            register(
                RadioListener.SERVICE_STATE,
                ServiceStateCallback { state ->
                    val wallMs = clock.wallMillis()
                    val elapsedMs = clock.elapsedRealtimeMillis()
                    deliver(TelephonyStateMapper.serviceState(state, wallMs, elapsedMs))
                },
            )
            register(
                RadioListener.DISPLAY_INFO,
                DisplayInfoCallback { info ->
                    val wallMs = clock.wallMillis()
                    val elapsedMs = clock.elapsedRealtimeMillis()
                    deliver(TelephonyStateMapper.displayInfo(info, wallMs, elapsedMs))
                },
            )
            register(
                RadioListener.DATA_CONNECTION_STATE,
                DataConnectionStateCallback { state, networkType ->
                    val wallMs = clock.wallMillis()
                    val elapsedMs = clock.elapsedRealtimeMillis()
                    deliver(TelephonyStateMapper.dataState(state, networkType, wallMs, elapsedMs))
                },
            )
        }

        /** Registers CellInfoListener once both permissions are granted; gives up only on a non-permission failure. */
        fun ensurePushListener() {
            if (pushState != PushListenerState.WAITING) return
            if (!Permissions.instantCellUpdatesAllowed(context)) {
                report(RadioListener.CELL_INFO_PUSH, ListenerOutcome.MISSING_PERMISSION, PUSH_NEEDS_PERMISSIONS)
                return
            }
            val outcome = register(
                RadioListener.CELL_INFO_PUSH,
                CellInfoPushCallback { cells -> deliverAnswer(CellInfoSource.PUSH, cells) },
            )
            pushState = PushListenerState.after(outcome)
        }

        /** The cached list once, for the first screen. */
        fun emitCachedCells() {
            if (!Permissions.preciseLocationGranted(context)) return
            try {
                onRequest?.invoke()
                val infos: List<CellInfo> = manager.allCellInfo.orEmpty()
                // Android's cached list: whatever the last requester received. A session never writes it.
                deliverAnswer(CellInfoSource.REQUEST, infos, cached = true)
            } catch (e: SecurityException) {
                failed(TelephonyValues.securityDetail(e.message))
            } catch (e: RuntimeException) {
                failed(TelephonyValues.failureDetail(e.javaClass.simpleName, e.message))
            }
        }

        fun requestCellInfo() {
            if (!Permissions.preciseLocationGranted(context)) {
                report(RadioListener.CELL_INFO_REQUEST, ListenerOutcome.MISSING_PERMISSION, PRECISE_LOCATION_NEEDED)
                return
            }
            try {
                onRequest?.invoke()
                manager.requestCellInfoUpdate(executor, requestCallback)
                report(RadioListener.CELL_INFO_REQUEST, ListenerOutcome.REGISTERED, null)
            } catch (e: SecurityException) {
                val detail = TelephonyValues.securityDetail(e.message)
                report(RadioListener.CELL_INFO_REQUEST, ListenerOutcome.MISSING_PERMISSION, detail)
                failed(detail)
            } catch (e: RuntimeException) {
                val detail = TelephonyValues.failureDetail(e.javaClass.simpleName, e.message)
                report(RadioListener.CELL_INFO_REQUEST, ListenerOutcome.FAILED, detail)
                failed(detail)
            }
        }

        fun close() {
            for (callback in registered) {
                try {
                    manager.unregisterTelephonyCallback(callback)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Could not unregister a telephony listener", e)
                }
            }
            registered.clear()
            executor.shutdown()
        }

        private fun register(listener: RadioListener, callback: TelephonyCallback): ListenerOutcome {
            val attempt = try {
                manager.registerTelephonyCallback(executor, callback)
                registered += callback
                Attempt(ListenerOutcome.REGISTERED, null)
            } catch (e: SecurityException) {
                Attempt(
                    TelephonyValues.outcomeForSecurityException(listener, e.message),
                    TelephonyValues.securityDetail(e.message),
                )
            } catch (e: RuntimeException) {
                Attempt(ListenerOutcome.FAILED, TelephonyValues.failureDetail(e.javaClass.simpleName, e.message))
            }
            report(listener, attempt.outcome, attempt.detail)
            return attempt.outcome
        }

        /** Emits a [ListenerReport] when [listener]'s outcome or detail differs from the last one reported. */
        private fun report(listener: RadioListener, outcome: ListenerOutcome, detail: String?) {
            if (!reports.shouldReport(listener, outcome, detail)) return
            deliver(ListenerReport(listener, outcome, detail, clock.wallMillis(), clock.elapsedRealtimeMillis()))
        }

        private fun deliverAnswer(source: CellInfoSource, infos: List<CellInfo>, cached: Boolean = false) {
            val wallMs = clock.wallMillis()
            val elapsedMs = clock.elapsedRealtimeMillis()
            val atAnswer = conditions()
            deliver(CellInfoAnswer(source, CellInfoMapper.mapAll(infos), target.subId, atAnswer, wallMs, elapsedMs, cached = cached))
        }

        private fun failed(detail: String) {
            deliver(CellInfoRequestFailed(null, detail, clock.wallMillis(), clock.elapsedRealtimeMillis()))
        }

        private fun deliver(input: MeasurementInput) {
            out.trySend(input)
        }
    }

    private companion object {
        const val THREAD_NAME = "fieldtap-telephony"
        const val PROBE_THREAD_NAME = "fieldtap-telephony-probe"
        const val PRECISE_LOCATION_NEEDED = "precise location not granted"
        const val PUSH_NEEDS_PERMISSIONS = "needs the Phone permission and precise location"

        val STANDARD_LISTENERS: List<RadioListener> = listOf(
            RadioListener.CELL_INFO_REQUEST,
            RadioListener.CELL_INFO_PUSH,
            RadioListener.SIGNAL_STRENGTHS,
            RadioListener.SERVICE_STATE,
            RadioListener.DISPLAY_INFO,
            RadioListener.DATA_CONNECTION_STATE,
        )

        val RESTRICTED_LISTENERS: List<RadioListener> = listOf(
            RadioListener.PHYSICAL_CHANNEL_CONFIG,
            RadioListener.BARRING_INFO,
            RadioListener.REGISTRATION_FAILED,
        )
    }
}

/**
 * `CellInfo` -> [CellSnapshot] for every subclass (Nr, Lte, Wcdma, Gsm, Tdscdma, Cdma), field sources as
 * in CellSnapshot's KDoc, every sentinel through `com.fieldtap.core.CellValues`. Returns null only for
 * an unknown subclass. Unit tests use the pure part ([RawCell.toSnapshot]); the subclass switch is
 * exercised on the emulator.
 *
 * Field notes: NR's `getCellIdentity()` and `getCellSignalStrength()` return the base types in the SDK, so
 * they are cast; WCDMA and TD-SCDMA have no RSSI getter; CDMA's RSSI is `getCdmaDbm()`; LTE's cell id is
 * an int widened to long.
 *
 * Owner: workstream `platform-adapters`.
 */
object CellInfoMapper {
    fun map(info: CellInfo): CellSnapshot? = raw(info)?.toSnapshot()

    /** Every cell in Android's order. A cell Android cannot describe is logged and left out, not the answer. */
    fun mapAll(infos: List<CellInfo>): List<CellSnapshot> = infos.mapNotNull { info ->
        try {
            map(info)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Left out a ${info.javaClass.simpleName} that could not be read", e)
            null
        }
    }

    // CellInfoCdma is deprecated since CDMA networks were switched off, but a phone can still report such a cell,
    // and cdma is a RAT the session format accepts, so it is still read rather than dropped.
    @Suppress("DEPRECATION")
    internal fun raw(info: CellInfo): RawCell? = when (info) {
        is CellInfoNr -> nr(info)
        is CellInfoLte -> lte(info)
        is CellInfoWcdma -> wcdma(info)
        is CellInfoGsm -> gsm(info)
        is CellInfoTdscdma -> tdscdma(info)
        is CellInfoCdma -> cdma(info)
        else -> null
    }

    private fun nr(info: CellInfoNr): RawCell {
        val identity = info.cellIdentity as CellIdentityNr
        val signal = info.cellSignalStrength as CellSignalStrengthNr
        return RawCell(
            rat = Rat.NR,
            registered = info.isRegistered,
            connectionStatus = info.cellConnectionStatus,
            timestampMs = info.timestampMillis,
            mcc = identity.mccString,
            mnc = identity.mncString,
            operatorLong = identity.operatorAlphaLong,
            operatorShort = identity.operatorAlphaShort,
            pci = identity.pci,
            arfcn = identity.nrarfcn,
            bands = identity.bands.toList(),
            tac = identity.tac,
            cellId = identity.nci,
            additionalPlmns = identity.additionalPlmns.orEmpty(),
            rsrp = signal.ssRsrp,
            rsrq = signal.ssRsrq,
            sinr = signal.ssSinr,
            csiRsrp = signal.csiRsrp,
            csiRsrq = signal.csiRsrq,
            csiSinr = signal.csiSinr,
            level = signal.level,
        )
    }

    private fun lte(info: CellInfoLte): RawCell {
        val identity = info.cellIdentity
        val signal = info.cellSignalStrength
        return RawCell(
            rat = Rat.LTE,
            registered = info.isRegistered,
            connectionStatus = info.cellConnectionStatus,
            timestampMs = info.timestampMillis,
            mcc = identity.mccString,
            mnc = identity.mncString,
            operatorLong = identity.operatorAlphaLong,
            operatorShort = identity.operatorAlphaShort,
            pci = identity.pci,
            arfcn = identity.earfcn,
            bands = identity.bands.toList(),
            tac = identity.tac,
            cellId = identity.ci.toLong(),
            bandwidthKhz = identity.bandwidth,
            additionalPlmns = identity.additionalPlmns.orEmpty(),
            rsrp = signal.rsrp,
            rsrq = signal.rsrq,
            sinr = signal.rssnr,
            rssi = signal.rssi,
            level = signal.level,
            cqi = signal.cqi,
            timingAdvance = signal.timingAdvance,
        )
    }

    private fun wcdma(info: CellInfoWcdma): RawCell {
        val identity = info.cellIdentity
        val signal = info.cellSignalStrength
        return RawCell(
            rat = Rat.WCDMA,
            registered = info.isRegistered,
            connectionStatus = info.cellConnectionStatus,
            timestampMs = info.timestampMillis,
            mcc = identity.mccString,
            mnc = identity.mncString,
            operatorLong = identity.operatorAlphaLong,
            operatorShort = identity.operatorAlphaShort,
            arfcn = identity.uarfcn,
            tac = identity.lac,
            cellId = identity.cid.toLong(),
            additionalPlmns = identity.additionalPlmns.orEmpty(),
            level = signal.level,
        )
    }

    private fun gsm(info: CellInfoGsm): RawCell {
        val identity = info.cellIdentity
        val signal = info.cellSignalStrength
        return RawCell(
            rat = Rat.GSM,
            registered = info.isRegistered,
            connectionStatus = info.cellConnectionStatus,
            timestampMs = info.timestampMillis,
            mcc = identity.mccString,
            mnc = identity.mncString,
            operatorLong = identity.operatorAlphaLong,
            operatorShort = identity.operatorAlphaShort,
            arfcn = identity.arfcn,
            tac = identity.lac,
            cellId = identity.cid.toLong(),
            additionalPlmns = identity.additionalPlmns.orEmpty(),
            rssi = signal.rssi,
            level = signal.level,
        )
    }

    private fun tdscdma(info: CellInfoTdscdma): RawCell {
        val identity = info.cellIdentity
        val signal = info.cellSignalStrength
        return RawCell(
            rat = Rat.TDSCDMA,
            registered = info.isRegistered,
            connectionStatus = info.cellConnectionStatus,
            timestampMs = info.timestampMillis,
            mcc = identity.mccString,
            mnc = identity.mncString,
            operatorLong = identity.operatorAlphaLong,
            operatorShort = identity.operatorAlphaShort,
            arfcn = identity.uarfcn,
            tac = identity.lac,
            cellId = identity.cid.toLong(),
            additionalPlmns = identity.additionalPlmns.orEmpty(),
            level = signal.level,
        )
    }

    @Suppress("DEPRECATION")
    private fun cdma(info: CellInfoCdma): RawCell {
        val identity = info.cellIdentity
        val signal = info.cellSignalStrength
        return RawCell(
            rat = Rat.CDMA,
            registered = info.isRegistered,
            connectionStatus = info.cellConnectionStatus,
            timestampMs = info.timestampMillis,
            operatorLong = identity.operatorAlphaLong,
            operatorShort = identity.operatorAlphaShort,
            cellId = identity.basestationId.toLong(),
            rssi = signal.cdmaDbm,
            level = signal.level,
        )
    }
}

/**
 * Service state, data state, display info and signal strength -> :core snapshots.
 * Emergency-only: see `ServiceStateSnapshot.emergencyOnly` (no public `isEmergencyOnly()` exists) and
 * [TelephonyValues.emergencyOnly]; only cellular (WWAN) registrations are consulted.
 * Data state ints are `TelephonyManager.DATA_*`.
 *
 * Owner: workstream `platform-adapters`.
 */
object TelephonyStateMapper {
    fun serviceState(state: ServiceState, wallMs: Long, elapsedMs: Long): ServiceStateSnapshot {
        val rawState = state.state
        val cellular = state.networkRegistrationInfoList.orEmpty()
            .filter { it.transportType == TelephonyValues.TRANSPORT_TYPE_WWAN }
        val registered = cellular.any { registered(it) }
        val emergencyAvailable = cellular.any { TelephonyValues.SERVICE_TYPE_EMERGENCY in it.availableServices.orEmpty() }
        // The operator fields are location-sensitive. Android normally redacts them rather than throwing, but a
        // refusal costs only these two values, never the service state itself.
        val operatorNumeric = try {
            TelephonyValues.text(state.operatorNumeric)
        } catch (e: SecurityException) {
            null
        }
        val operatorAlphaLong = try {
            TelephonyValues.text(state.operatorAlphaLong)
        } catch (e: SecurityException) {
            null
        }
        return ServiceStateSnapshot(
            state = TelephonyValues.regState(rawState),
            emergencyOnly = TelephonyValues.emergencyOnly(rawState, registered, emergencyAvailable),
            operatorNumeric = operatorNumeric,
            operatorAlphaLong = operatorAlphaLong,
            roaming = TelephonyValues.roaming(rawState, state.roaming),
            observedWallMs = wallMs,
            observedElapsedMs = elapsedMs,
        )
    }

    /** `isNetworkRegistered()` from API 34, which replaced `isRegistered()`; the older call below that. */
    @Suppress("DEPRECATION")
    private fun registered(info: android.telephony.NetworkRegistrationInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.isNetworkRegistered else info.isRegistered

    fun dataState(state: Int, networkType: Int, wallMs: Long, elapsedMs: Long): DataStateSnapshot =
        DataStateSnapshot(
            state = TelephonyValues.dataConnState(state),
            networkType = networkType,
            observedWallMs = wallMs,
            observedElapsedMs = elapsedMs,
        )

    fun displayInfo(info: TelephonyDisplayInfo, wallMs: Long, elapsedMs: Long): DisplayInfoSnapshot =
        DisplayInfoSnapshot(
            networkType = info.networkType,
            overrideNetworkType = info.overrideNetworkType,
            observedWallMs = wallMs,
            observedElapsedMs = elapsedMs,
        )

    /** The first valid LTE and NR parts of [strength]; Android lists only valid parts. */
    fun signal(strength: SignalStrength, wallMs: Long, elapsedMs: Long): SignalSnapshot {
        val lte = strength.getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull()
        val nr = strength.getCellSignalStrengths(CellSignalStrengthNr::class.java).firstOrNull()
        return SignalSnapshot(
            lteRsrp = lte?.let { CellValues.intOrNull(it.rsrp) },
            lteRsrq = lte?.let { CellValues.intOrNull(it.rsrq) },
            lteRssnr = lte?.let { CellValues.intOrNull(it.rssnr) },
            nrSsRsrp = nr?.let { CellValues.intOrNull(it.ssRsrp) },
            nrSsRsrq = nr?.let { CellValues.intOrNull(it.ssRsrq) },
            nrSsSinr = nr?.let { CellValues.intOrNull(it.ssSinr) },
            level = CellValues.intOrNull(strength.level),
            modemTimestampMs = CellValues.longOrNull(strength.timestampMillis),
            observedWallMs = wallMs,
            observedElapsedMs = elapsedMs,
        )
    }
}

/**
 * `handset` for session.json, from `Build` and the default data SIM's `TelephonyManager`, with the
 * sources listed in docs/SESSION-FORMAT.md. Blank strings become null, and so does `Build`'s `unknown`
 * placeholder, matching the laptop tool, whose `getprop` reads come back empty. `network_type` through
 * `NetworkTypeNames`; Android refuses `getDataNetworkType()` without the Phone permission, so
 * [read] with a fallback type exists. Never `Build.getSerial()` or any identifier. Call it off the main
 * thread: the operator fields are binder calls.
 *
 * Owner: workstream `platform-adapters`.
 */
class HandsetInfoReader(private val context: Context) {
    fun read(): HandsetMeta = read(fallbackNetworkType = null)

    /**
     * As [read], using [fallbackNetworkType] (a `TelephonyManager.NETWORK_TYPE_*`, for example the newest
     * `DisplayInfoSnapshot.networkType`) when `getDataNetworkType()` is refused or unknown.
     */
    fun read(fallbackNetworkType: Int?): HandsetMeta {
        val manager = DefaultDataTelephony.resolve(context)?.manager
        return HandsetMeta(
            manufacturer = TelephonyValues.buildValue(Build.MANUFACTURER),
            model = TelephonyValues.buildValue(Build.MODEL),
            device = TelephonyValues.buildValue(Build.DEVICE),
            androidVersion = TelephonyValues.buildValue(Build.VERSION.RELEASE),
            androidBuild = TelephonyValues.buildValue(Build.DISPLAY),
            securityPatch = TelephonyValues.buildValue(Build.VERSION.SECURITY_PATCH),
            baseband = TelephonyValues.buildValue(Build.getRadioVersion()),
            soc = TelephonyValues.buildValue(Build.SOC_MODEL),
            platform = TelephonyValues.buildValue(Build.BOARD),
            hardware = TelephonyValues.buildValue(Build.HARDWARE),
            operatorMccmnc = manager?.let { operatorText { it.networkOperator } },
            operatorName = manager?.let { operatorText { it.networkOperatorName } },
            simMccmnc = manager?.let { operatorText { it.simOperator } },
            simOperatorName = manager?.let { operatorText { it.simOperatorName } },
            networkType = TelephonyValues.networkTypeName(manager?.let { dataNetworkType(it) }, fallbackNetworkType),
        )
    }

    private inline fun operatorText(read: () -> String?): String? = try {
        TelephonyValues.text(read())
    } catch (e: RuntimeException) {
        Log.w(TAG, "An operator field could not be read", e)
        null
    }

    private fun dataNetworkType(manager: TelephonyManager): Int? = try {
        manager.dataNetworkType
    } catch (e: SecurityException) {
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "The data network type could not be read", e)
        null
    }
}

/** The `TelephonyManager` of the default data SIM, and that SIM's subscription id when it is valid. */
private class DefaultDataTelephony(val manager: TelephonyManager, val subId: Int?) {
    companion object {
        fun resolve(context: Context): DefaultDataTelephony? {
            val base = context.getSystemService(TelephonyManager::class.java) ?: return null
            val subId = SubscriptionManager.getDefaultDataSubscriptionId()
            return if (SubscriptionManager.isValidSubscriptionId(subId)) {
                DefaultDataTelephony(base.createForSubscriptionId(subId), subId)
            } else {
                DefaultDataTelephony(base, null)
            }
        }
    }
}

/** A listener's outcome and detail, compared to report changes only. */
private data class Attempt(val outcome: ListenerOutcome, val detail: String?)

private class SignalStrengthsCallback(private val onChange: (SignalStrength) -> Unit) :
    TelephonyCallback(),
    TelephonyCallback.SignalStrengthsListener {
    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
        onChange(signalStrength)
    }
}

private class ServiceStateCallback(private val onChange: (ServiceState) -> Unit) :
    TelephonyCallback(),
    TelephonyCallback.ServiceStateListener {
    override fun onServiceStateChanged(serviceState: ServiceState) {
        onChange(serviceState)
    }
}

private class DisplayInfoCallback(private val onChange: (TelephonyDisplayInfo) -> Unit) :
    TelephonyCallback(),
    TelephonyCallback.DisplayInfoListener {
    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
        onChange(telephonyDisplayInfo)
    }
}

private class DataConnectionStateCallback(private val onChange: (Int, Int) -> Unit) :
    TelephonyCallback(),
    TelephonyCallback.DataConnectionStateListener {
    override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
        onChange(state, networkType)
    }
}

private class CellInfoPushCallback(private val onChange: (List<CellInfo>) -> Unit) :
    TelephonyCallback(),
    TelephonyCallback.CellInfoListener {
    override fun onCellInfoChanged(cellInfo: List<CellInfo>) {
        onChange(cellInfo)
    }
}

/** Registered only to see whether Android refuses it; the value is never read. */
private class PhysicalChannelConfigProbe :
    TelephonyCallback(),
    TelephonyCallback.PhysicalChannelConfigListener {
    override fun onPhysicalChannelConfigChanged(configs: List<PhysicalChannelConfig>) = Unit
}

/** Registered only to see whether Android refuses it; the value is never read. */
private class BarringInfoProbe :
    TelephonyCallback(),
    TelephonyCallback.BarringInfoListener {
    override fun onBarringInfoChanged(barringInfo: BarringInfo) = Unit
}

/** Registered only to see whether Android refuses it; no cell identity or cause is ever read. */
private class RegistrationFailedProbe :
    TelephonyCallback(),
    TelephonyCallback.RegistrationFailedListener {
    override fun onRegistrationFailed(
        cellIdentity: CellIdentity,
        chosenPlmn: String,
        domain: Int,
        causeCode: Int,
        additionalCauseCode: Int,
    ) = Unit
}
