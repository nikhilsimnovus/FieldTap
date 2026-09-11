package com.fieldtap.format

/**
 * Field and record rules shared by every CSV in a session: the bytes Python's `csv` module writes
 * with its default dialect (docs/SESSION-FORMAT.md, "CSV bytes" and "Numbers").
 * Blank means unknown, never `null`, `NaN`, `N/A` or 2147483647.
 *
 * Tests (workstream `format`) must cover: quoting of `,` `"` CR LF only, doubled quotes; CR and LF in
 * text turned into spaces; `decimal(0.0625, 3)` is `0.062` (HALF_EVEN on the exact binary value,
 * where `String.format` gives `0.063`); `-0.04` at one decimal is `0.0`, never `-0.0`; a German
 * default locale changes nothing; `epoch(1789050600400)` is `1789050600.400`; `list` of two bands
 * is `66, 2` and is quoted by [record]; [parseRecord] inverts [record].
 *
 * Owner: workstream `format`.
 */
object Csv {
    /** Every record, the header and the last row included, ends in CR LF. */
    const val LINE_END: String = "\r\n"

    fun cell(value: Int?): String = value?.toString() ?: ""

    /**
     * One record: fields joined with `,`, a field quoted only when it contains `,`, `"`, CR or LF, a
     * `"` inside doubled, then [LINE_END]. No trailing comma, no spaces.
     */
    fun record(fields: List<String>): String = TODO("format")

    /** Free text: null -> blank; CR and LF each replaced by a space. Quoting is left to [record]. */
    fun text(value: String?): String = TODO("format")

    /** Optional `-`, digits, no leading zeros, no `+`; blank for null. */
    fun int(value: Int?): String = TODO("format")

    fun long(value: Long?): String = TODO("format")

    /**
     * Exactly [decimals] digits after the point:
     * `BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()`, with a negative
     * zero written unsigned. Blank for null, NaN or infinity.
     */
    fun decimal(value: Double?, decimals: Int): String = TODO("format")

    /** An Android integer written as a decimal with no double involved: `-84` -> `-84.0`. */
    fun decimalOfInt(value: Int?, decimals: Int): String = TODO("format")

    /** `time_epoch` from integer milliseconds; see [SessionFormat.timeEpoch]. */
    fun epoch(epochMillis: Long): String = TODO("format")

    /** A `*_utc` field; blank for null. See [SessionFormat.utc]. */
    fun utc(epochMillis: Long?): String = TODO("format")

    /** `1` or `0`. */
    fun flag(value: Boolean): String = TODO("format")

    /** `True` or `False`, as Python writes a boolean (cells.csv `plausible`). */
    fun pythonBool(value: Boolean): String = TODO("format")

    /** A list field (`bands`, `additional_plmns`): items joined with `, `; blank when empty. */
    fun list(items: List<String>): String = TODO("format")

    /** Splits one record (without its line ending) into fields, undoing [record]'s quoting. */
    fun parseRecord(line: String): List<String> = TODO("format")

    /**
     * Splits file text into records without their line endings. Records end in CR LF; a final
     * record without a line ending (a torn write) is returned last so callers can drop it.
     */
    fun records(text: String): List<String> = TODO("format")
}
