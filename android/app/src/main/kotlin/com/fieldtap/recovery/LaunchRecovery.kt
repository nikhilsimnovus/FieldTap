package com.fieldtap.recovery

import android.util.Log
import com.fieldtap.app.RecoveryNotices
import com.fieldtap.core.session.ExitRecord
import com.fieldtap.core.session.OpenSession
import com.fieldtap.core.session.RecoveryAction
import com.fieldtap.core.session.RecoveryPlanner
import com.fieldtap.core.session.SessionOutcome
import com.fieldtap.core.session.SessionRecovery
import com.fieldtap.platform.exit.ExitReasonReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Closes sessions a killed process left open, once per process start, before any new session.
 *
 * [run]: `SessionRecovery.findOpen()`, `ExitReasonReader.recent()`, `RecoveryPlanner.plan(open, exits,
 * activeDirName())`, then `SessionRecovery.close` for each CloseInterrupted, in plan order, on a background
 * dispatcher. Failures are logged (no session content in the log) and leave the session open for the
 * next launch. Closed sessions appear in [closed] until acknowledged.
 *
 * [run] is idempotent and safe to call concurrently: the first call does the work, later calls wait for it
 * and return the same outcomes. `SessionControl.start` calls it before dispatching a start, so a start
 * never races recovery. A run that was cancelled before it finished runs again on the next call.
 *
 * Owner: workstream `service-and-tests`.
 */
class LaunchRecovery internal constructor(
    private val findOpen: () -> List<OpenSession>,
    private val exitRecords: () -> List<ExitRecord>,
    private val close: (RecoveryAction.CloseInterrupted) -> SessionOutcome,
    private val activeDirName: () -> String?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = ::logWarning,
    private val plan: (List<OpenSession>, List<ExitRecord>, String?) -> List<RecoveryAction> = RecoveryPlanner::plan,
) : RecoveryNotices {

    constructor(
        recovery: SessionRecovery,
        exits: ExitReasonReader,
        activeDirName: () -> String?,
    ) : this(
        findOpen = { recovery.findOpen() },
        exitRecords = { exits.recent() },
        close = { action -> recovery.close(action) },
        activeDirName = activeDirName,
    )

    private val mutableClosed = MutableStateFlow<List<SessionOutcome>>(emptyList())
    private val mutex = Mutex()
    private val done = CompletableDeferred<List<SessionOutcome>>()

    override val closed: StateFlow<List<SessionOutcome>> = mutableClosed.asStateFlow()

    /** True once [run] has finished in this process. */
    val completed: Boolean get() = done.isCompleted

    suspend fun run(): List<SessionOutcome> {
        mutex.withLock {
            if (done.isCompleted) return done.await()
            val outcomes = try {
                withContext(dispatcher) { recoverAll() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Launch recovery failed; open sessions stay open until the next launch (${e.javaClass.simpleName})")
                emptyList()
            }
            if (outcomes.isNotEmpty()) {
                mutableClosed.update { current ->
                    current + outcomes.filter { outcome -> current.none { it.dirName == outcome.dirName } }
                }
            }
            done.complete(outcomes)
        }
        return done.await()
    }

    override fun acknowledge(dirName: String) {
        mutableClosed.update { current -> current.filterNot { it.dirName == dirName } }
    }

    private suspend fun recoverAll(): List<SessionOutcome> {
        val open = findOpen()
        if (open.isEmpty()) return emptyList()
        val exits = try {
            exitRecords()
        } catch (e: Exception) {
            log("Could not read process exit reasons (${e.javaClass.simpleName})")
            emptyList()
        }
        val actions = plan(open, exits, activeDirName())
        val outcomes = ArrayList<SessionOutcome>()
        for (action in actions) {
            currentCoroutineContext().ensureActive()
            if (action !is RecoveryAction.CloseInterrupted) continue
            try {
                outcomes += close(action)
            } catch (e: Exception) {
                log("Could not close an interrupted session; it stays open until the next launch (${e.javaClass.simpleName})")
            }
        }
        return outcomes
    }
}

private const val TAG = "FieldTapRecovery"

private fun logWarning(message: String) {
    Log.w(TAG, message)
}
