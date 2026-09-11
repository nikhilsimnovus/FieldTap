package com.fieldtap.core.radio

import com.fieldtap.core.input.DeviceConditions

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
    const val SHORT_MAX_AGE_MS: Long = 2_500
    const val LONG_MAX_AGE_MS: Long = 11_000

    fun isShortInterval(conditions: DeviceConditions): Boolean = TODO("radio-core")

    fun intervalMs(conditions: DeviceConditions): Long = TODO("radio-core")

    fun maxKpiAgeMs(conditions: DeviceConditions): Long = TODO("radio-core")
}
