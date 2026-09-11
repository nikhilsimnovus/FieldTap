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
    val loggedPct: Double get() = TODO("service-and-tests")
}

/**
 * The optional 10-minute screen-off soak test: the location foreground service runs the telephony
 * ticker with no session and no files, and this counts how much of the time the app actually ran.
 *
 * Owner: workstream `service-and-tests`.
 */
class SoakEvaluator(private val startElapsedMs: Long) {
    fun onTick(nowElapsedMs: Long): Unit = TODO("service-and-tests")

    fun onAnswer(classified: ClassifiedAnswer): Unit = TODO("service-and-tests")

    fun result(nowElapsedMs: Long): SoakResult = TODO("service-and-tests")

    companion object {
        const val DEFAULT_DURATION_MS: Long = 600_000
    }
}
