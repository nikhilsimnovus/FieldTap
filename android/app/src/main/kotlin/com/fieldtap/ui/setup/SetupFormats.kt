package com.fieldtap.ui.setup

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Dates, times and distances as the setup screens show them. The words around them come from string resources;
 * numbers that change while watched use `com.fieldtap.ui.theme.Formats`.
 *
 * Owner: workstream `ui-setup`.
 */
internal object SetupFormats {
    /** A calendar date in the user's locale and time zone, for example "10 Sept 2026" or "10.09.2026". */
    fun date(utcMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(utcMs))

    /** A time of day in the user's locale and time zone, for example "14:32". */
    fun time(utcMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(utcMs))

    /** A date and time of day, for example "10.09.2026, 14:32". */
    fun dateTime(utcMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone)
            .format(Instant.ofEpochMilli(utcMs))

    /** Metres with at most one decimal and no trailing zero: 200.0 is "200", 150.55 is "150.6". Not finite: the plain value. */
    fun metres(value: Double): String {
        if (!value.isFinite()) return value.toString()
        val rounded = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros()
        return if (rounded.signum() == 0) "0" else rounded.toPlainString()
    }

    /**
     * A coordinate for display with [decimals] decimals (5 is about 1 m), a '.' separator in every locale so it
     * can be typed back, and never "-0". Not finite: the plain value.
     */
    fun coordinate(value: Double, decimals: Int = DISPLAY_COORDINATE_DECIMALS): String {
        if (!value.isFinite()) return value.toString()
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).toPlainString()
    }

    /** About 1 m. */
    const val DISPLAY_COORDINATE_DECIMALS: Int = 5

    /** About 0.1 m, for coordinates written into an editable field. */
    const val EDIT_COORDINATE_DECIMALS: Int = 6
}
