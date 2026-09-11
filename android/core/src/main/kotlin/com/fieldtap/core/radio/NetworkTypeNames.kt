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
 *   1 `LTE_CA`, 2 `LTE_ADVANCED_PRO`, 3 `NR_NSA`, 4 `NR_NSA_MMWAVE`, 5 `NR_ADVANCED`; anything else
 *   `UNKNOWN`.
 * - [shows5g]: override is NR_NSA, NR_NSA_MMWAVE or NR_ADVANCED, or the network type is NR.
 *
 * The values were checked with `javap -constants` against platforms/android-37.0/android.jar;
 * NetworkTypeNamesTest repeats that check against the newest installed platform.
 *
 * Owner: workstream `radio-core`.
 */
object NetworkTypeNames {
    /** The name of a value outside Android's table. */
    const val UNKNOWN: String = "UNKNOWN"

    /** `TelephonyManager.NETWORK_TYPE_LTE`. */
    const val NETWORK_TYPE_LTE: Int = 13

    /** `TelephonyManager.NETWORK_TYPE_NR`. */
    const val NETWORK_TYPE_NR: Int = 20

    /** `TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA`. */
    const val OVERRIDE_NR_NSA: Int = 3

    /** `TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE`. */
    const val OVERRIDE_NR_NSA_MMWAVE: Int = 4

    /** `TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED`. */
    const val OVERRIDE_NR_ADVANCED: Int = 5

    private val NETWORK_TYPES: Map<Int, String> = mapOf(
        0 to UNKNOWN,
        1 to "GPRS",
        2 to "EDGE",
        3 to "UMTS",
        4 to "CDMA",
        5 to "EVDO_0",
        6 to "EVDO_A",
        7 to "1xRTT",
        8 to "HSDPA",
        9 to "HSUPA",
        10 to "HSPA",
        11 to "IDEN",
        12 to "EVDO_B",
        NETWORK_TYPE_LTE to "LTE",
        14 to "EHRPD",
        15 to "HSPAP",
        16 to "GSM",
        17 to "TD_SCDMA",
        18 to "IWLAN",
        NETWORK_TYPE_NR to "NR",
    )

    private val OVERRIDE_TYPES: Map<Int, String> = mapOf(
        0 to "NONE",
        1 to "LTE_CA",
        2 to "LTE_ADVANCED_PRO",
        OVERRIDE_NR_NSA to "NR_NSA",
        OVERRIDE_NR_NSA_MMWAVE to "NR_NSA_MMWAVE",
        OVERRIDE_NR_ADVANCED to "NR_ADVANCED",
    )

    /** The `NETWORK_TYPE_*` name of [type] without its prefix, or [UNKNOWN]. */
    fun networkType(type: Int): String = NETWORK_TYPES[type] ?: UNKNOWN

    /** The `OVERRIDE_NETWORK_TYPE_*` name of [type] without its prefix, or [UNKNOWN]. */
    fun overrideType(type: Int): String = OVERRIDE_TYPES[type] ?: UNKNOWN

    /** Whether the status bar shows a 5G icon for [info]. An indicator, not a measurement. */
    fun shows5g(info: DisplayInfoSnapshot): Boolean =
        info.overrideNetworkType == OVERRIDE_NR_NSA ||
            info.overrideNetworkType == OVERRIDE_NR_NSA_MMWAVE ||
            info.overrideNetworkType == OVERRIDE_NR_ADVANCED ||
            info.networkType == NETWORK_TYPE_NR
}
