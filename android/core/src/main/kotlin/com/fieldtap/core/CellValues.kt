package com.fieldtap.core

import com.fieldtap.format.Csv

/** Measurement values as Android reports them, turned into what the session files hold. */
object CellValues {
    /** The value of android.telephony.CellInfo.UNAVAILABLE: no value, never a measurement. */
    const val UNAVAILABLE: Int = Int.MAX_VALUE

    /** The CSV cell for a raw Android value: blank when Android has no value. */
    fun csvCell(raw: Int): String = Csv.cell(raw.takeUnless { it == UNAVAILABLE })
}
