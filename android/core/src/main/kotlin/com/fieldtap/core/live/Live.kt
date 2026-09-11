package com.fieldtap.core.live

import com.fieldtap.core.input.DataStateSnapshot
import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.core.input.DisplayInfoSnapshot
import com.fieldtap.core.input.FixSample
import com.fieldtap.core.input.GnssSnapshot
import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.MeasurementInput
import com.fieldtap.core.input.RadioListener
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.core.input.SignalSnapshot
import com.fieldtap.format.Rat

/** One cell as the Live screen shows it. */
data class LiveCell(
    val rat: Rat,
    val pci: Int?,
    val arfcn: Int?,
    val band: Int?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val plmn: String?,
    val operator: String?,
    val connectionStatus: Int?,
    /** `timestampMs` of the measurement, to age it on every tick. */
    val timestampMs: Long,
)

/** The age badge on the serving tile. */
enum class AgeBadge {
    /** No serving sample yet. */
    NONE,

    /** At most 2500 ms old. */
    FRESH,

    /** At most 11 000 ms old. */
    AGING,

    /** Older: the tile greys out and says so. */
    STALE,
}

/** A chart point: elapsedRealtime of the measurement and its value. */
data class ChartPoint(val elapsedMs: Long, val value: Int)

/** Everything the Live screen draws. Plain values; the screen never computes. */
data class LiveState(
    val serving: LiveCell? = null,
    val nsaLeg: LiveCell? = null,
    val servingAgeMs: Long? = null,
    val badge: AgeBadge = AgeBadge.NONE,
    /** From the newest answer: cells that are neither primary nor secondary serving, strongest first. */
    val neighbours: List<LiveCell> = emptyList(),
    /** Fresh primary serving RSRP over the last 5 minutes. */
    val rsrpSeries: List<ChartPoint> = emptyList(),
    /** Fresh primary serving SINR over the last 5 minutes. */
    val sinrSeries: List<ChartPoint> = emptyList(),
    /** True for Android's 2 s interval, false for 10 s, null before any answer. */
    val shortInterval: Boolean? = null,
    /** Median of the last 10 fresh intervals. */
    val recentFreshIntervalMs: Long? = null,
    val conditions: DeviceConditions? = null,
    val service: ServiceStateSnapshot? = null,
    val display: DisplayInfoSnapshot? = null,
    val data: DataStateSnapshot? = null,
    val signal: SignalSnapshot? = null,
    val lastFix: FixSample? = null,
    val gnss: GnssSnapshot? = null,
    val listeners: Map<RadioListener, ListenerOutcome> = emptyMap(),
    val nowElapsedMs: Long = 0,
)

/**
 * The Live screen's state, reduced from the same [MeasurementInput] stream the recorder gets, while
 * the screen is visible or a session runs. It de-duplicates with its own
 * [com.fieldtap.core.radio.FreshnessEngine] and picks serving cells with
 * [com.fieldtap.core.radio.ServingCellSelector]; it never writes files.
 *
 * - [reduce] folds one input in; [tick] re-ages the badge and drops chart points older than [WINDOW_MS].
 * - [badge]: null -> NONE, <= 2500 FRESH, <= 11 000 AGING, else STALE.
 *
 * Tests: chart window; repeats do not add chart points; neighbour ordering; badge thresholds.
 *
 * Owner: workstream `ui-session`.
 */
class LiveStateReducer {
    fun reduce(state: LiveState, input: MeasurementInput): LiveState = TODO("ui-session")

    fun tick(state: LiveState, nowElapsedMs: Long): LiveState = TODO("ui-session")

    companion object {
        const val WINDOW_MS: Long = 300_000

        fun badge(ageMs: Long?): AgeBadge = TODO("ui-session")
    }
}
