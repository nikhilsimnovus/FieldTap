package com.fieldtap.core.input

import com.fieldtap.format.CellInfoSource
import com.fieldtap.format.Rat

/*
 * Radio inputs: what the telephony adapter (com.fieldtap.platform.telephony, workstream
 * `platform-adapters`) delivers. Shapes are frozen; see android/ARCHITECTURE.md.
 *
 * Owner: workstream `radio-core`.
 */

/**
 * One `android.telephony.CellInfo` as plain values. Every Android "unavailable" sentinel
 * (`CellInfo.UNAVAILABLE`, `UNAVAILABLE_LONG`, `CONNECTION_UNKNOWN`) is already null here.
 *
 * Field sources, per subclass (the adapter's contract):
 * - [rat]: the subclass. [registered]: `isRegistered()`. [connectionStatus]: `getCellConnectionStatus()`.
 * - [timestampMs]: `getTimestampMillis()`, milliseconds since boot when the modem measured.
 * - Identity: [mcc]/[mnc] `getMccString()`/`getMncString()` (leading zeros kept); [operatorLong] and
 *   [operatorShort] `getOperatorAlphaLong()`/`Short()`; [pci] `getPci()` (LTE, NR); [arfcn]
 *   `getNrarfcn()`, `getEarfcn()`, `getUarfcn()` or `getArfcn()`; [bands] `getBands()` in Android's
 *   order; [tac] `getTac()` or `getLac()`; [cellId] `getNci()`, `getCi()`, `getCid()` or
 *   `getBasestationId()`; [bandwidthKhz] LTE `getBandwidth()`; [additionalPlmns] `getAdditionalPlmns()`.
 * - Signal: [rsrp]/[rsrq]/[sinr] are LTE `getRsrp()`/`getRsrq()`/`getRssnr()` and NR
 *   `getSsRsrp()`/`getSsRsrq()`/`getSsSinr()`; [csiRsrp]/[csiRsrq]/[csiSinr] NR only; [rssi] where the RAT
 *   has one; [level] `getLevel()`; [cqi] and [timingAdvance] LTE only.
 * No identifier beyond cell identity is ever read.
 */
data class CellSnapshot(
    val rat: Rat,
    val registered: Boolean,
    val connectionStatus: Int?,
    val timestampMs: Long,
    val mcc: String? = null,
    val mnc: String? = null,
    val operatorLong: String? = null,
    val operatorShort: String? = null,
    val pci: Int? = null,
    val arfcn: Int? = null,
    val bands: List<Int> = emptyList(),
    val tac: Int? = null,
    val cellId: Long? = null,
    val bandwidthKhz: Int? = null,
    val additionalPlmns: List<String> = emptyList(),
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val sinr: Int? = null,
    val csiRsrp: Int? = null,
    val csiRsrq: Int? = null,
    val csiSinr: Int? = null,
    val rssi: Int? = null,
    val level: Int? = null,
    val cqi: Int? = null,
    val timingAdvance: Int? = null,
) {
    companion object {
        const val CONNECTION_NONE: Int = 0
        const val CONNECTION_PRIMARY_SERVING: Int = 1
        const val CONNECTION_SECONDARY_SERVING: Int = 2
    }
}

/**
 * The screen, charging and Wi-Fi state at the moment of an answer. These decide Android's
 * cell-info interval: 2 s when the screen is on and (Wi-Fi is off or the phone is charging),
 * otherwise 10 s. Wi-Fi means "connected to a Wi-Fi network"; no SSID or BSSID is ever read.
 */
data class DeviceConditions(
    val screenOn: Boolean,
    val charging: Boolean,
    val wifiConnected: Boolean,
)

/**
 * One cell-info answer: a `requestCellInfoUpdate` `onCellInfo` callback or a `CellInfoListener`
 * callback, with every `CellInfo` in the list, in Android's order. [conditions] are read by the
 * adapter synchronously when the callback fires. [subId] is
 * `SubscriptionManager.getDefaultDataSubscriptionId()`, null when invalid.
 *
 * [cached] marks the one `getAllCellInfo()` read the adapter makes when it starts: Android's cached list,
 * whatever the last requester received, possibly minutes old, and not a `requestCellInfoUpdate` answer at
 * all. It fills the Live screen before the first request comes back; a session never writes it, counts it
 * or times a gap from it.
 */
data class CellInfoAnswer(
    val source: CellInfoSource,
    val cells: List<CellSnapshot>,
    val subId: Int?,
    val conditions: DeviceConditions,
    override val observedWallMs: Long,
    override val observedElapsedMs: Long,
    val cached: Boolean = false,
) : MeasurementInput

/** `CellInfoCallback.onError`, or a `SecurityException` from `requestCellInfoUpdate`. Never written to a file. */
data class CellInfoRequestFailed(
    val errorCode: Int?,
    val detail: String?,
    override val observedWallMs: Long,
    override val observedElapsedMs: Long,
) : MeasurementInput

/** `ServiceState.getState()`. */
enum class ServiceRegState { IN_SERVICE, OUT_OF_SERVICE, EMERGENCY_ONLY, POWER_OFF, UNKNOWN }

/**
 * `ServiceStateListener.onServiceStateChanged`.
 *
 * [emergencyOnly]: the adapter sets it when `getState()` is `STATE_EMERGENCY_ONLY`, or when no
 * `NetworkRegistrationInfo` in `getNetworkRegistrationInfoList()` `isRegistered()` while one lists
 * `SERVICE_TYPE_EMERGENCY` in `getAvailableServices()`. `ServiceState` has no public
 * `isEmergencyOnly()`. This is the source of truth for emergency-only: a SIM-less phone reports its
 * emergency-camped cell as registered. [operatorNumeric] and [operatorAlphaLong] are null without
 * location permission.
 */
data class ServiceStateSnapshot(
    val state: ServiceRegState,
    val emergencyOnly: Boolean,
    val operatorNumeric: String?,
    val operatorAlphaLong: String?,
    val roaming: Boolean?,
    override val observedWallMs: Long,
    override val observedElapsedMs: Long,
) : MeasurementInput

/** `TelephonyManager.DATA_*`. */
enum class DataConnState { DISCONNECTED, CONNECTING, CONNECTED, SUSPENDED, DISCONNECTING, HANDOVER_IN_PROGRESS, UNKNOWN }

/** `DataConnectionStateListener.onDataConnectionStateChanged(state, networkType)`. */
data class DataStateSnapshot(
    val state: DataConnState,
    /** `TelephonyManager.NETWORK_TYPE_*`. */
    val networkType: Int,
    override val observedWallMs: Long,
    override val observedElapsedMs: Long,
) : MeasurementInput

/** `DisplayInfoListener.onDisplayInfoChanged`: the 5G icon decision, not a measurement. */
data class DisplayInfoSnapshot(
    /** `TelephonyDisplayInfo.getNetworkType()`. */
    val networkType: Int,
    /** `TelephonyDisplayInfo.getOverrideNetworkType()`. */
    val overrideNetworkType: Int,
    override val observedWallMs: Long,
    override val observedElapsedMs: Long,
) : MeasurementInput

/**
 * `SignalStrengthsListener.onSignalStrengthsChanged`. Display only (Live tile and notification
 * between cell-info answers); never written to a session file. Stops screen-off on battery.
 */
data class SignalSnapshot(
    val lteRsrp: Int?,
    val lteRsrq: Int?,
    val lteRssnr: Int?,
    val nrSsRsrp: Int?,
    val nrSsRsrq: Int?,
    val nrSsSinr: Int?,
    val level: Int?,
    /** `SignalStrength.getTimestampMillis()`, ms since boot. */
    val modemTimestampMs: Long?,
    override val observedWallMs: Long,
    override val observedElapsedMs: Long,
) : MeasurementInput

/** The listeners and requests the telephony adapter registers, each separately. */
enum class RadioListener {
    CELL_INFO_REQUEST,
    CELL_INFO_PUSH,
    SIGNAL_STRENGTHS,
    SERVICE_STATE,
    DISPLAY_INFO,
    DATA_CONNECTION_STATE,

    /** Probe only: needs READ_PRECISE_PHONE_STATE, expected to be refused. */
    PHYSICAL_CHANNEL_CONFIG,

    /** Probe only: needs READ_PRECISE_PHONE_STATE, expected to be refused. */
    BARRING_INFO,

    /** Probe only: needs READ_PRECISE_PHONE_STATE, expected to be refused. */
    REGISTRATION_FAILED,
}

enum class ListenerOutcome {
    REGISTERED,

    /** A runtime permission the user can grant is missing (location, phone). */
    MISSING_PERMISSION,

    /** Refused with SecurityException for a permission no ordinary app can hold. */
    REFUSED_BY_PLATFORM,

    UNREGISTERED,
    FAILED,
}

/** How registering one listener went. One refused listener costs its columns, not the session. */
data class ListenerReport(
    val listener: RadioListener,
    val outcome: ListenerOutcome,
    val detail: String?,
    override val observedWallMs: Long,
    override val observedElapsedMs: Long,
) : MeasurementInput
