package com.fieldtap.ui.readiness

import com.fieldtap.app.SoakState
import com.fieldtap.core.readiness.ReadinessItem
import com.fieldtap.core.readiness.ReadinessLevel
import com.fieldtap.core.readiness.ReadinessReport
import com.fieldtap.core.readiness.SettingsTarget
import com.fieldtap.core.soak.SoakResult
import com.fieldtap.ui.theme.StatusTone

/** The one-line verdict at the top of the Readiness screen. */
internal sealed interface ReadinessSummary {
    val tone: StatusTone

    /** Every check passed and the maker is not known for stopping background apps. */
    data object Ready : ReadinessSummary {
        override val tone: StatusTone get() = StatusTone.SUCCESS
    }

    /** Sessions can start, but [count] settings can cost samples. */
    data class Advice(val count: Int) : ReadinessSummary {
        override val tone: StatusTone get() = StatusTone.WARNING
    }

    /** Sessions can start and every check passed, but [manufacturer] is known for stopping background apps. */
    data class AggressiveOem(val manufacturer: String) : ReadinessSummary {
        override val tone: StatusTone get() = StatusTone.WARNING
    }

    /** [count] problems stop a session from starting. */
    data class Blocked(val count: Int) : ReadinessSummary {
        override val tone: StatusTone get() = StatusTone.ERROR
    }
}

/** How the soak test went. */
internal enum class SoakVerdict(val tone: StatusTone) {
    /** At least [ReadinessPresentation.SOAK_GOOD_PCT] of the seconds were logged. */
    GOOD(StatusTone.SUCCESS),

    /** At least [ReadinessPresentation.SOAK_PARTIAL_PCT], below good. */
    PARTIAL(StatusTone.WARNING),

    /** Below partial. */
    POOR(StatusTone.ERROR),

    /** Not one whole second elapsed, so nothing was measured. */
    TOO_SHORT(StatusTone.NEUTRAL),
}

/**
 * The Readiness screen's decisions, pure so they are unit-tested. The levels themselves come from
 * `ReadinessPolicy`; this only decides how they are shown.
 *
 * Owner: workstream `ui-setup`.
 */
internal object ReadinessPresentation {
    /** Share of seconds logged from which Android kept the app running well enough for a pocket session. */
    const val SOAK_GOOD_PCT: Double = 95.0

    /** Share of seconds logged below which the phone loses much of a session. */
    const val SOAK_PARTIAL_PCT: Double = 80.0

    fun tone(level: ReadinessLevel): StatusTone = when (level) {
        ReadinessLevel.OK -> StatusTone.SUCCESS
        ReadinessLevel.ADVICE -> StatusTone.WARNING
        ReadinessLevel.BLOCKER -> StatusTone.ERROR
    }

    /** Blockers first, then advice, then the checks that passed; the report's order within each level. */
    fun ordered(items: List<ReadinessItem>): List<ReadinessItem> = items.sortedByDescending { severity(it.level) }

    /** A fix button shows for a problem that a settings screen can fix. */
    fun showsFix(item: ReadinessItem): Boolean = item.level != ReadinessLevel.OK && item.target != SettingsTarget.NONE

    fun summary(report: ReadinessReport): ReadinessSummary {
        val blockers = report.blockers.size
        val advice = report.advice.size
        return when {
            blockers > 0 -> ReadinessSummary.Blocked(blockers)
            advice > 0 -> ReadinessSummary.Advice(advice)
            report.aggressiveOem -> ReadinessSummary.AggressiveOem(report.manufacturer.trim())
            else -> ReadinessSummary.Ready
        }
    }

    /** The share of a running soak test done, 0 to 1. */
    fun soakFraction(running: SoakState.Running): Float {
        if (running.durationMs <= 0) return 0f
        return (running.elapsedMs.toDouble() / running.durationMs).coerceIn(0.0, 1.0).toFloat()
    }

    fun soakVerdict(result: SoakResult): SoakVerdict = when {
        result.secondsElapsed <= 0 -> SoakVerdict.TOO_SHORT
        result.loggedPct >= SOAK_GOOD_PCT -> SoakVerdict.GOOD
        result.loggedPct >= SOAK_PARTIAL_PCT -> SoakVerdict.PARTIAL
        else -> SoakVerdict.POOR
    }

    /** Answers arrived, but none with the screen off: the run says nothing about screen-off behaviour. */
    fun screenStayedOn(result: SoakResult): Boolean = result.answers > 0 && result.screenOffAnswers == 0

    private fun severity(level: ReadinessLevel): Int = when (level) {
        ReadinessLevel.BLOCKER -> 2
        ReadinessLevel.ADVICE -> 1
        ReadinessLevel.OK -> 0
    }
}
