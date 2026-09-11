package com.fieldtap.platform.clock

import android.os.SystemClock
import com.fieldtap.core.time.Clock

/**
 * The real clocks. The only place in the app that reads them.
 *
 * Owner: workstream `platform-adapters`.
 */
object AndroidClock : Clock {
    override fun wallMillis(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}
