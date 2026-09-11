package com.fieldtap.core.radio

import com.fieldtap.core.input.DisplayInfoSnapshot

/**
 * Names for Android's integer network types, without their prefixes, for event details and
 * `handset.network_type`. Pure: the integers are Android's public constant values.
 *
 * - [networkType]: `TelephonyManager.NETWORK_TYPE_*` without `NETWORK_TYPE_`: 0 `UNKNOWN`, 1 `GPRS`,
 *   2 `EDGE`, 3 `UMTS`, 4 `CDMA`, 5 `EVDO_0`, 6 `EVDO_A`, 7 `1xRTT`, 8 `HSDPA`, 9 `HSUPA`, 10 `HSPA`,
 *   11 `IDEN`, 12 `EVDO_B`, 13 `LTE`, 14 `EHRPD`, 15 `HSPAP`, 16 `GSM`, 17 `TD_SCDMA`, 18 `IWLAN`,
 *   20 `NR`; anything else `UNKNOWN`. Verify the table against the SDK's android.jar in the test.
 * - [overrideType]: `TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_*` without the prefix: 0 `NONE`,
 *   1 `LTE_CA`, 2 `LTE_ADVANCED_PRO`, 3 `NR_NSA`, 4 `NR_NSA_MMWAVE`, 5 `NR_ADVANCED`.
 * - [shows5g]: override is NR_NSA, NR_NSA_MMWAVE or NR_ADVANCED, or the network type is NR.
 *
 * Owner: workstream `radio-core`.
 */
object NetworkTypeNames {
    fun networkType(type: Int): String = TODO("radio-core")

    fun overrideType(type: Int): String = TODO("radio-core")

    fun shows5g(info: DisplayInfoSnapshot): Boolean = TODO("radio-core")
}
