package com.fieldtap.ui.theme

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Number formatting for display. These return numbers only (plus SI unit symbols for sizes); every
 * word around them ("2.1 s old", "Recording") comes from the screen's string resources.
 *
 * Digits are always ASCII so values line up with the report and the CSV files; the decimal
 * separator follows [Locale].
 */
object Formats {
    /**
     * A sample age in seconds for "%s s old": one decimal below 9.95 s ("0.4", "2.1", "9.9"), whole
     * seconds from there ("10", "75"). Rounds half up. Negative ages show as "0.0".
     */
    fun ageSeconds(ageMs: Long, locale: Locale = Locale.getDefault()): String {
        val ms = ageMs.coerceAtLeast(0)
        val tenths = (ms + 50) / 100
        if (tenths < 100) {
            return "${tenths / 10}${decimalSeparator(locale)}${tenths % 10}"
        }
        return ((ms + 500) / 1000).toString()
    }

    /** Elapsed time as "m:ss" below an hour ("0:05", "12:34") and "h:mm:ss" from there ("1:02:03"). */
    fun elapsed(elapsedMs: Long): String {
        val totalSeconds = elapsedMs.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val ss = seconds.toString().padStart(2, '0')
        return if (hours > 0) {
            "$hours:${minutes.toString().padStart(2, '0')}:$ss"
        } else {
            "$minutes:$ss"
        }
    }

    /**
     * A size in decimal SI units, matching the storage cap's "2 GB": "512 B", "4.2 kB", "12.3 MB",
     * "250 MB", "1.9 GB". One decimal below 100 of a unit, whole numbers from there.
     */
    fun decimalBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val b = bytes.coerceAtLeast(0)
        if (b < 1000) return "$b B"
        val units = arrayOf("kB", "MB", "GB", "TB")
        var unit = 0
        var value = b / 1000.0
        // From 999.5 of a unit the whole-number display would round to 1000, so move up a unit.
        while (value >= 999.5 && unit < units.lastIndex) {
            value /= 1000.0
            unit++
        }
        val text = if (value < 99.95) {
            oneDecimal(value, locale)
        } else {
            BigDecimal(value).setScale(0, RoundingMode.HALF_UP).toPlainString()
        }
        return "$text ${units[unit]}"
    }

    /** One decimal, half up, never "-0.0": 88.25 is "88.3", -0.04 is "0.0". */
    fun oneDecimal(value: Double, locale: Locale = Locale.getDefault()): String {
        require(!value.isNaN() && !value.isInfinite()) { "not a finite number: $value" }
        val text = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString()
        val separator = decimalSeparator(locale)
        return if (separator == '.') text else text.replace('.', separator)
    }

    private fun decimalSeparator(locale: Locale): Char = DecimalFormatSymbols.getInstance(locale).decimalSeparator
}
