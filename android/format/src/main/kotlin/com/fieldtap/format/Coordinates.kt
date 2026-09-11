package com.fieldtap.format

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Coordinate text and precision rounding, shared by the writers and the export copy.
 *
 * - [format]: `%.7f` via `BigDecimal(v).setScale(7, HALF_EVEN).toPlainString()`; `0.0000000`, never
 *   `-0.0000000`.
 * - [approx110m]: SESSION-FORMAT.md "Location precision": `BigDecimal(v).setScale(3, HALF_EVEN)`, still
 *   written with 7 decimals (`38.8895123` -> `38.8900000`). The value returned is the rounded double.
 *
 * Owner: workstream `format`.
 */
object Coordinates {
    /** Digits after the point of every `lat` and `lon` (`%.7f`). */
    internal const val DECIMALS: Int = 7

    /** `session_json.approx_110m_decimals` in columns.json; guarded by SchemaDriftTest. */
    internal const val APPROX_110M_DECIMALS: Int = 3

    /**
     * A `lat` or `lon` field with exactly seven decimals. NaN and infinity, which no real fix has,
     * are blank; the CSV encoders then leave both coordinates blank.
     */
    fun format(value: Double): String = Csv.decimal(value, DECIMALS)

    /**
     * The coordinate rounded half-even to three decimals on its exact binary value. The result is
     * never negative zero, so it formats as `0.0000000`. NaN and infinity are returned unchanged.
     */
    fun approx110m(value: Double): Double {
        if (!value.isFinite()) return value
        val rounded = BigDecimal(value).setScale(APPROX_110M_DECIMALS, RoundingMode.HALF_EVEN).toDouble()
        return if (rounded == 0.0) 0.0 else rounded
    }

    /** Exactly 0,0: a fix with no real position, never written to track.csv. */
    fun isNullIsland(lat: Double, lon: Double): Boolean = lat == 0.0 && lon == 0.0
}
