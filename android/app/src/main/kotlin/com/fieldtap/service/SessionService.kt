package com.fieldtap.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.lifecycle.LifecycleService
import com.fieldtap.app.SessionControl
import com.fieldtap.app.SessionStatus
import com.fieldtap.app.SoakControl
import com.fieldtap.app.SoakState
import com.fieldtap.core.session.RecorderSnapshot
import com.fieldtap.core.session.SessionCommand
import com.fieldtap.core.session.SessionOutcome
import com.fieldtap.core.session.SessionState
import com.fieldtap.core.session.StartRequest
import com.fieldtap.app.StartResult
import kotlinx.coroutines.flow.StateFlow

/**
 * The location foreground service that keeps a session (or a soak test) alive after Home or
 * screen-off. Started only by [ServiceSessionControl.start] or [ServiceSoakControl.start] while an
 * activity is visible; never from the background, never at boot.
 *
 * Contract:
 * - [onStartCommand] with [ACTION_START]: call `ServiceCompat.startForeground(this, NOTIFICATION_ID,
 *   notification, FOREGROUND_SERVICE_TYPE_LOCATION)` at once, then let [SessionRuntime] allocate the
 *   directory, build the recorder on its own dispatcher, subscribe it to the MeasurementHub, and start
 *   the NetTestRunner when the request asked for tests. Returns `START_NOT_STICKY`: a restart would be
 *   invisible, and location cannot start then; recovery is explicit instead.
 * - [ACTION_MARK] (notification action): a marker with no note. [ACTION_STOP]: stop with cause `user`.
 * - [ACTION_SOAK]: foreground with the soak notification; runs the telephony ticker only.
 * - The notification ([SessionNotification]) shows elapsed time, serving RSRP, the newest sample's age,
 *   and Stop and Mark; updated at most every 2 s.
 * - [onDestroy] while recording: the recorder stops with `service_destroyed`.
 * - A refused POST_NOTIFICATIONS does not stop the service.
 *
 * Owner: workstream `service-and-tests`.
 */
class SessionService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        TODO("service-and-tests")
    }

    companion object {
        const val ACTION_START: String = "com.fieldtap.service.action.START"
        const val ACTION_MARK: String = "com.fieldtap.service.action.MARK"
        const val ACTION_STOP: String = "com.fieldtap.service.action.STOP"
        const val ACTION_SOAK: String = "com.fieldtap.service.action.SOAK"
        const val NOTIFICATION_ID: Int = 1
        const val CHANNEL_ID: String = "session"
    }
}

/**
 * The session notification. Channel [SessionService.CHANNEL_ID], low importance, no sound. Actions are
 * PendingIntents to [SessionService] (immutable), so no exported receiver exists.
 *
 * Owner: workstream `service-and-tests`.
 */
class SessionNotification(private val context: Context) {
    fun ensureChannel(): Unit = TODO("service-and-tests")

    fun recording(snapshot: RecorderSnapshot): Notification = TODO("service-and-tests")

    fun soak(elapsedMs: Long, durationMs: Long): Notification = TODO("service-and-tests")
}

/**
 * The process-wide owner of the session lifecycle: the `SessionStateMachine` state, the running
 * `SessionRecorder`, its dispatcher and source subscription, and the test runner. [ServiceSessionControl]
 * (UI side) and [SessionService] (service side) both talk to it; it applies the state machine's effects.
 *
 * Owner: workstream `service-and-tests`.
 */
class SessionRuntime(private val context: Context) {
    val state: StateFlow<SessionState> get() = TODO("service-and-tests")

    val recorderSnapshot: StateFlow<RecorderSnapshot?> get() = TODO("service-and-tests")

    fun dispatch(command: SessionCommand): Unit = TODO("service-and-tests")

    fun attach(service: SessionService): Unit = TODO("service-and-tests")

    fun detach(service: SessionService): Unit = TODO("service-and-tests")

    /** The running session's directory name, for recovery and delete guards. */
    fun activeDirName(): String? = TODO("service-and-tests")
}

/** Owner: workstream `service-and-tests`. */
class ServiceSessionControl(private val runtime: SessionRuntime) : SessionControl {
    override val status: StateFlow<SessionStatus> get() = TODO("service-and-tests")
    override val lastOutcome: StateFlow<SessionOutcome?> get() = TODO("service-and-tests")

    override suspend fun start(request: StartRequest): StartResult = TODO("service-and-tests")

    override fun mark(note: String?): Boolean = TODO("service-and-tests")

    override fun stop(): Unit = TODO("service-and-tests")
}

/** Owner: workstream `service-and-tests`. */
class ServiceSoakControl(private val runtime: SessionRuntime) : SoakControl {
    override val state: StateFlow<SoakState> get() = TODO("service-and-tests")

    override fun start(durationMs: Long): Unit = TODO("service-and-tests")

    override fun cancel(): Unit = TODO("service-and-tests")
}
