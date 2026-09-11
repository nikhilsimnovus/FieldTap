package com.fieldtap.format

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
 * use [SessionFormat.utc]. Rendering is [JsonText.render].
 *
 * [decode] is tolerant: it reads what [encode] writes and what older app builds wrote (unknown keys
 * ignored, a trailing `Z` accepted), and throws [SessionJsonException] for anything it cannot map
 * (not JSON, wrong types, missing `session_id`, `name` or `started_utc`). The sessions list and crash
 * recovery use it; a session whose session.json does not decode is listed as unreadable, never deleted.
 *
 * Tests (workstream `format`): `encode(decode(golden))` equals the golden session.json byte for byte;
 * null objects never appear; a German default locale does not change any byte.
 *
 * Owner: workstream `format`.
 */
object SessionJson {
    fun toTree(meta: SessionMeta): JsonObj = TODO("format")

    fun encode(meta: SessionMeta): String = TODO("format")

    fun decode(text: String): SessionMeta = TODO("format")
}

class SessionJsonException(message: String, cause: Throwable? = null) : Exception(message, cause)
