package com.fieldtap.format

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
    fun format(value: Double): String = TODO("format")

    fun approx110m(value: Double): Double = TODO("format")

    /** Exactly 0,0: a fix with no real position, never written to track.csv. */
    fun isNullIsland(lat: Double, lon: Double): Boolean = TODO("format")
}
