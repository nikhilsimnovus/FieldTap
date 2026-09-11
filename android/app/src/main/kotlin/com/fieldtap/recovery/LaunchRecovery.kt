package com.fieldtap.recovery

import com.fieldtap.app.RecoveryNotices
import com.fieldtap.core.session.SessionOutcome
import com.fieldtap.core.session.SessionRecovery
import com.fieldtap.platform.exit.ExitReasonReader
import kotlinx.coroutines.flow.StateFlow

/**
 * Closes sessions a killed process left open, once per process start, before any new session.
 *
 * [run]: `SessionRecovery.findOpen()`, `ExitReasonReader.recent()`, `RecoveryPlanner.plan(open, exits,
 * activeDirName())`, then `SessionRecovery.close` for each CloseInterrupted, on a background
 * dispatcher. Failures are logged (no session content in the log) and leave the session open for the
 * next launch. Closed sessions appear in [closed] until acknowledged.
 *
 * Owner: workstream `service-and-tests`.
 */
class LaunchRecovery(
    private val recovery: SessionRecovery,
    private val exits: ExitReasonReader,
    private val activeDirName: () -> String?,
) : RecoveryNotices {
    suspend fun run(): List<SessionOutcome> = TODO("service-and-tests")

    override val closed: StateFlow<List<SessionOutcome>> get() = TODO("service-and-tests")

    override fun acknowledge(dirName: String): Unit = TODO("service-and-tests")
}
