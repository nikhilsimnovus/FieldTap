package com.fieldtap.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `schema/columns.json`, parsed once per test JVM. */
internal object SchemaJson {
    val root: JsonObject by lazy { Json.parseToJsonElement(Golden.schemaText()).jsonObject }

    /** The `csv` entry of [file]. */
    fun csv(file: SessionFile): JsonObject =
        root.arr("csv").map { it.jsonObject }.single { it.str("name") == file.fileName }

    fun columns(file: SessionFile): List<JsonObject> = csv(file).arr("columns").map { it.jsonObject }

    fun column(file: SessionFile, name: String): JsonObject = columns(file).single { it.str("name") == name }

    /** The `session_json.fields` entry with [path]. */
    fun field(path: String): JsonObject = fields().single { it.str("path") == path }

    fun fields(): List<JsonObject> = root.obj("session_json").arr("fields").map { it.jsonObject }
}

internal fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

internal fun JsonObject.strOrNull(key: String): String? = get(key)?.let { if (it is JsonNull) null else it.jsonPrimitive.content }

internal fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.content.toBooleanStrict()

internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

internal fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray

internal fun JsonObject.strings(key: String): List<String> = arr(key).map { it.jsonPrimitive.content }

internal fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.content.toLong()

internal fun JsonObject.numberOrNull(key: String): Double? =
    get(key)?.let { if (it is JsonNull) null else it.jsonPrimitive.content.toDouble() }
