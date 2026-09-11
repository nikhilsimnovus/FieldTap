package com.fieldtap.format

/**
 * The seven files of a session directory, spelled exactly as the contract spells them.
 * No other file name is ever written into a session directory or a bundle.
 *
 * Owner: workstream `format`.
 */
enum class SessionFile(val fileName: String, val shortName: String?) {
    SESSION_JSON("session.json", null),
    KPI("kpi.csv", "kpi"),
    TRACK("track.csv", "track"),
    EVENTS("events.csv", "events"),
    TRAFFIC("traffic.csv", "traffic"),
    CELLS("cells.csv", "cells"),
    CELLINFO("cellinfo.csv", "cellinfo");

    /** The exact header of a CSV file; empty for session.json. */
    val header: List<String>
        get() = when (this) {
            SESSION_JSON -> emptyList()
            KPI -> Schema.KPI_HEADER
            TRACK -> Schema.TRACK_HEADER
            EVENTS -> Schema.EVENTS_HEADER
            TRAFFIC -> Schema.TRAFFIC_HEADER
            CELLS -> Schema.CELLS_HEADER
            CELLINFO -> Schema.CELLINFO_HEADER
        }

    companion object {
        /** The six CSV files, in the order of the session.json `files` object. */
        val CSV: List<SessionFile> = listOf(KPI, TRACK, EVENTS, TRAFFIC, CELLS, CELLINFO)

        /** Entry order inside an export zip. */
        val BUNDLE: List<SessionFile> = listOf(SESSION_JSON, KPI, TRACK, EVENTS, TRAFFIC, CELLS, CELLINFO)

        fun byFileName(name: String): SessionFile? = entries.firstOrNull { it.fileName == name }
    }
}
