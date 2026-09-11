package com.fieldtap.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * session.json bytes and back.
 *
 * [encode] writes keys in the SESSION-FORMAT.md table order: format, session_id, group_id, name, note,
 * location, started_utc, stopped_utc, transport{transport, app, app_version, version_code},
 * handset{present keys in [Schema.HANDSET_KEYS] order}, device{key, label if set}, modem{},
 * log_mask{}, files{short name: file name}, summary{stopped_by, plmns{}}, capabilities{layer3},
 * collection{median_fresh_interval_ms, short_interval_pct, screen_on_pct, wifi_connected_pct,
 * charging_pct, fresh_samples, repeats_dropped, gaps[{start_utc, stop_utc, seconds, reason}]},
 * privacy{data_class, location_precision, zone_pauses, consent_version, consent_sha256}.
 * Objects are `{}` when empty, never null; percentages and `seconds` have one decimal; `*_utc` values
 * use [SessionFormat.utc]. Rendering is [JsonText.render]. `files` lists the CSVs of
 * [SessionMeta.files] in [SessionFile.CSV] order, whatever order the list has; a NaN or infinite
 * percentage is written `null`.
 *
 * [decode] is tolerant: it reads what [encode] writes and what older app builds wrote (unknown keys
 * ignored, a trailing `Z` accepted), and throws [SessionJsonException] for anything it cannot map
 * (not JSON, wrong types, missing `session_id`, `name` or `started_utc`). The sessions list and crash
 * recovery use it; a session whose session.json does not decode is listed as unreadable, never deleted.
 *
 * Decoding rules in detail:
 * - A key whose model property has a default may be missing or null, and takes that default:
 *   `group_id`, `note`, `location`, `stopped_utc`, `handset` and each of its keys, `device.label`,
 *   `files` (all six CSVs), `summary.plmns`, `capabilities` (`layer3` false), `collection`
 *   ([CollectionMeta.EMPTY], member by member), `transport.transport`, `transport.app` and
 *   `privacy.data_class`. `modem`, `log_mask` and `gaps[].seconds` are not read.
 * - Every other key is required: `session_id`, `name`, `started_utc`, `transport.app_version`,
 *   `transport.version_code`, `device.key`, `summary.stopped_by`, `privacy.location_precision`,
 *   `privacy.zone_pauses`, `privacy.consent_version`, `privacy.consent_sha256`, and each gap's
 *   `start_utc`, `stop_utc` and `reason`.
 * - A present value of the wrong JSON type, a timestamp not in the contract form (or with `Z`), an
 *   unknown `location_precision`, and a `format` other than [SessionFormat.ID] are errors.
 *
 * Tests (workstream `format`): `encode(decode(golden))` equals the golden session.json byte for byte;
 * null objects never appear; a German default locale does not change any byte.
 *
 * Owner: workstream `format`.
 */
object SessionJson {
    private const val PERCENT_DECIMALS: Int = 1
    private const val SECONDS_DECIMALS: Int = 1

    private val EMPTY_OBJECT: JsonObj = JsonObj(emptyList())

    /** The session.json tree of [meta], in contract key order. */
    fun toTree(meta: SessionMeta): JsonObj = obj(
        "format" to JsonStr(SessionFormat.ID),
        "session_id" to JsonStr(meta.sessionId),
        "group_id" to stringOrNull(meta.groupId),
        "name" to JsonStr(meta.name),
        "note" to stringOrNull(meta.note),
        "location" to stringOrNull(meta.location),
        "started_utc" to JsonStr(SessionFormat.utc(meta.startedUtcMs)),
        "stopped_utc" to utcOrNull(meta.stoppedUtcMs),
        "transport" to obj(
            "transport" to JsonStr(meta.transport.transport),
            "app" to JsonStr(meta.transport.app),
            "app_version" to JsonStr(meta.transport.appVersion),
            "version_code" to JsonInt(meta.transport.versionCode),
        ),
        "handset" to handsetTree(meta.handset),
        "device" to deviceTree(meta.device),
        "modem" to EMPTY_OBJECT,
        "log_mask" to EMPTY_OBJECT,
        "files" to filesTree(meta.files),
        "summary" to obj(
            "stopped_by" to JsonStr(meta.summary.stoppedBy),
            "plmns" to JsonObj(meta.summary.plmns.map { (plmn, rows) -> plmn to JsonInt(rows.toLong()) }),
        ),
        "capabilities" to obj("layer3" to JsonBool(meta.capabilities.layer3)),
        "collection" to collectionTree(meta.collection),
        "privacy" to obj(
            "data_class" to JsonStr(meta.privacy.dataClass),
            "location_precision" to JsonStr(meta.privacy.locationPrecision.wire),
            "zone_pauses" to JsonInt(meta.privacy.zonePauses.toLong()),
            "consent_version" to JsonStr(meta.privacy.consentVersion),
            "consent_sha256" to JsonStr(meta.privacy.consentSha256),
        ),
    )

    /** The bytes of session.json as UTF-8 text: [toTree] rendered by [JsonText.render], ending in LF. */
    fun encode(meta: SessionMeta): String = JsonText.render(toTree(meta))

    /**
     * Reads session.json text; see the decoding rules above.
     *
     * @throws SessionJsonException when the text is not JSON or cannot be mapped to [SessionMeta].
     */
    fun decode(text: String): SessionMeta {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization's SerializationException is an IllegalArgumentException.
            throw SessionJsonException("session.json is not valid JSON", e)
        }
        val json = root as? JsonObject ?: throw SessionJsonException("session.json is not a JSON object")
        val fields = JsonFields("", json)
        val format = fields.optionalString("format")
        if (format != null && format != SessionFormat.ID) {
            throw SessionJsonException("session.json: format '$format' is not ${SessionFormat.ID}")
        }
        val sessionId = fields.requiredString("session_id")
        val name = fields.requiredString("name")
        val startedUtcMs = fields.requiredUtc("started_utc")
        val transport = fields.requiredObject("transport")
        val device = fields.requiredObject("device")
        val summary = fields.requiredObject("summary")
        val privacy = fields.requiredObject("privacy")
        return SessionMeta(
            sessionId = sessionId,
            groupId = fields.optionalString("group_id"),
            name = name,
            note = fields.optionalString("note"),
            location = fields.optionalString("location"),
            startedUtcMs = startedUtcMs,
            stoppedUtcMs = fields.optionalUtc("stopped_utc"),
            transport = TransportMeta(
                appVersion = transport.requiredString("app_version"),
                versionCode = transport.requiredLong("version_code"),
                transport = transport.optionalString("transport") ?: Schema.TRANSPORT_APP,
                app = transport.optionalString("app") ?: Schema.APP_NAME,
            ),
            handset = fields.optionalObject("handset")?.let { decodeHandset(it) } ?: HandsetMeta(),
            device = DeviceMeta(
                key = device.requiredString("key"),
                label = device.optionalString("label"),
            ),
            files = fields.optionalObject("files")?.let { decodeFiles(it) } ?: SessionFile.CSV,
            summary = SummaryMeta(
                stoppedBy = summary.requiredString("stopped_by"),
                plmns = summary.optionalObject("plmns")?.let { decodePlmns(it) } ?: emptyMap(),
            ),
            capabilities = Capabilities(
                layer3 = fields.optionalObject("capabilities")?.optionalBoolean("layer3") ?: false,
            ),
            collection = fields.optionalObject("collection")?.let { decodeCollection(it) } ?: CollectionMeta.EMPTY,
            privacy = PrivacyMeta(
                locationPrecision = decodePrecision(privacy),
                zonePauses = privacy.requiredInt("zone_pauses"),
                consentVersion = privacy.requiredString("consent_version"),
                consentSha256 = privacy.requiredString("consent_sha256"),
                dataClass = privacy.optionalString("data_class") ?: Schema.DATA_CLASS,
            ),
        )
    }

    private fun obj(vararg members: Pair<String, JsonNode>): JsonObj = JsonObj(members.toList())

    private fun stringOrNull(value: String?): JsonNode = if (value == null) JsonNul else JsonStr(value)

    private fun utcOrNull(epochMillis: Long?): JsonNode =
        if (epochMillis == null) JsonNul else JsonStr(SessionFormat.utc(epochMillis))

    private fun percent(value: Double?): JsonNode =
        if (value == null || !value.isFinite()) JsonNul else JsonDec(value, PERCENT_DECIMALS)

    /** Values in [Schema.HANDSET_KEYS] order. */
    private fun handsetValues(handset: HandsetMeta): List<String?> = listOf(
        handset.manufacturer,
        handset.model,
        handset.device,
        handset.androidVersion,
        handset.androidBuild,
        handset.securityPatch,
        handset.baseband,
        handset.soc,
        handset.platform,
        handset.hardware,
        handset.operatorMccmnc,
        handset.operatorName,
        handset.simMccmnc,
        handset.simOperatorName,
        handset.networkType,
    )

    private fun handsetTree(handset: HandsetMeta): JsonObj {
        val values = handsetValues(handset)
        check(values.size == Schema.HANDSET_KEYS.size) { "handset has ${values.size} values for ${Schema.HANDSET_KEYS.size} keys" }
        return JsonObj(Schema.HANDSET_KEYS.zip(values).mapNotNull { (key, value) -> value?.let { key to JsonStr(it) } })
    }

    private fun deviceTree(device: DeviceMeta): JsonObj {
        val label = device.label
        return if (label == null) {
            obj("key" to JsonStr(device.key))
        } else {
            obj("key" to JsonStr(device.key), "label" to JsonStr(label))
        }
    }

    private fun filesTree(files: List<SessionFile>): JsonObj = JsonObj(
        SessionFile.CSV.filter { it in files }.mapNotNull { file -> file.shortName?.let { it to JsonStr(file.fileName) } },
    )

    private fun collectionTree(collection: CollectionMeta): JsonObj = obj(
        "median_fresh_interval_ms" to (collection.medianFreshIntervalMs?.let { JsonInt(it) } ?: JsonNul),
        "short_interval_pct" to percent(collection.shortIntervalPct),
        "screen_on_pct" to percent(collection.screenOnPct),
        "wifi_connected_pct" to percent(collection.wifiConnectedPct),
        "charging_pct" to percent(collection.chargingPct),
        "fresh_samples" to JsonInt(collection.freshSamples),
        "repeats_dropped" to JsonInt(collection.repeatsDropped),
        "gaps" to JsonArr(
            collection.gaps.map { gap ->
                obj(
                    "start_utc" to JsonStr(SessionFormat.utc(gap.startUtcMs)),
                    "stop_utc" to JsonStr(SessionFormat.utc(gap.stopUtcMs)),
                    "seconds" to JsonDec(gap.seconds, SECONDS_DECIMALS),
                    "reason" to JsonStr(gap.reason),
                )
            },
        ),
    )

    private fun decodeHandset(handset: JsonFields): HandsetMeta = HandsetMeta(
        manufacturer = handset.optionalString("manufacturer"),
        model = handset.optionalString("model"),
        device = handset.optionalString("device"),
        androidVersion = handset.optionalString("android_version"),
        androidBuild = handset.optionalString("android_build"),
        securityPatch = handset.optionalString("security_patch"),
        baseband = handset.optionalString("baseband"),
        soc = handset.optionalString("soc"),
        platform = handset.optionalString("platform"),
        hardware = handset.optionalString("hardware"),
        operatorMccmnc = handset.optionalString("operator_mccmnc"),
        operatorName = handset.optionalString("operator_name"),
        simMccmnc = handset.optionalString("sim_mccmnc"),
        simOperatorName = handset.optionalString("sim_operator_name"),
        networkType = handset.optionalString("network_type"),
    )

    private fun decodeFiles(files: JsonFields): List<SessionFile> {
        val present = HashSet<SessionFile>()
        for (key in files.keys) {
            val file = SessionFile.CSV.firstOrNull { it.shortName == key } ?: continue
            files.requiredString(key)
            present.add(file)
        }
        return SessionFile.CSV.filter { it in present }
    }

    private fun decodePlmns(plmns: JsonFields): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (plmn in plmns.keys) counts[plmn] = plmns.requiredInt(plmn)
        return counts
    }

    private fun decodeCollection(collection: JsonFields): CollectionMeta = CollectionMeta(
        medianFreshIntervalMs = collection.optionalLong("median_fresh_interval_ms"),
        shortIntervalPct = collection.optionalDouble("short_interval_pct"),
        screenOnPct = collection.optionalDouble("screen_on_pct"),
        wifiConnectedPct = collection.optionalDouble("wifi_connected_pct"),
        chargingPct = collection.optionalDouble("charging_pct"),
        freshSamples = collection.optionalLong("fresh_samples") ?: CollectionMeta.EMPTY.freshSamples,
        repeatsDropped = collection.optionalLong("repeats_dropped") ?: CollectionMeta.EMPTY.repeatsDropped,
        gaps = collection.optionalArray("gaps")?.let { decodeGaps(collection.pathOf("gaps"), it) } ?: emptyList(),
    )

    private fun decodeGaps(path: String, gaps: JsonArray): List<GapMeta> = gaps.mapIndexed { index, element ->
        val gap = element as? JsonObject ?: throw SessionJsonException("session.json: $path[$index] must be an object")
        val fields = JsonFields("$path[$index]", gap)
        GapMeta(
            startUtcMs = fields.requiredUtc("start_utc"),
            stopUtcMs = fields.requiredUtc("stop_utc"),
            reason = fields.requiredString("reason"),
        )
    }

    private fun decodePrecision(privacy: JsonFields): LocationPrecision {
        val wire = privacy.requiredString("location_precision")
        return LocationPrecision.fromWire(wire)
            ?: throw SessionJsonException("session.json: ${privacy.pathOf("location_precision")} '$wire' is not a known precision")
    }

    /** Typed access to one JSON object's members, with the dotted path used in error messages. */
    private class JsonFields(private val path: String, private val json: JsonObject) {
        val keys: Set<String> get() = json.keys

        fun pathOf(key: String): String = if (path.isEmpty()) key else "$path.$key"

        fun optionalString(key: String): String? = when (val element = json[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> if (element.isString) element.content else throw wrongType(key, "a string")
            else -> throw wrongType(key, "a string")
        }

        fun requiredString(key: String): String = optionalString(key) ?: throw missing(key)

        fun optionalLong(key: String): Long? = when (val element = json[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> {
                val value = if (element.isString) null else element.content.toLongOrNull()
                value ?: throw wrongType(key, "an integer")
            }
            else -> throw wrongType(key, "an integer")
        }

        fun requiredLong(key: String): Long = optionalLong(key) ?: throw missing(key)

        fun requiredInt(key: String): Int {
            val value = requiredLong(key)
            if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw wrongType(key, "a 32-bit integer")
            return value.toInt()
        }

        fun optionalDouble(key: String): Double? = when (val element = json[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> {
                val value = if (element.isString) null else element.content.toDoubleOrNull()
                if (value == null || !value.isFinite()) throw wrongType(key, "a finite number")
                value
            }
            else -> throw wrongType(key, "a number")
        }

        fun optionalBoolean(key: String): Boolean? = when (val element = json[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> when {
                element.isString -> throw wrongType(key, "a boolean")
                element.content == "true" -> true
                element.content == "false" -> false
                else -> throw wrongType(key, "a boolean")
            }
            else -> throw wrongType(key, "a boolean")
        }

        fun optionalUtc(key: String): Long? {
            val text = optionalString(key) ?: return null
            return SessionFormat.parseUtc(text)
                ?: throw SessionJsonException("session.json: ${pathOf(key)} is not a timestamp in the contract form")
        }

        fun requiredUtc(key: String): Long = optionalUtc(key) ?: throw missing(key)

        fun optionalObject(key: String): JsonFields? = when (val element = json[key]) {
            null, JsonNull -> null
            is JsonObject -> JsonFields(pathOf(key), element)
            else -> throw wrongType(key, "an object")
        }

        fun requiredObject(key: String): JsonFields = optionalObject(key) ?: throw missing(key)

        fun optionalArray(key: String): JsonArray? = when (val element = json[key]) {
            null, JsonNull -> null
            is JsonArray -> element
            else -> throw wrongType(key, "an array")
        }

        private fun missing(key: String): SessionJsonException =
            SessionJsonException("session.json: ${pathOf(key)} is missing")

        private fun wrongType(key: String, expected: String): SessionJsonException =
            SessionJsonException("session.json: ${pathOf(key)} must be $expected")
    }
}

/** session.json could not be read: not JSON, or not mappable to [SessionMeta]. The message names the key. */
class SessionJsonException(message: String, cause: Throwable? = null) : Exception(message, cause)
