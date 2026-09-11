package com.fieldtap.core.nettest

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Runs [block], which blocks its thread in calls that ignore coroutine cancellation (waiting for an HTTP response,
 * reading a body), and calls [interrupt] as soon as the calling coroutine is cancelled, so that those calls fail at
 * once instead of running on to their own timeout. For a download, [interrupt] disconnects the connection: its socket
 * closes and the cellular network request ends with the test.
 *
 * - [interrupt] is called at most once, and never after [block] has returned or thrown.
 * - It runs on the caller's dispatcher while [block] still holds its own thread, so that dispatcher must have another
 *   thread free: `Dispatchers.IO` does; a single-threaded dispatcher would run it only after [block] ended.
 * - The watcher starts undispatched, so a cancellation that arrives before [block] reaches its blocking call still
 *   interrupts; [block] should check for cancellation just before that call.
 * - A failure of [interrupt] itself is swallowed: the cancellation is what matters.
 *
 * Owner: workstream `service-and-tests`.
 */
suspend fun <T> interruptingOnCancel(interrupt: () -> Unit, block: suspend () -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (finished.compareAndSet(false, true)) {
                try {
                    interrupt()
                } catch (e: RuntimeException) {
                    // The blocked call ends by its own timeout instead.
                }
            }
        }
    }
    try {
        block()
    } finally {
        finished.set(true)
        watcher.cancel()
    }
}
