package com.fieldtap.format

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
 *   `\u00XX` (lower-case hex); everything else, `/` and non-ASCII included, written as UTF-8 as is.
 * - [JsonInt] as `toString()`. [JsonDec] with exactly `decimals` digits via
 *   `BigDecimal(value).setScale(decimals, HALF_EVEN).toPlainString()`, which equals Python's
 *   `repr(round(value, decimals))` for the one-decimal values the contract uses; never `-0.0`.
 * - The document ends with one LF. No BOM.
 *
 * Owner: workstream `format`.
 */
sealed interface JsonNode

data class JsonObj(val members: List<Pair<String, JsonNode>>) : JsonNode

data class JsonArr(val items: List<JsonNode>) : JsonNode

data class JsonStr(val value: String) : JsonNode

data class JsonInt(val value: Long) : JsonNode

/** A non-integer number written with a fixed number of decimals. */
data class JsonDec(val value: Double, val decimals: Int) : JsonNode

data class JsonBool(val value: Boolean) : JsonNode

data object JsonNul : JsonNode

object JsonText {
    /** The whole document, ending in LF. */
    fun render(root: JsonNode): String = TODO("format")

    /** A JSON string literal with the escaping above, quotes included. */
    fun quote(value: String): String = TODO("format")
}
