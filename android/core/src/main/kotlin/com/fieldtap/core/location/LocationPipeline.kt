package com.fieldtap.core.location

import com.fieldtap.core.input.FixSample
import com.fieldtap.core.privacy.PrivacyZone
import com.fieldtap.format.EventRow
import com.fieldtap.format.LatLon
import com.fieldtap.format.TrackRow

/** What one fix produced. */
data class LocationStep(
    /** The track.csv row, or null when the fix was rejected or is inside a privacy zone. */
    val track: TrackRow?,
    /**
     * Events to write now: `privacy_zone` (always written, paused or not), and `gps_restored`
     * (written only when not paused).
     */
    val events: List<EventRow>,
    /** True when this fix moved logging into or out of a privacy zone. */
    val pauseChanged: Boolean,
)

/**
 * Everything location the session recorder needs. Single-threaded: session dispatcher only.
 *
 * Pause rule (privacy zones applied at write time): logging is paused from the first accepted fix
 * inside a zone until the first accepted fix outside every zone. Without any fix yet, logging is not
 * paused. While paused nothing is written to any file except the `privacy_zone` events. A fix inside
 * a zone is never added to the join buffer or the track.
 *
 * Owner: workstream `location-privacy-core`.
 */
interface LocationPipeline {
    fun onFix(fix: FixSample): LocationStep

    /** `gps_lost` when due; the recorder drops it while paused. */
    fun onTick(nowWallMs: Long, nowElapsedMs: Long): List<EventRow>

    fun join(measurementElapsedMs: Long, nowElapsedMs: Long): JoinResult

    fun joinFinal(measurementElapsedMs: Long): LatLon?

    val paused: Boolean

    /** `privacy.zone_pauses`: how many times logging paused. */
    val zonePauses: Int

    /** The newest accepted fix outside every zone, for the notification and Live screen. */
    fun lastFix(): FixSample?
}

/**
 * The production [LocationPipeline]: [FixSelector] -> [com.fieldtap.core.privacy.PrivacyZoneGate] ->
 * [FixJoiner], [GpsEventDeriver], [TrackRows].
 *
 * Tests: a walk into a zone and out again gives exactly two privacy_zone rows without coordinates,
 * no track rows between them, zonePauses 1, and joins that never use a fix from inside the zone.
 *
 * Owner: workstream `location-privacy-core`.
 */
class DefaultLocationPipeline(
    private val zones: List<PrivacyZone>,
    private val allowMockFixes: Boolean = false,
) : LocationPipeline {
    override fun onFix(fix: FixSample): LocationStep = TODO("location-privacy-core")

    override fun onTick(nowWallMs: Long, nowElapsedMs: Long): List<EventRow> = TODO("location-privacy-core")

    override fun join(measurementElapsedMs: Long, nowElapsedMs: Long): JoinResult = TODO("location-privacy-core")

    override fun joinFinal(measurementElapsedMs: Long): LatLon? = TODO("location-privacy-core")

    override val paused: Boolean get() = TODO("location-privacy-core")

    override val zonePauses: Int get() = TODO("location-privacy-core")

    override fun lastFix(): FixSample? = TODO("location-privacy-core")
}
