package com.fieldtap.platform

import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The one background thread on which an adapter's platform callbacks run, so callbacks never touch the
 * main thread and each adapter's callbacks arrive in order.
 *
 * - A callback that throws is logged and dropped: one unreadable input never takes the process, and the
 *   session with it, down.
 * - After [shutdown], late callbacks (Android may deliver one just after unregistering) are discarded.
 *
 * Owner: workstream `platform-adapters`.
 */
internal class AdapterExecutor(private val threadName: String) : Executor {
    private val delegate = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(),
        ThreadFactory { runnable -> Thread(runnable, threadName).apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    override fun execute(command: Runnable) {
        delegate.execute {
            try {
                command.run()
            } catch (e: RuntimeException) {
                Log.e(TAG, "A platform callback on $threadName failed; its input is dropped", e)
            }
        }
    }

    /** Stops the thread once queued callbacks have run; later callbacks are discarded. */
    fun shutdown() {
        delegate.shutdown()
    }

    /** True once [shutdown] was called. */
    val isShutdown: Boolean get() = delegate.isShutdown

    private companion object {
        const val TAG = "FieldTapAdapter"
    }
}
