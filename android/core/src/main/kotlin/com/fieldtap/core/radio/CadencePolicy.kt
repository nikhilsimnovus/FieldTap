package com.fieldtap.core.radio

import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.format.Schema

/**
 * Android's cell-info refresh interval and the KPI age limits that follow from it
 * (docs/APP-PLAN.md, "The Android facts this rests on").
 *
 * - Short interval (2 s) when `screenOn && (!wifiConnected || charging)`; otherwise long (10 s).
 * - kpi.csv accepts a fresh serving sample at most 2500 ms old on the short interval and at most
 *   11000 ms old on the long one.
 * - The app asks for an update every [REQUEST_PERIOD_MS]; inside Android's interval the answer is
 *   the cached list, which the [FreshnessEngine] marks stale.
 *
 * Tests: all eight combinations of the three booleans.
 *
 * Owner: workstream `radio-core`.
 */
object CadencePolicy {
    const val REQUEST_PERIOD_MS: Long = 1_000
    const val SHORT_INTERVAL_MS: Long = 2_000
    const val LONG_INTERVAL_MS: Long = 10_000

    /** `kpi.max_age_ms_short_interval` in schema/columns.json, guarded by :format's SchemaDriftTest. */
    const val SHORT_MAX_AGE_MS: Long = Schema.KPI_MAX_AGE_MS_SHORT_INTERVAL

    /** `kpi.max_age_ms` in schema/columns.json, guarded by :format's SchemaDriftTest. */
    const val LONG_MAX_AGE_MS: Long = Schema.KPI_MAX_AGE_MS

    /** True when Android refreshes cell info every 2 s: the screen is on, and Wi-Fi is off or the phone charges. */
    fun isShortInterval(conditions: DeviceConditions): Boolean =
        conditions.screenOn && (!conditions.wifiConnected || conditions.charging)

    /** [SHORT_INTERVAL_MS] or [LONG_INTERVAL_MS] for [conditions]. */
    fun intervalMs(conditions: DeviceConditions): Long =
        if (isShortInterval(conditions)) SHORT_INTERVAL_MS else LONG_INTERVAL_MS

    /** The oldest sample kpi.csv accepts under [conditions]: [SHORT_MAX_AGE_MS] or [LONG_MAX_AGE_MS]. */
    fun maxKpiAgeMs(conditions: DeviceConditions): Long =
        if (isShortInterval(conditions)) SHORT_MAX_AGE_MS else LONG_MAX_AGE_MS
}
