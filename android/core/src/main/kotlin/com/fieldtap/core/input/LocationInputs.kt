package com.fieldtap.core.input

import com.fieldtap.format.FixProvider

/*
 * Location inputs: what the location adapter (com.fieldtap.platform.location, workstream
 * `platform-adapters`) delivers. Shapes are frozen; see android/ARCHITECTURE.md.
 *
 * Owner: workstream `location-privacy-core`.
 */

/**
 * One `android.location.Location` from `LocationManager` (GPS, fused or network provider).
 *
 * - [elapsedMs]: `Location.getElapsedRealtimeMillis()`, the fix time on the monotonic clock; the
 *   GPS join uses only this. A reported time that is not positive, runs ahead of the callback, or is more than
 *   an hour older is not a time since boot (the API 31 emulator reports wall-clock nanoseconds); the adapter
 *   then uses the callback's elapsed time.
 * - [wallMs]: the same instant on the app's wall clock,
 *   `observedWallMs - (observedElapsedMs - elapsedMs)`. `track.csv` `time_utc` is this, not
 *   `Location.getTime()`, so track and measurement times share one clock.
 * - [accuracyM], [altitudeM], [speedMps]: null unless `hasAccuracy()`, `hasAltitude()`, `hasSpeed()`.
 * - [mock]: `Location.isMock()`.
 */
data class FixSample(
    val elapsedMs: Long,
    val wallMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val speedMps: Double?,
    val provider: FixProvider,
    val mock: Boolean,
    override val observedWallMs: Long,
    override val observedElapsedMs: Long,
) : MeasurementInput

/** `GnssStatus.Callback.onSatelliteStatusChanged`. Display only. */
data class GnssSnapshot(
    val satellitesVisible: Int,
    val satellitesUsedInFix: Int,
    override val observedWallMs: Long,
    override val observedElapsedMs: Long,
) : MeasurementInput

/** Location switched on or off, or providers appearing. Display and readiness only. */
data class LocationAvailability(
    val locationEnabled: Boolean,
    val preciseLocationGranted: Boolean,
    val providers: Set<FixProvider>,
    override val observedWallMs: Long,
    override val observedElapsedMs: Long,
) : MeasurementInput
