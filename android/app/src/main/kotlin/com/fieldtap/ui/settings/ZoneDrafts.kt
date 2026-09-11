package com.fieldtap.ui.settings

import com.fieldtap.core.input.FixSample
import com.fieldtap.core.privacy.PrivacyZone
import com.fieldtap.ui.setup.SetupFormats

/**
 * A privacy zone as typed in the zone editor. [id] is null for a new zone. Numbers are text, so a half-typed value
 * stays on screen; [toZone] turns what cannot be read into NaN, which `PrivacyZones.validate` reports.
 */
internal data class ZoneDraft(
    val id: String?,
    val label: String,
    val radius: String,
    val lat: String,
    val lon: String,
) {
    /** The zone to validate and save; a new zone gets [newId]. */
    fun toZone(newId: () -> String): PrivacyZone = PrivacyZone(
        id = id ?: newId(),
        label = label.trim(),
        lat = ZoneDrafts.parseDecimal(lat),
        lon = ZoneDrafts.parseDecimal(lon),
        radiusM = ZoneDrafts.parseDecimal(radius),
    )

    /** This draft centred on [lat], [lon], written with 6 decimals (about 0.1 m). */
    fun withPosition(lat: Double, lon: Double): ZoneDraft = copy(
        lat = SetupFormats.coordinate(lat, SetupFormats.EDIT_COORDINATE_DECIMALS),
        lon = SetupFormats.coordinate(lon, SetupFormats.EDIT_COORDINATE_DECIMALS),
    )

    companion object {
        /** A new zone with the default radius and no position. */
        fun blank(): ZoneDraft = ZoneDraft(
            id = null,
            label = "",
            radius = SetupFormats.metres(ZoneDrafts.DEFAULT_RADIUS_M),
            lat = "",
            lon = "",
        )

        /** An existing zone, for editing. */
        fun of(zone: PrivacyZone): ZoneDraft = ZoneDraft(
            id = zone.id,
            label = zone.label,
            radius = SetupFormats.metres(zone.radiusM),
            lat = SetupFormats.coordinate(zone.lat, SetupFormats.EDIT_COORDINATE_DECIMALS),
            lon = SetupFormats.coordinate(zone.lon, SetupFormats.EDIT_COORDINATE_DECIMALS),
        )
    }
}

/**
 * Zone editing rules, pure so they are unit-tested.
 *
 * Owner: workstream `ui-setup`.
 */
internal object ZoneDrafts {
    /** The radius a new zone starts with. */
    const val DEFAULT_RADIUS_M: Double = 200.0

    /** A fix older than this is not "here" any more. */
    const val MAX_FIX_AGE_MS: Long = 60_000

    /** An optional sign, digits and one decimal point; no exponent, "NaN" or "Infinity". */
    private val DECIMAL = Regex("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")

    /**
     * A typed decimal with a '.' or ',' separator and an optional ASCII or Unicode minus sign, surrounding spaces
     * ignored; NaN when it is not a plain decimal number.
     */
    fun parseDecimal(text: String): Double {
        val normalized = text.trim().replace(',', '.').replace(UNICODE_MINUS, '-')
        if (!DECIMAL.matches(normalized)) return Double.NaN
        return normalized.toDoubleOrNull() ?: Double.NaN
    }

    /** The typed radius, or [DEFAULT_RADIUS_M] while it cannot be read. */
    fun radiusOrDefault(text: String): Double = parseDecimal(text).takeIf { it.isFinite() } ?: DEFAULT_RADIUS_M

    /** [fix] is at most [MAX_FIX_AGE_MS] old at [nowElapsedMs], both on `elapsedRealtime`. */
    fun isRecent(fix: FixSample, nowElapsedMs: Long): Boolean = nowElapsedMs - fix.elapsedMs <= MAX_FIX_AGE_MS

    /** [zones] with [zone] replacing the zone of the same id in place, or added at the end. */
    fun upsert(zones: List<PrivacyZone>, zone: PrivacyZone): List<PrivacyZone> {
        val index = zones.indexOfFirst { it.id == zone.id }
        if (index < 0) return zones + zone
        return zones.toMutableList().also { it[index] = zone }
    }

    private const val UNICODE_MINUS = '−'
}
