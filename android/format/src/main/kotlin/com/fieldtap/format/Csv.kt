package com.fieldtap.format

import java.io.Closeable
import java.io.Reader
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Field and record rules shared by every CSV in a session: the bytes Python's `csv` module writes
 * with its default dialect (docs/SESSION-FORMAT.md, "CSV bytes" and "Numbers").
 * Blank means unknown, never `null`, `NaN`, `N/A` or 2147483647.
 *
 * Numbers are never formatted through the default locale, `String.format`, `java.util.Formatter`
 * or `Double.toString()`: a phone set to German would write `44,7`, and those formatters round on the
 * shortest decimal form instead of the exact binary value.
 *
 * As a last line of defence the numeric helpers write Android's "unavailable" sentinels blank:
 * `Integer.MAX_VALUE`, `Integer.MIN_VALUE`, `-Integer.MAX_VALUE`, their `Long` equivalents
 * (plus and minus 2147483647 and 2147483648) and `Long.MAX_VALUE`/`Long.MIN_VALUE`. `fieldtap validate`
 * rejects each of them in any numeric column, and no column of the contract can hold one as a real value.
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

    private const val DELIMITER: Char = ','
    private const val QUOTE: Char = '"'
    private const val CR: Char = '\r'
    private const val LF: Char = '\n'
    private const val LIST_SEPARATOR: String = ", "

    // States of the reader, the same as Python's csv reader in its default (non-strict) dialect.
    private const val START_FIELD: Int = 0
    private const val IN_FIELD: Int = 1
    private const val IN_QUOTED_FIELD: Int = 2
    private const val QUOTE_IN_QUOTED_FIELD: Int = 3

    private val REPLACEMENT_CHARACTER: Char = Char(0xFFFD)

    private val LONG_SENTINELS: Set<Long> = setOf(
        Long.MAX_VALUE,
        Long.MIN_VALUE,
        2_147_483_647L,
        -2_147_483_647L,
        2_147_483_648L,
        -2_147_483_648L,
    )

    /** An integer field, blank for null and for Android's sentinels. The same as [int]. */
    fun cell(value: Int?): String = int(value)

    /**
     * One record: fields joined with `,`, a field quoted only when it contains `,`, `"`, CR or LF, a
     * `"` inside doubled, then [LINE_END]. No trailing comma, no spaces.
     *
     * As in Python, a record whose only field is empty is written `""` so that it is not a blank
     * line; no session file has a one-column record.
     */
    fun record(fields: List<String>): String {
        if (fields.size == 1 && fields[0].isEmpty()) return "\"\"" + LINE_END
        val out = StringBuilder()
        for ((index, field) in fields.withIndex()) {
            if (index > 0) out.append(DELIMITER)
            if (needsQuotes(field)) {
                out.append(QUOTE)
                for (c in field) {
                    if (c == QUOTE) out.append(QUOTE)
                    out.append(c)
                }
                out.append(QUOTE)
            } else {
                out.append(field)
            }
        }
        return out.append(LINE_END).toString()
    }

    /**
     * Free text: null -> blank; CR and LF each replaced by a space. Quoting is left to [record].
     * A lone UTF-16 surrogate, which cannot be written as UTF-8, becomes U+FFFD.
     */
    fun text(value: String?): String {
        if (value == null) return ""
        if (value.none { it == CR || it == LF || it.isSurrogate() }) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == CR || c == LF -> out.append(' ')
                c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                    out.append(c).append(value[i + 1])
                    i++
                }
                c.isSurrogate() -> out.append(REPLACEMENT_CHARACTER)
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Optional `-`, digits, no leading zeros, no `+`; blank for null and for Android's sentinels. */
    fun int(value: Int?): String =
        if (value == null || value == Int.MAX_VALUE || value == Int.MIN_VALUE || value == -Int.MAX_VALUE) {
            ""
        } else {
            value.toString()
        }

    /** As [int] for a `Long`; blank for null and for the sentinels listed on [Csv]. */
    fun long(value: Long?): String = if (value == null || value in LONG_SENTINELS) "" else value.toString()

    /**
     * Exactly [decimals] digits after the point:
     * `BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()`, with a negative
     * zero written unsigned. Blank for null, NaN or infinity, and for exactly plus or minus 2147483647
     * or 2147483648 (a sentinel converted to a double).
     *
     * @throws IllegalArgumentException when [decimals] is negative (a programming error).
     */
    fun decimal(value: Double?, decimals: Int): String {
        require(decimals >= 0) { "decimals must be 0 or more, was $decimals" }
        if (value == null || !value.isFinite() || isSentinel(value)) return ""
        return plain(BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN))
    }

    /**
     * An Android integer written as a decimal with no double involved: `-84` -> `-84.0`. Blank for
     * null and for Android's sentinels.
     *
     * @throws IllegalArgumentException when [decimals] is negative (a programming error).
     */
    fun decimalOfInt(value: Int?, decimals: Int): String {
        require(decimals >= 0) { "decimals must be 0 or more, was $decimals" }
        val digits = int(value)
        return when {
            digits.isEmpty() -> ""
            decimals == 0 -> digits
            else -> digits + "." + "0".repeat(decimals)
        }
    }

    /** `time_epoch` from integer milliseconds; see [SessionFormat.timeEpoch]. */
    fun epoch(epochMillis: Long): String = SessionFormat.timeEpoch(epochMillis)

    /** A `*_utc` field; blank for null. See [SessionFormat.utc]. */
    fun utc(epochMillis: Long?): String = if (epochMillis == null) "" else SessionFormat.utc(epochMillis)

    /** `1` or `0`. */
    fun flag(value: Boolean): String = if (value) "1" else "0"

    /** `True` or `False`, as Python writes a boolean (cells.csv `plausible`). */
    fun pythonBool(value: Boolean): String = if (value) "True" else "False"

    /**
     * A list field (`bands`, `additional_plmns`): items joined with `, `; blank when empty. The
     * caller chooses the order (`bands` as Android reports them, `additional_plmns` ascending).
     */
    fun list(items: List<String>): String = items.joinToString(LIST_SEPARATOR)

    /**
     * Splits one record (without its line ending) into fields, undoing [record]'s quoting.
     *
     * It reads as Python's csv reader does in its default dialect: a `"` opens a quoted field only at
     * the start of a field, `""` inside one is a `"`, and characters after a closing quote are kept.
     * An empty line is one empty field.
     */
    fun parseRecord(line: String): List<String> {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var state = START_FIELD
        for (c in line) {
            when (state) {
                START_FIELD -> when (c) {
                    QUOTE -> state = IN_QUOTED_FIELD
                    DELIMITER -> fields.add("")
                    else -> {
                        field.append(c)
                        state = IN_FIELD
                    }
                }
                IN_FIELD -> if (c == DELIMITER) {
                    fields.add(field.toString())
                    field.setLength(0)
                    state = START_FIELD
                } else {
                    field.append(c)
                }
                IN_QUOTED_FIELD -> if (c == QUOTE) {
                    state = QUOTE_IN_QUOTED_FIELD
                } else {
                    field.append(c)
                }
                else -> when (c) {
                    QUOTE -> {
                        field.append(QUOTE)
                        state = IN_QUOTED_FIELD
                    }
                    DELIMITER -> {
                        fields.add(field.toString())
                        field.setLength(0)
                        state = START_FIELD
                    }
                    else -> {
                        field.append(c)
                        state = IN_FIELD
                    }
                }
            }
        }
        fields.add(field.toString())
        return fields
    }

    /**
     * Splits file text into records without their line endings. Records end in CR LF; a final
     * record without a line ending (a torn write) is returned last so callers can drop it.
     *
     * Quotes are honoured as in [parseRecord]: a CR or LF inside a quoted field belongs to the field.
     * Outside quotes a lone CR or LF also ends a record, as in Python's csv reader, and a blank line
     * is returned as an empty record. A caller detects a torn last record with
     * `!text.endsWith(LINE_END)`. Empty text has no records.
     */
    fun records(text: String): List<String> {
        val out = ArrayList<String>()
        var state = START_FIELD
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (state == IN_QUOTED_FIELD) {
                if (c == QUOTE) state = QUOTE_IN_QUOTED_FIELD
                i++
                continue
            }
            when (c) {
                CR, LF -> {
                    out.add(text.substring(start, i))
                    i += if (c == CR && i + 1 < text.length && text[i + 1] == LF) 2 else 1
                    start = i
                    state = START_FIELD
                    continue
                }
                QUOTE -> state = when (state) {
                    START_FIELD, QUOTE_IN_QUOTED_FIELD -> IN_QUOTED_FIELD
                    else -> IN_FIELD
                }
                DELIMITER -> state = START_FIELD
                else -> state = IN_FIELD
            }
            i++
        }
        if (start < text.length) out.add(text.substring(start))
        return out
    }

    /**
     * [records] over a stream: [next] returns each record of [reader] without its line ending, a final record without
     * one last, then null, splitting exactly as [records] does. It holds one record in memory at a time, so a session
     * file of any size can be read back. [close] closes [reader].
     */
    class RecordReader(private val reader: Reader, bufferChars: Int = 64 * 1024) : Closeable {
        private val buffer = CharArray(bufferChars.coerceAtLeast(1))
        private var length = 0
        private var position = 0
        private var skipLineFeed = false
        private var finished = false

        fun next(): String? {
            if (finished) return null
            val record = StringBuilder()
            var state = START_FIELD
            var started = false
            while (true) {
                if (position == length) {
                    val read = reader.read(buffer)
                    if (read <= 0) {
                        finished = true
                        return if (started) record.toString() else null
                    }
                    length = read
                    position = 0
                }
                val c = buffer[position++]
                if (skipLineFeed) {
                    skipLineFeed = false
                    if (c == LF) continue
                }
                started = true
                if (state == IN_QUOTED_FIELD) {
                    if (c == QUOTE) state = QUOTE_IN_QUOTED_FIELD
                    record.append(c)
                    continue
                }
                when (c) {
                    CR, LF -> {
                        skipLineFeed = c == CR
                        return record.toString()
                    }
                    QUOTE -> state = if (state == START_FIELD || state == QUOTE_IN_QUOTED_FIELD) IN_QUOTED_FIELD else IN_FIELD
                    DELIMITER -> state = START_FIELD
                    else -> state = IN_FIELD
                }
                record.append(c)
            }
        }

        override fun close() {
            reader.close()
        }
    }

    /** The plain digits of an already rounded value, never with a sign on zero. */
    internal fun plain(rounded: BigDecimal): String {
        val text = rounded.toPlainString()
        return if (rounded.signum() == 0 && text.startsWith('-')) text.substring(1) else text
    }

    private fun needsQuotes(field: String): Boolean =
        field.any { it == DELIMITER || it == QUOTE || it == CR || it == LF }

    private fun isSentinel(value: Double): Boolean {
        val magnitude = abs(value)
        return magnitude == 2_147_483_647.0 || magnitude == 2_147_483_648.0
    }
}
