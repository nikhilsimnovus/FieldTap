package com.fieldtap.format

/**
 * The session.json model, field for field, in key order. See docs/SESSION-FORMAT.md, "session.json".
 * [SessionJson] turns it into bytes; nothing else writes session.json.
 *
 * Owner: workstream `format`.
 */
data class SessionMeta(
    /** Random lower-case UUID. */
    val sessionId: String,
    /** Always null in fieldtap-session/1. */
    val groupId: String? = null,
    /** Not blank; the directory slug comes from it. */
    val name: String,
    val note: String? = null,
    /** Free-text place name, never coordinates. */
    val location: String? = null,
    val startedUtcMs: Long,
    /** Null only while recording. */
    val stoppedUtcMs: Long? = null,
    val transport: TransportMeta,
    val handset: HandsetMeta = HandsetMeta(),
    val device: DeviceMeta,
    /** The CSV files present, written as `{"kpi": "kpi.csv", ...}` in [SessionFile.CSV] order. */
    val files: List<SessionFile> = SessionFile.CSV,
    val summary: SummaryMeta,
    val capabilities: Capabilities = Capabilities(layer3 = false),
    val collection: CollectionMeta = CollectionMeta.EMPTY,
    val privacy: PrivacyMeta,
)

/** `transport`. `transport` is always `android-api`, `app` always `5gto6G FieldTap`. */
data class TransportMeta(
    val appVersion: String,
    val versionCode: Long,
    val transport: String = Schema.TRANSPORT_APP,
    val app: String = Schema.APP_NAME,
)

/**
 * `handset`: the keys the laptop tool reads over adb, all strings. A null value leaves its key out.
 * Sources are in SESSION-FORMAT.md. Never an identifier: no serial, IMEI or subscriber number.
 */
data class HandsetMeta(
    val manufacturer: String? = null,
    val model: String? = null,
    val device: String? = null,
    val androidVersion: String? = null,
    val androidBuild: String? = null,
    val securityPatch: String? = null,
    val baseband: String? = null,
    val soc: String? = null,
    val platform: String? = null,
    val hardware: String? = null,
    val operatorMccmnc: String? = null,
    val operatorName: String? = null,
    val simMccmnc: String? = null,
    val simOperatorName: String? = null,
    /** `getDataNetworkType()` as its name without `NETWORK_TYPE_`, e.g. `LTE`. */
    val networkType: String? = null,
)

/** `device`. [key] is `app:` plus the random install UUID. */
data class DeviceMeta(
    val key: String,
    val label: String? = null,
)

/**
 * `summary`. [stoppedBy] is `user`, an app stop token, an Android exit-reason token, or `recording`
 * while the session is open (see com.fieldtap.core.session.ExitReasons). [plmns] keeps insertion order.
 */
data class SummaryMeta(
    val stoppedBy: String,
    val plmns: Map<String, Int> = emptyMap(),
)

/**
 * `collection`: how well Android delivered measurements. Percentages are written with one
 * decimal by [SessionJson]; callers pass unrounded values.
 */
data class CollectionMeta(
    val medianFreshIntervalMs: Long?,
    val shortIntervalPct: Double?,
    val screenOnPct: Double?,
    val wifiConnectedPct: Double?,
    val chargingPct: Double?,
    val freshSamples: Long,
    val repeatsDropped: Long,
    val gaps: List<GapMeta>,
) {
    companion object {
        val EMPTY: CollectionMeta = CollectionMeta(
            medianFreshIntervalMs = null,
            shortIntervalPct = null,
            screenOnPct = null,
            wifiConnectedPct = null,
            chargingPct = null,
            freshSamples = 0,
            repeatsDropped = 0,
            gaps = emptyList(),
        )
    }
}

/** One `collection.gaps[]` entry; one per sampling_gap event, in time order. */
data class GapMeta(
    /** The last fresh sample before the gap (measurement time). */
    val startUtcMs: Long,
    /** The first fresh sample after it (measurement time). */
    val stopUtcMs: Long,
    /** A token, e.g. `screen_off`. */
    val reason: String,
) {
    /** `stop_utc` minus `start_utc`, written with one decimal. */
    val seconds: Double get() = (stopUtcMs - startUtcMs) / 1000.0
}

/** `privacy`. `data_class` is always `kpi`. */
data class PrivacyMeta(
    val locationPrecision: LocationPrecision,
    val zonePauses: Int,
    val consentVersion: String,
    /** 64 lower-case hex digits. */
    val consentSha256: String,
    val dataClass: String = Schema.DATA_CLASS,
)
