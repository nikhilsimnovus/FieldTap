package com.fieldtap.core

import com.fieldtap.format.Csv

/**
 * Measurement values as Android reports them, turned into what the session files hold.
 * The platform adapters call [intOrNull] and [longOrNull] on every raw telephony value before it
 * enters a [com.fieldtap.core.input.CellSnapshot], so no sentinel ever reaches :core logic.
 *
 * Owner: workstream `radio-core`.
 */
object CellValues {
    /** The value of android.telephony.CellInfo.UNAVAILABLE: no value, never a measurement. */
    const val UNAVAILABLE: Int = Int.MAX_VALUE

    /** The value of android.telephony.CellInfo.UNAVAILABLE_LONG (an unavailable NR NCI). */
    const val UNAVAILABLE_LONG: Long = Long.MAX_VALUE

    /** The CSV cell for a raw Android value: blank when Android has no value. */
    fun csvCell(raw: Int): String = Csv.cell(raw.takeUnless { it == UNAVAILABLE })

    /** Null for `Integer.MAX_VALUE` and `Integer.MIN_VALUE` (both mean "unavailable" in telephony). */
    fun intOrNull(raw: Int): Int? = TODO("radio-core")

    /** Null for `Long.MAX_VALUE` and `Integer.MAX_VALUE` as a long. */
    fun longOrNull(raw: Long): Long? = TODO("radio-core")
}
