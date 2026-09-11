package com.fieldtap.ui.common

import com.fieldtap.ui.theme.Formats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Times and small numbers for display, in the phone's zone and locale. Session files keep UTC; only the
 * screens convert. Pure, so it is unit-tested with a fixed zone and locale.
 *
 * Owner: workstream `ui-session`.
 */
object DisplayTime {
    /** "10 Sep 2026, 14:30" in [locale]'s medium date and short time. */
    fun dateTime(utcMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone)
            .format(Instant.ofEpochMilli(utcMs))

    /** "14:30" in [locale]'s short time. */
    fun time(utcMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone)
            .format(Instant.ofEpochMilli(utcMs))

    /** "14:30:05" in [locale]'s medium time, for events that seconds matter to. */
    fun timeWithSeconds(utcMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(zone)
            .format(Instant.ofEpochMilli(utcMs))

    /** Milliseconds as seconds with one decimal, half up: 2000 is "2.0", 14050 is "14.1". */
    fun seconds(ms: Long, locale: Locale = Locale.getDefault()): String = Formats.oneDecimal(ms / 1000.0, locale)

    /** A share with one decimal ("88.3"), or null when it is unknown or not a finite number. */
    fun percent(value: Double?, locale: Locale = Locale.getDefault()): String? =
        value?.takeIf { it.isFinite() }?.let { Formats.oneDecimal(it, locale) }
}
