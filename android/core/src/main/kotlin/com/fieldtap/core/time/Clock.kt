package com.fieldtap.core.time

/**
 * The two clocks every time-dependent class takes by injection. Nothing in :core or :app reads
 * `System.currentTimeMillis()` or `SystemClock.elapsedRealtime()` directly, so tests control time.
 *
 * - [wallMillis]: Unix milliseconds (`System.currentTimeMillis()`); what files show.
 * - [elapsedRealtimeMillis]: milliseconds since boot, including deep sleep
 *   (`SystemClock.elapsedRealtime()`); the clock of `CellInfo.getTimestampMillis()` and
 *   `Location.getElapsedRealtimeMillis()`. Ages, intervals, gaps and the GPS join use it, because the
 *   wall clock can jump.
 *
 * Owner: workstream `session-core`. Android implementation: com.fieldtap.platform.clock.AndroidClock.
 */
interface Clock {
    fun wallMillis(): Long

    fun elapsedRealtimeMillis(): Long
}

/** A clock tests set by hand. Both clocks move together on [advance]. Not thread-safe. */
class ManualClock(
    var wallMs: Long = 1_789_050_600_000L,
    var elapsedMs: Long = 25_323_456L,
) : Clock {
    override fun wallMillis(): Long = wallMs

    override fun elapsedRealtimeMillis(): Long = elapsedMs

    fun advance(ms: Long) {
        wallMs += ms
        elapsedMs += ms
    }
}
