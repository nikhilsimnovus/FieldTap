package com.fieldtap.format

/**
 * Constants of `fieldtap-session/1`, copied from `schema/columns.json` at the repository root.
 *
 * `schema/columns.json` is the authority, then `docs/SESSION-FORMAT.md`, then the golden session in
 * `tests/fixtures/android_session/`. These values are written by hand, not generated: the `:format`
 * test `SchemaDriftTest` reads `../../schema/columns.json` and fails when any header, name or number
 * here differs from it. To change the format, change the JSON first (see SESSION-FORMAT.md,
 * "Keeping both sides honest"), then this file.
 *
 * Owner: workstream `format`.
 */
object Schema {
    const val FORMAT: String = SessionFormat.ID
    const val SCHEMA_VERSION: Int = 1

    /** `android.telephony.CellInfo.UNAVAILABLE`. Never written: it becomes a blank field. */
    const val ANDROID_UNAVAILABLE: Int = Int.MAX_VALUE

    /** `android.telephony.CellInfo.UNAVAILABLE_LONG`, the unavailable NR NCI. Never written. */
    const val ANDROID_UNAVAILABLE_LONG: Long = Long.MAX_VALUE

    /** `transport.transport` for every app session. Never `file`, which labels a report SIMULATED. */
    const val TRANSPORT_APP: String = "android-api"

    /** `transport.app`. */
    const val APP_NAME: String = "5gto6G FieldTap"

    /** `privacy.data_class`. */
    const val DATA_CLASS: String = "kpi"

    /** `device.key` is this prefix plus a random per-install UUID. Never ANDROID_ID. */
    const val DEVICE_KEY_PREFIX: String = "app:"

    /** `kpi.max_age_ms`: no kpi.csv row is older than this, whichever Android interval applies. */
    const val KPI_MAX_AGE_MS: Long = 11_000

    /** `kpi.max_age_ms_short_interval`: the limit while Android's 2 s cell-info interval applies. */
    const val KPI_MAX_AGE_MS_SHORT_INTERVAL: Long = 2_500

    /** `kpi.gps_match_seconds` in milliseconds: a row gets a position only from a fix this close. */
    const val GPS_MATCH_MS: Long = 5_000

    /** `kpi.comment_pattern`. */
    const val KPI_COMMENT_PATTERN: String = "^android-api age_ms=(0|[1-9][0-9]*) src=(request|push)$"

    const val SLUG_MAX_LENGTH: Int = 48
    const val SLUG_EMPTY: String = "session"
    const val DIRECTORY_PATTERN: String = "^[0-9]{8}-[0-9]{6}_[A-Za-z0-9._-]{1,48}$"
    const val UTC_PATTERN: String =
        "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}\\+00:00$"

    /** Pattern of every token value: `kind`, `cause`, `summary.stopped_by`, `gaps[].reason`. */
    const val TOKEN_PATTERN: String = "^[a-z0-9_]+$"

    /** `upload_bundle.max_compressed_bytes` (50 MiB). */
    const val MAX_COMPRESSED_BYTES: Long = 52_428_800

    /** `upload_bundle.max_uncompressed_bytes` (200 MiB). */
    const val MAX_UNCOMPRESSED_BYTES: Long = 209_715_200

    /** Files the app never writes and never puts in a bundle. */
    val REPORT_OUTPUTS: List<String> = listOf("summary.json", "report.html", "index.html")

    /** `session_json.handset_keys`, in session.json key order. */
    val HANDSET_KEYS: List<String> = listOf(
        "manufacturer", "model", "device", "android_version", "android_build", "security_patch",
        "baseband", "soc", "platform", "hardware", "operator_mccmnc", "operator_name", "sim_mccmnc",
        "sim_operator_name", "network_type",
    )

    /** `session_json.forbidden_keys`: never a key anywhere in session.json. */
    val FORBIDDEN_KEYS: List<String> = listOf(
        "imei", "imeisv", "meid", "imsi", "iccid", "msisdn", "phone_number", "line1_number",
        "subscriber_id", "sim_serial", "serial", "serial_number", "android_id", "advertising_id", "tmsi",
        "guti", "supi", "suci", "ssid", "bssid", "mac", "mac_address", "ip", "ip_address",
    )

    val KPI_HEADER: List<String> = listOf(
        "frame", "time_epoch", "rat", "meas_id", "pci", "rsrp_dbm", "rsrq_db", "sinr_db", "comment", "lat", "lon",
    )

    val TRACK_HEADER: List<String> = listOf(
        "time_utc", "lat", "lon", "accuracy_m", "altitude_m", "speed_mps", "provider", "source",
    )

    val EVENTS_HEADER: List<String> = listOf(
        "time_utc", "rat", "kind", "severity", "title", "detail", "frame", "pci", "arfcn", "cause", "setup_ms",
    )

    val TRAFFIC_HEADER: List<String> = listOf(
        "time_utc", "test", "target", "ok", "seconds", "loss_pct", "rtt_min_ms", "rtt_avg_ms", "rtt_max_ms",
        "mbps", "bytes", "http_code", "error",
    )

    val CELLS_HEADER: List<String> = listOf(
        "first_seen_utc", "rat", "plmn", "mcc", "mnc", "tac", "cell_id", "enb_id", "sector", "pci", "band",
        "dl_earfcn", "ul_earfcn", "dl_bw_mhz", "ul_bw_mhz", "version", "plausible",
        "operator", "additional_plmns", "samples", "rsrp_min", "rsrp_max",
    )

    /** The first 19 columns are exactly `fieldtap.scan.COLUMNS`. */
    val CELLINFO_HEADER: List<String> = listOf(
        "seen_utc", "rat", "registered", "plmn", "mcc", "mnc", "operator", "pci", "arfcn", "bands", "tac",
        "cell_id", "bandwidth_khz", "rsrp", "rsrq", "sinr", "rssi", "level", "additional_plmns",
        "time_epoch", "timestamp_ms", "age_ms", "stale", "connection_status", "source", "cqi",
        "timing_advance", "csi_rsrp", "csi_rsrq", "csi_sinr", "screen_on", "charging", "wifi_connected",
        "sub_id", "lat", "lon",
    )

    /** `events.signalling_kind_prefixes`. [EventKind] cannot express any of them. */
    val SIGNALLING_KIND_PREFIXES: List<String> = listOf(
        "handover", "rrc_", "attach_", "registration_", "tau_", "pdn_", "pdu_", "reestablishment", "bearer_",
    )

    /** `events.signalling_kinds`. [EventKind] cannot express any of them. */
    val SIGNALLING_KINDS: List<String> = listOf(
        "attach_accept", "attach_attempt", "attach_complete", "attach_reject", "auth", "auth_failure",
        "auth_reject", "bearer_dedicated", "bearer_release", "bearer_setup", "capability", "config_update",
        "deregistration", "detach", "emm_info", "emm_status", "esm_status", "failure_info", "guti", "handover",
        "handover_command", "identity", "irat_mobility", "meas_report", "mm_status", "nas_security",
        "nas_security_reject", "paging", "pdn_attempt", "pdn_disconnect", "pdn_reject", "pdu_accept",
        "pdu_attempt", "pdu_modify_reject", "pdu_reject", "pdu_release", "reconfiguration",
        "reconfiguration_complete", "reestablishment", "reestablishment_attempt", "reestablishment_reject",
        "registration_accept", "registration_attempt", "registration_complete", "registration_reject",
        "rrc_attempt", "rrc_connected", "rrc_reject", "rrc_release", "rrc_resume", "rrc_resume_attempt",
        "rrc_setup", "scg_failure", "security_mode", "service_accept", "service_attempt", "service_reject",
        "sib1", "sm_status", "tau_accept", "tau_attempt", "tau_reject",
    )
}

/**
 * Inclusive ranges from `schema/columns.json` (`min`/`max` and `range_by_rat`). A value outside its
 * range is written blank, never clipped: "implausible values written blank instead of wrong".
 * Ranges keyed by [ServingRat] apply to rows of that RAT only; other RATs have no range.
 *
 * Owner: workstream `format`.
 */
object Ranges {
    val KPI_RSRP_DBM: Map<ServingRat, ClosedFloatingPointRange<Double>> =
        mapOf(ServingRat.LTE to -156.0..-43.0, ServingRat.NR to -156.0..-29.0)
    val KPI_RSRQ_DB: Map<ServingRat, ClosedFloatingPointRange<Double>> =
        mapOf(ServingRat.LTE to -34.0..3.0, ServingRat.NR to -43.0..20.5)
    val KPI_SINR_DB: Map<ServingRat, ClosedFloatingPointRange<Double>> =
        mapOf(ServingRat.LTE to -23.0..40.0, ServingRat.NR to -23.0..40.5)

    val PCI: Map<ServingRat, IntRange> = mapOf(ServingRat.LTE to 0..503, ServingRat.NR to 0..1007)
    val ARFCN: Map<ServingRat, IntRange> = mapOf(ServingRat.LTE to 0..262_143, ServingRat.NR to 0..3_279_165)
    val CELL_ID: Map<ServingRat, LongRange> =
        mapOf(ServingRat.LTE to 0L..268_435_455L, ServingRat.NR to 0L..68_719_476_735L)

    val CELLINFO_RSRP: Map<ServingRat, IntRange> = mapOf(ServingRat.LTE to -156..-43, ServingRat.NR to -156..-29)
    val CELLINFO_RSRQ: Map<ServingRat, IntRange> = mapOf(ServingRat.LTE to -34..3, ServingRat.NR to -43..20)
    val CELLINFO_SINR: Map<ServingRat, IntRange> = mapOf(ServingRat.LTE to -23..40, ServingRat.NR to -23..40)

    val TAC: IntRange = 0..16_777_215
    val BAND: IntRange = 1..1024
    val BANDWIDTH_KHZ: IntRange = 0..400_000
    val RSSI: IntRange = -150..0
    val LEVEL: IntRange = 0..4
    val CQI: IntRange = 0..15
    val TIMING_ADVANCE: IntRange = 0..3846
    val CSI_RSRP: IntRange = -156..-31
    val CSI_RSRQ: IntRange = -20..-3
    val CSI_SINR: IntRange = -23..23
    val ENB_ID: LongRange = 0L..1_048_575L
    val SECTOR: IntRange = 0..255
    val BW_MHZ: ClosedFloatingPointRange<Double> = 0.0..400.0

    val LAT: ClosedFloatingPointRange<Double> = -90.0..90.0
    val LON: ClosedFloatingPointRange<Double> = -180.0..180.0
    const val ACCURACY_M_MIN: Double = 0.0
    val ALTITUDE_M: ClosedFloatingPointRange<Double> = -1000.0..20_000.0
    val SPEED_MPS: ClosedFloatingPointRange<Double> = 0.0..400.0

    val LOSS_PCT: ClosedFloatingPointRange<Double> = 0.0..100.0
    val HTTP_CODE: IntRange = 100..599

    /** `time_epoch` bounds in Unix seconds (2000-01-01 to 2100-01-01). */
    val EPOCH_SECONDS: LongRange = 946_684_800L..4_102_444_800L
}
