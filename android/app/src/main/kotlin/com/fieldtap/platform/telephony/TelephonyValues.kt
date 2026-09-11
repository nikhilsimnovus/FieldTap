package com.fieldtap.platform.telephony

import com.fieldtap.core.CellValues
import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.DataConnState
import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.RadioListener
import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.probe.ProbeNotes
import com.fieldtap.core.radio.NetworkTypeNames
import com.fieldtap.format.Rat

/**
 * One `android.telephony.CellInfo` exactly as Android returned it, sentinels included. [CellInfoMapper]
 * fills it from the platform classes and [toSnapshot] applies every rule, so the rules are unit-tested
 * without Android. The defaults are Android's "unavailable" values, which is what a RAT without the
 * field reports.
 *
 * Owner: workstream `platform-adapters`.
 */
internal data class RawCell(
    val rat: Rat,
    val registered: Boolean,
    /** `getCellConnectionStatus()`; `CONNECTION_UNKNOWN` is `Int.MAX_VALUE`. */
    val connectionStatus: Int,
    val timestampMs: Long,
    val mcc: String? = null,
    val mnc: String? = null,
    val operatorLong: CharSequence? = null,
    val operatorShort: CharSequence? = null,
    val pci: Int = CellValues.UNAVAILABLE,
    val arfcn: Int = CellValues.UNAVAILABLE,
    val bands: List<Int> = emptyList(),
    val tac: Int = CellValues.UNAVAILABLE,
    /** A long NCI, or an int cell id widened to long (so an unavailable int id is `Int.MAX_VALUE`). */
    val cellId: Long = CellValues.UNAVAILABLE_LONG,
    val bandwidthKhz: Int = CellValues.UNAVAILABLE,
    val additionalPlmns: Collection<String?> = emptyList(),
    val rsrp: Int = CellValues.UNAVAILABLE,
    val rsrq: Int = CellValues.UNAVAILABLE,
    val sinr: Int = CellValues.UNAVAILABLE,
    val csiRsrp: Int = CellValues.UNAVAILABLE,
    val csiRsrq: Int = CellValues.UNAVAILABLE,
    val csiSinr: Int = CellValues.UNAVAILABLE,
    val rssi: Int = CellValues.UNAVAILABLE,
    val level: Int = CellValues.UNAVAILABLE,
    val cqi: Int = CellValues.UNAVAILABLE,
    val timingAdvance: Int = CellValues.UNAVAILABLE,
) {
    /**
     * The :core value: every sentinel through [CellValues], blank strings null, band sentinels dropped
     * (Android's order kept), additional PLMNs without blanks or duplicates and sorted, because Android
     * hands them over as an unordered set.
     */
    fun toSnapshot(): CellSnapshot = CellSnapshot(
        rat = rat,
        registered = registered,
        connectionStatus = CellValues.intOrNull(connectionStatus),
        timestampMs = timestampMs,
        mcc = TelephonyValues.text(mcc),
        mnc = TelephonyValues.text(mnc),
        operatorLong = TelephonyValues.text(operatorLong),
        operatorShort = TelephonyValues.text(operatorShort),
        pci = CellValues.intOrNull(pci),
        arfcn = CellValues.intOrNull(arfcn),
        bands = bands.mapNotNull { CellValues.intOrNull(it) },
        tac = CellValues.intOrNull(tac),
        cellId = CellValues.longOrNull(cellId),
        bandwidthKhz = CellValues.intOrNull(bandwidthKhz),
        additionalPlmns = TelephonyValues.plmns(additionalPlmns),
        rsrp = CellValues.intOrNull(rsrp),
        rsrq = CellValues.intOrNull(rsrq),
        sinr = CellValues.intOrNull(sinr),
        csiRsrp = CellValues.intOrNull(csiRsrp),
        csiRsrq = CellValues.intOrNull(csiRsrq),
        csiSinr = CellValues.intOrNull(csiSinr),
        rssi = CellValues.intOrNull(rssi),
        level = CellValues.intOrNull(level),
        cqi = CellValues.intOrNull(cqi),
        timingAdvance = CellValues.intOrNull(timingAdvance),
    )
}

/**
 * The pure rules behind the telephony adapter. Android's constant values are copied as literals, checked
 * against the android-37.0 android.jar and pinned by TelephonyValuesTest, so no constant newer than API 31
 * is referenced from code that runs on API 31.
 *
 * Owner: workstream `platform-adapters`.
 */
internal object TelephonyValues {
    /** `ServiceState.STATE_*`. */
    const val STATE_IN_SERVICE: Int = 0
    const val STATE_OUT_OF_SERVICE: Int = 1
    const val STATE_EMERGENCY_ONLY: Int = 2
    const val STATE_POWER_OFF: Int = 3

    /** `TelephonyManager.DATA_*`; `DATA_HANDOVER_IN_PROGRESS` is API 33. */
    const val DATA_UNKNOWN: Int = -1
    const val DATA_DISCONNECTED: Int = 0
    const val DATA_CONNECTING: Int = 1
    const val DATA_CONNECTED: Int = 2
    const val DATA_SUSPENDED: Int = 3
    const val DATA_DISCONNECTING: Int = 4
    const val DATA_HANDOVER_IN_PROGRESS: Int = 5

    /** `NetworkRegistrationInfo.SERVICE_TYPE_EMERGENCY`. */
    const val SERVICE_TYPE_EMERGENCY: Int = 5

    /** `AccessNetworkConstants.TRANSPORT_TYPE_WWAN`: the cellular registrations, not IWLAN. */
    const val TRANSPORT_TYPE_WWAN: Int = 1

    /** `TelephonyManager.CellInfoCallback.ERROR_*`. */
    const val ERROR_TIMEOUT: Int = 1
    const val ERROR_MODEM_ERROR: Int = 2

    /** `TelephonyManager.NETWORK_TYPE_UNKNOWN`. */
    const val NETWORK_TYPE_UNKNOWN: Int = 0

    /** `Build.UNKNOWN`: what `Build` fields hold when the property is not set. */
    const val BUILD_UNKNOWN: String = "unknown"

    private const val MAX_DETAIL_LENGTH = 120
    private const val UNKNOWN_NETWORK_NAME = "UNKNOWN"
    private val PRIVILEGED_PERMISSIONS = listOf(
        "READ_PRECISE_PHONE_STATE",
        "READ_PRIVILEGED_PHONE_STATE",
        "MODIFY_PHONE_STATE",
    )
    private val PERMISSION_NAME = Regex("android\\.permission\\.([A-Z_]+)")

    /** `ServiceState.getState()` -> [ServiceRegState]. */
    fun regState(state: Int): ServiceRegState = when (state) {
        STATE_IN_SERVICE -> ServiceRegState.IN_SERVICE
        STATE_OUT_OF_SERVICE -> ServiceRegState.OUT_OF_SERVICE
        STATE_EMERGENCY_ONLY -> ServiceRegState.EMERGENCY_ONLY
        STATE_POWER_OFF -> ServiceRegState.POWER_OFF
        else -> ServiceRegState.UNKNOWN
    }

    /**
     * Emergency-only service, since `ServiceState` has no public `isEmergencyOnly()`: `getState()` says so,
     * or no cellular (WWAN) registration `isRegistered()` while a cellular registration lists
     * `SERVICE_TYPE_EMERGENCY`. Only WWAN counts, so Wi-Fi calling over IWLAN does not hide an
     * emergency-camped radio.
     */
    fun emergencyOnly(state: Int, wwanRegistered: Boolean, wwanEmergencyAvailable: Boolean): Boolean =
        state == STATE_EMERGENCY_ONLY || (!wwanRegistered && wwanEmergencyAvailable)

    /** `getRoaming()` only means something in service; otherwise unknown. */
    fun roaming(state: Int, roaming: Boolean): Boolean? = if (state == STATE_IN_SERVICE) roaming else null

    /** `TelephonyManager.DATA_*` -> [DataConnState]. */
    fun dataConnState(state: Int): DataConnState = when (state) {
        DATA_DISCONNECTED -> DataConnState.DISCONNECTED
        DATA_CONNECTING -> DataConnState.CONNECTING
        DATA_CONNECTED -> DataConnState.CONNECTED
        DATA_SUSPENDED -> DataConnState.SUSPENDED
        DATA_DISCONNECTING -> DataConnState.DISCONNECTING
        DATA_HANDOVER_IN_PROGRESS -> DataConnState.HANDOVER_IN_PROGRESS
        else -> DataConnState.UNKNOWN
    }

    /** Android text as a string; blank becomes null. The text itself is kept as Android gave it. */
    fun text(value: CharSequence?): String? = value?.toString()?.takeUnless { it.isBlank() }

    /** A `Build` field: blank or [BUILD_UNKNOWN] becomes null, so the handset key is left out. */
    fun buildValue(value: String?): String? = value?.takeUnless { it.isBlank() || it == BUILD_UNKNOWN }

    /** Additional PLMNs without nulls, blanks or duplicates, sorted for a stable file. */
    fun plmns(raw: Collection<String?>): List<String> =
        raw.filterNotNull().filter { it.isNotBlank() }.distinct().sorted()

    /**
     * `handset.network_type`: the name of [reported] (`getDataNetworkType()`), else of [fallback]; null when
     * neither is a known type.
     */
    fun networkTypeName(reported: Int?, fallback: Int?): String? {
        val type = reported?.takeUnless { it == NETWORK_TYPE_UNKNOWN }
            ?: fallback?.takeUnless { it == NETWORK_TYPE_UNKNOWN }
            ?: return null
        return NetworkTypeNames.networkType(type).takeUnless { it == UNKNOWN_NETWORK_NAME }
    }

    /**
     * How a `SecurityException` from registering [listener] is reported: the three restricted listeners, or
     * a message naming a privileged permission, are [ListenerOutcome.REFUSED_BY_PLATFORM]; anything else is a
     * runtime permission the user can grant, [ListenerOutcome.MISSING_PERMISSION].
     */
    fun outcomeForSecurityException(listener: RadioListener, message: String?): ListenerOutcome {
        val privileged = message != null && PRIVILEGED_PERMISSIONS.any { message.contains(it) }
        return if (privileged || listener in ProbeNotes.RESTRICTED) {
            ListenerOutcome.REFUSED_BY_PLATFORM
        } else {
            ListenerOutcome.MISSING_PERMISSION
        }
    }

    /**
     * A `SecurityException` message reduced to the permissions it names (`requires READ_PHONE_STATE`), or
     * `permission denied`. Android's message also carries a uid and a package, which never leave the adapter.
     */
    fun securityDetail(message: String?): String {
        val names = if (message == null) {
            emptyList<String>()
        } else {
            PERMISSION_NAME.findAll(message).map { it.groupValues[1] }.distinct().toList()
        }
        return if (names.isEmpty()) "permission denied" else "requires ${names.joinToString(" and ")}"
    }

    /** `IllegalStateException: telephony service is null`, the message trimmed and cut at 120 characters. */
    fun failureDetail(exceptionName: String, message: String?): String {
        val extra = message?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_DETAIL_LENGTH)
        return if (extra == null) exceptionName else "$exceptionName: $extra"
    }

    /** `CellInfoCallback.onError` as `timeout`, `modem error` or `error <code>`, plus the cause when given. */
    fun cellInfoErrorDetail(errorCode: Int, cause: String?): String {
        val base = when (errorCode) {
            ERROR_TIMEOUT -> "timeout"
            ERROR_MODEM_ERROR -> "modem error"
            else -> "error $errorCode"
        }
        return failureDetail(base, cause)
    }
}

/**
 * Remembers the outcome and detail last reported for each listener, so the telephony adapter emits a
 * `ListenerReport` only when something changed, not on every request period. Not thread-safe: the adapter
 * calls it from its producer coroutine only.
 *
 * Owner: workstream `platform-adapters`.
 */
internal class ListenerReportGate {
    private val last = HashMap<RadioListener, Pair<ListenerOutcome, String?>>()

    /** True, and remembered, when [outcome] and [detail] differ from what was last reported for [listener]. */
    fun shouldReport(listener: RadioListener, outcome: ListenerOutcome, detail: String?): Boolean {
        val attempt = outcome to detail
        if (last[listener] == attempt) return false
        last[listener] = attempt
        return true
    }
}

/**
 * Where the push listener (`CellInfoListener`) stands during one collection of the telephony inputs.
 *
 * Owner: workstream `platform-adapters`.
 */
internal enum class PushListenerState {
    /** Not registered: tried again every request period, so granting the permissions takes effect at once. */
    WAITING,

    REGISTERED,

    /** Android failed or refused it for a reason no permission fixes; not tried again in this collection. */
    ABANDONED,
    ;

    companion object {
        /** The state after one registration attempt ended in [outcome]. */
        fun after(outcome: ListenerOutcome): PushListenerState = when (outcome) {
            ListenerOutcome.REGISTERED -> REGISTERED
            ListenerOutcome.FAILED, ListenerOutcome.REFUSED_BY_PLATFORM -> ABANDONED
            ListenerOutcome.MISSING_PERMISSION, ListenerOutcome.UNREGISTERED -> WAITING
        }
    }
}
