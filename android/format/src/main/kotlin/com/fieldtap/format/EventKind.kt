package com.fieldtap.format

/**
 * The event kinds the app writes (`events.app_kinds` in columns.json), with the `rat` and
 * `severity` values each allows and whether its `pci` and `arfcn` are filled.
 *
 * This enum is the only way to put a `kind` into events.csv, so a signalling kind (`handover`,
 * `rrc_*`, `attach_*`, `registration_*`, anything in [Schema.SIGNALLING_KINDS]) cannot be written:
 * the report would count it as a procedure in a session that cannot observe one.
 *
 * Owner: workstream `format`. Titles and details are chosen by the deriving workstream; see
 * android/ARCHITECTURE.md, "Events".
 */
enum class EventKind(
    val wire: String,
    val rats: Set<EventRat>,
    val severities: Set<Severity>,
    val namesCell: Boolean,
) {
    SERVING_CELL("serving_cell", setOf(EventRat.LTE, EventRat.NR), setOf(Severity.INFO), true),
    RAT_CHANGE("rat_change", setOf(EventRat.LTE, EventRat.NR), setOf(Severity.INFO, Severity.WARN), true),
    SERVICE_LOST("service_lost", setOf(EventRat.LTE, EventRat.NR, EventRat.NONE), setOf(Severity.ERROR), false),
    EMERGENCY_ONLY("emergency_only", setOf(EventRat.LTE, EventRat.NR), setOf(Severity.ERROR), true),
    SERVICE_RESTORED("service_restored", setOf(EventRat.LTE, EventRat.NR), setOf(Severity.OK), true),
    DATA_STATE("data_state", setOf(EventRat.NONE), setOf(Severity.INFO, Severity.WARN), false),
    NR_DISPLAY("nr_display", setOf(EventRat.NR), setOf(Severity.INFO), false),
    GPS_LOST("gps_lost", setOf(EventRat.NONE), setOf(Severity.WARN), false),
    GPS_RESTORED("gps_restored", setOf(EventRat.NONE), setOf(Severity.OK), false),
    SAMPLING_GAP("sampling_gap", setOf(EventRat.NONE), setOf(Severity.WARN), false),
    MARKER("marker", setOf(EventRat.NONE), setOf(Severity.INFO), false),
    TEST_FAILED("test_failed", setOf(EventRat.NONE), setOf(Severity.ERROR), false),
    SESSION_INTERRUPTED("session_interrupted", setOf(EventRat.NONE), setOf(Severity.ERROR), false),
    PRIVACY_ZONE("privacy_zone", setOf(EventRat.NONE), setOf(Severity.INFO), false),
}
