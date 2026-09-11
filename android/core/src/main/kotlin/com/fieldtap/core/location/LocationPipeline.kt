package com.fieldtap.core.location

import com.fieldtap.core.input.FixSample
import com.fieldtap.core.privacy.PrivacyZone
import com.fieldtap.core.privacy.PrivacyZoneGate
import com.fieldtap.core.privacy.ZonePlacement
import com.fieldtap.format.EventRow
import com.fieldtap.format.LatLon
import com.fieldtap.format.TrackRow

/** What one fix produced. */
data class LocationStep(
    /** The track.csv row, or null when the fix was rejected, is inside a privacy zone, or may be inside one. */
    val track: TrackRow?,
    /**
     * Events to write now: `privacy_zone` (always written, paused or not), and `gps_restored`
     * (written only when not paused).
     */
    val events: List<EventRow>,
    /** True when this fix moved logging into or out of a privacy zone. */
    val pauseChanged: Boolean,
    /**
     * True when this fix was accepted and shows the phone outside every zone: whatever was held until now was
     * taken outside them and may be written.
     */
    val confirmsOutside: Boolean = false,
)

/**
 * Everything location the session recorder needs. Single-threaded: session dispatcher only.
 *
 * Pause rule (privacy zones applied at write time): logging is paused from the first accepted fix
 * inside a zone until the first accepted fix outside every zone. While paused nothing is written to any file
 * except the `privacy_zone` events. A fix inside a zone, or one whose accuracy reaches into a zone, is never
 * added to the join buffer or the track.
 *
 * Hold rule, when the session has zones: [holding] says that no fix yet shows where the inputs arriving now are
 * taken (before the first fix, after a fix near a zone, after a fix that may be inside one). The recorder keeps
 * those inputs back. The next fix outside every zone ([LocationStep.confirmsOutside]) lets them be written; a
 * fix inside a zone, or a hold that outlasts its limit ([onTick] then pauses), drops them. A session without
 * zones never holds.
 *
 * Owner: workstream `location-privacy-core`.
 */
interface LocationPipeline {
    fun onFix(fix: FixSample): LocationStep

    /** `gps_lost` when due, and the `privacy_zone` pause that ends an overlong hold; the recorder drops gps events while paused. */
    fun onTick(nowWallMs: Long, nowElapsedMs: Long): List<EventRow>

    fun join(measurementElapsedMs: Long, nowElapsedMs: Long): JoinResult

    fun joinFinal(measurementElapsedMs: Long): LatLon?

    val paused: Boolean

    /** True while inputs must wait for a fix that shows where they were taken; never while [paused]. */
    val holding: Boolean get() = false

    /** True while [paused] because no fix showed where the phone is, rather than because a fix was inside a zone. */
    val pausedWithoutFix: Boolean get() = false

    /** How long inputs have been held at [nowElapsedMs]; null when not [holding]. */
    fun holdAgeMs(nowElapsedMs: Long): Long? = null

    /** `privacy.zone_pauses`: how many times logging paused inside a zone. */
    val zonePauses: Int

    /** The newest accepted fix outside every zone, for the notification and Live screen. */
    fun lastFix(): FixSample?
}

/**
 * The production [LocationPipeline]: [FixSelector] -> [PrivacyZoneGate] -> [FixJoiner],
 * [GpsEventDeriver], [TrackRows].
 *
 * For each fix, in this order:
 * 1. [FixSelector] rejects mock (unless [allowMockFixes]), 0,0, out-of-range, out-of-order and
 *    redundant fallback fixes. A rejected fix produces nothing and changes no state.
 * 2. [PrivacyZoneGate] places the fix, decides the pause and the hold, and yields the `privacy_zone` event, if any.
 * 3. [GpsEventDeriver] sees every accepted fix, inside a zone or not; its `gps_restored` is kept only
 *    when logging is not paused after this fix.
 * 4. Only a fix outside every zone ([ZonePlacement.NEAR] or [ZonePlacement.CLEAR]) becomes a track row, a join
 *    candidate and [lastFix]; it is the fix that [LocationStep.confirmsOutside].
 *
 * [onTick] returns `gps_lost` when due, then the gate's pause for a hold older than [holdLimitMs].
 *
 * The zones are copied at construction: a session keeps the zones it started with.
 *
 * Tests: a walk into a zone and out again gives exactly two privacy_zone rows without coordinates,
 * no track rows between them, zonePauses 1, and joins that never use a fix from inside the zone.
 *
 * Owner: workstream `location-privacy-core`.
 */
class DefaultLocationPipeline(
    zones: List<PrivacyZone>,
    private val allowMockFixes: Boolean = false,
    holdLimitMs: Long = PrivacyZoneGate.HOLD_LIMIT_MS,
) : LocationPipeline {
    private val selector = FixSelector(allowMock = allowMockFixes)
    private val gate = PrivacyZoneGate(zones.toList(), holdLimitMs)
    private val joiner = FixJoiner()
    private val gpsEvents = GpsEventDeriver()
    private var newestOutside: FixSample? = null

    override fun onFix(fix: FixSample): LocationStep {
        if (!selector.accept(fix)) return REJECTED

        val zoneEvent = gate.onFix(fix)
        val gpsEvent = gpsEvents.onFix(fix)
        val paused = gate.paused
        val events = when {
            zoneEvent != null && gpsEvent != null && !paused -> listOf(zoneEvent, gpsEvent)
            zoneEvent != null -> listOf(zoneEvent)
            gpsEvent != null && !paused -> listOf(gpsEvent)
            else -> emptyList()
        }
        val outside = !paused && (gate.lastPlacement == ZonePlacement.NEAR || gate.lastPlacement == ZonePlacement.CLEAR)
        if (!outside) return LocationStep(track = null, events = events, pauseChanged = zoneEvent != null)

        joiner.add(fix)
        newestOutside = fix
        return LocationStep(track = TrackRows.of(fix), events = events, pauseChanged = zoneEvent != null, confirmsOutside = true)
    }

    override fun onTick(nowWallMs: Long, nowElapsedMs: Long): List<EventRow> {
        val lost = gpsEvents.onTick(nowWallMs, nowElapsedMs)
        val pause = gate.onTick(nowWallMs, nowElapsedMs)
        return listOfNotNull(lost, pause)
    }

    override fun join(measurementElapsedMs: Long, nowElapsedMs: Long): JoinResult =
        joiner.join(measurementElapsedMs, nowElapsedMs)

    override fun joinFinal(measurementElapsedMs: Long): LatLon? = joiner.joinFinal(measurementElapsedMs)

    override val paused: Boolean get() = gate.paused

    override val holding: Boolean get() = gate.holding

    override val pausedWithoutFix: Boolean get() = gate.pausedWithoutFix

    override fun holdAgeMs(nowElapsedMs: Long): Long? = gate.holdAgeMs(nowElapsedMs)

    override val zonePauses: Int get() = gate.pauses

    override fun lastFix(): FixSample? = newestOutside

    private companion object {
        val REJECTED = LocationStep(track = null, events = emptyList(), pauseChanged = false)
    }
}
