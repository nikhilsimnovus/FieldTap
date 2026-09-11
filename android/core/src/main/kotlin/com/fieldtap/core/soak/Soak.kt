package com.fieldtap.core.soak

import com.fieldtap.core.radio.ClassifiedAnswer

/** The readiness soak test's result: seconds logged against seconds elapsed. */
data class SoakResult(
    val durationMs: Long,
    val secondsElapsed: Long,
    /** Distinct whole seconds since start in which the 1 s ticker ran. */
    val secondsLogged: Long,
    val answers: Int,
    val freshAnswers: Int,
    val screenOffAnswers: Int,
) {
    /** `100 * secondsLogged / secondsElapsed`; 0 when nothing elapsed. */
    val loggedPct: Double
        get() = if (secondsElapsed <= 0) 0.0 else 100.0 * secondsLogged / secondsElapsed
}

/**
 * The optional 10-minute screen-off soak test: the location foreground service runs the telephony
 * ticker with no session and no files, and this counts how much of the time the app actually ran.
 *
 * - [onTick] marks the whole second since start in which it was called; ticks before the start or at or
 *   after [durationMs] are ignored, and several ticks in one second count once.
 * - [onAnswer] counts cell-info answers, the fresh ones, and those that arrived with the screen off.
 * - [result] reports the seconds elapsed so far (at most the duration) and the logged seconds among them,
 *   so an early end reports the part that ran.
 *
 * Not thread-safe: the caller confines it to one thread or synchronises on it.
 *
 * Owner: workstream `service-and-tests`.
 */
class SoakEvaluator(
    private val startElapsedMs: Long,
    durationMs: Long = DEFAULT_DURATION_MS,
) {
    /** The planned duration, at least [MIN_DURATION_MS]. */
    val durationMs: Long = durationMs.coerceAtLeast(MIN_DURATION_MS)

    private val logged = java.util.BitSet((this.durationMs / 1000).toInt().coerceAtLeast(1))
    private var answers = 0
    private var freshAnswers = 0
    private var screenOffAnswers = 0

    fun onTick(nowElapsedMs: Long) {
        val sinceStart = nowElapsedMs - startElapsedMs
        if (sinceStart < 0 || sinceStart >= durationMs) return
        logged.set((sinceStart / 1000).toInt())
    }

    fun onAnswer(classified: ClassifiedAnswer) {
        answers++
        if (classified.fresh) freshAnswers++
        if (!classified.answer.conditions.screenOn) screenOffAnswers++
    }

    fun result(nowElapsedMs: Long): SoakResult {
        val elapsedMs = (nowElapsedMs - startElapsedMs).coerceIn(0, durationMs)
        val secondsElapsed = elapsedMs / 1000
        val secondsLogged = if (secondsElapsed == 0L) 0 else logged.get(0, secondsElapsed.toInt()).cardinality()
        return SoakResult(
            durationMs = durationMs,
            secondsElapsed = secondsElapsed,
            secondsLogged = secondsLogged.toLong(),
            answers = answers,
            freshAnswers = freshAnswers,
            screenOffAnswers = screenOffAnswers,
        )
    }

    /** True once [durationMs] has passed since start. */
    fun isFinished(nowElapsedMs: Long): Boolean = nowElapsedMs - startElapsedMs >= durationMs

    companion object {
        const val DEFAULT_DURATION_MS: Long = 600_000

        /** The shortest soak test; shorter requests are raised to it. */
        const val MIN_DURATION_MS: Long = 10_000
    }
}
