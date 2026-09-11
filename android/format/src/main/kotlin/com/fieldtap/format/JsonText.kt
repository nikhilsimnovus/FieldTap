package com.fieldtap.format

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A small ordered JSON tree and a renderer that writes exactly what Python's
 * `json.dumps(value, indent=2, ensure_ascii=False) + "\n"` writes.
 *
 * Used for session.json ([SessionJson]) and for the capability probe export. Kotlin's
 * serialization library is used only to read JSON, never to write a session file, because its
 * layout and number formatting are not the contract's.
 *
 * Rendering rules:
 * - Two-space indent, one member per line, `"key": value`, `,` after every member but the last.
 * - An empty object renders `{}` and an empty array `[]`, on one line.
 * - Strings: `"` -> `\"`, `\` -> `\\`, `\n`, `\r`, `\t`, `\b`, `\f`, other code points below U+0020 as
 *   a six-character escape with four lower-case hex digits; everything else, `/` and non-ASCII
 *   included, written as UTF-8 as is. A lone UTF-16 surrogate, which UTF-8 cannot hold, is written
 *   as U+FFFD.
 * - [JsonInt] as `toString()`. [JsonDec] with exactly `decimals` digits via
 *   `BigDecimal(value).setScale(decimals, HALF_EVEN).toPlainString()`, which equals Python's
 *   `repr(round(value, decimals))` for the one-decimal values the contract uses; never `-0.0`.
 *   A NaN or infinite [JsonDec] renders `null`; `decimals` below 1 is a programming error.
 * - The document ends with one LF. No BOM.
 *
 * Owner: workstream `format`.
 */
sealed interface JsonNode

/** A JSON object; members are rendered in list order. */
data class JsonObj(val members: List<Pair<String, JsonNode>>) : JsonNode

/** A JSON array. */
data class JsonArr(val items: List<JsonNode>) : JsonNode

/** A JSON string. */
data class JsonStr(val value: String) : JsonNode

/** A JSON integer. */
data class JsonInt(val value: Long) : JsonNode

/** A non-integer number written with a fixed number of decimals. */
data class JsonDec(val value: Double, val decimals: Int) : JsonNode

/** `true` or `false`. */
data class JsonBool(val value: Boolean) : JsonNode

/** `null`. */
data object JsonNul : JsonNode

/** Renders a [JsonNode] tree; see the rules on [JsonNode]. */
object JsonText {
    private const val INDENT: String = "  "
    private const val HEX_DIGITS: String = "0123456789abcdef"
    private const val BACKSLASH: Char = '\\'
    private const val QUOTE: Char = '"'

    private val BACKSPACE: Char = Char(0x08)
    private val FORM_FEED: Char = Char(0x0C)
    private val REPLACEMENT_CHARACTER: Char = Char(0xFFFD)

    /**
     * The whole document, ending in LF.
     *
     * @throws IllegalArgumentException for a [JsonDec] with fewer than one decimal.
     */
    fun render(root: JsonNode): String {
        val out = StringBuilder()
        write(out, root, 0)
        return out.append('\n').toString()
    }

    /** A JSON string literal with the escaping above, quotes included. */
    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        appendQuoted(out, value)
        return out.toString()
    }

    private fun write(out: StringBuilder, node: JsonNode, depth: Int) {
        when (node) {
            is JsonObj -> writeContainer(out, '{', '}', node.members.size, depth) { index ->
                val (key, value) = node.members[index]
                appendQuoted(out, key)
                out.append(": ")
                write(out, value, depth + 1)
            }
            is JsonArr -> writeContainer(out, '[', ']', node.items.size, depth) { index ->
                write(out, node.items[index], depth + 1)
            }
            is JsonStr -> appendQuoted(out, node.value)
            is JsonInt -> out.append(node.value)
            is JsonDec -> out.append(decimal(node))
            is JsonBool -> out.append(if (node.value) "true" else "false")
            is JsonNul -> out.append("null")
        }
    }

    private inline fun writeContainer(
        out: StringBuilder,
        open: Char,
        close: Char,
        size: Int,
        depth: Int,
        member: (Int) -> Unit,
    ) {
        out.append(open)
        if (size == 0) {
            out.append(close)
            return
        }
        for (index in 0 until size) {
            if (index > 0) out.append(',')
            out.append('\n')
            indent(out, depth + 1)
            member(index)
        }
        out.append('\n')
        indent(out, depth)
        out.append(close)
    }

    private fun indent(out: StringBuilder, depth: Int) {
        repeat(depth) { out.append(INDENT) }
    }

    private fun decimal(node: JsonDec): String {
        require(node.decimals >= 1) { "JsonDec needs at least one decimal, was ${node.decimals}" }
        if (!node.value.isFinite()) return "null"
        return Csv.plain(BigDecimal(node.value).setScale(node.decimals, RoundingMode.HALF_EVEN))
    }

    private fun appendQuoted(out: StringBuilder, value: String) {
        out.append(QUOTE)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == QUOTE -> out.append(BACKSLASH).append(QUOTE)
                c == BACKSLASH -> out.append(BACKSLASH).append(BACKSLASH)
                c == '\n' -> out.append(BACKSLASH).append('n')
                c == '\r' -> out.append(BACKSLASH).append('r')
                c == '\t' -> out.append(BACKSLASH).append('t')
                c == BACKSPACE -> out.append(BACKSLASH).append('b')
                c == FORM_FEED -> out.append(BACKSLASH).append('f')
                c.code < 0x20 -> out.append(BACKSLASH).append("u00")
                    .append(HEX_DIGITS[c.code shr 4])
                    .append(HEX_DIGITS[c.code and 0xF])
                c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                    out.append(c).append(value[i + 1])
                    i++
                }
                c.isSurrogate() -> out.append(REPLACEMENT_CHARACTER)
                else -> out.append(c)
            }
            i++
        }
        out.append(QUOTE)
    }
}
