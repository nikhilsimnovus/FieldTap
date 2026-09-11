package com.fieldtap.core.session

import com.fieldtap.core.privacy.ConsentRecord

/** What the user asked for on the Start dialog (or the debug automation hook). */
data class StartRequest(
    val name: String,
    val note: String? = null,
    val location: String? = null,
    val testsEnabled: Boolean = false,
    val walkMode: Boolean = false,
)

/** Facts gathered by :app just before a start. */
data class StartPreconditions(
    val consent: ConsentRecord?,
    val preciseLocationGranted: Boolean,
    val locationEnabled: Boolean,
    val storage: StorageStatus,
    /** From `ReadinessPolicy.requiredBeforeSession`: the check is due and has not been run. */
    val readinessRequired: Boolean,
)

enum class StartRefusal {
    BLANK_NAME,
    NO_CONSENT,
    NO_PRECISE_LOCATION,
    LOCATION_OFF,
    STORAGE_FULL,
    SESSION_RUNNING,
    READINESS_REQUIRED,
}

/**
 * Why a running session stopped, as written to `summary.stopped_by`. Exit reasons after a kill are
 * written by recovery instead (see [ExitReasons]). Each token matches `^[a-z0-9_]+$`.
 */
enum class StopCause(val token: String) {
    /** Stop on the Live screen or the notification. */
    USER("user"),

    /** Storage cap or free space reached while recording. */
    STORAGE_FULL("storage_full"),

    /** Precise location was revoked while recording. */
    PERMISSION_REVOKED("permission_revoked"),

    /** The service was destroyed by the system while the process lived. */
    SERVICE_DESTROYED("service_destroyed"),
}

enum class SessionPhase { IDLE, STARTING, RECORDING, STOPPING }

/** A finished session, for the UI and the automation hook. */
data class SessionOutcome(
    val dirName: String,
    val startedUtcMs: Long,
    val stoppedUtcMs: Long,
    val stoppedBy: String,
    /** True when closed by launch recovery rather than by a stop. */
    val interrupted: Boolean,
    val freshSamples: Long,
)

data class SessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val request: StartRequest? = null,
    val dirName: String? = null,
    val startedUtcMs: Long? = null,
    val stopCause: StopCause? = null,
    val lastRefusal: StartRefusal? = null,
    val lastOutcome: SessionOutcome? = null,
)

sealed interface SessionCommand {
    data class Start(val request: StartRequest, val preconditions: StartPreconditions) : SessionCommand

    data class Started(val dirName: String, val startedUtcMs: Long) : SessionCommand

    data class StartFailed(val detail: String) : SessionCommand

    data class Stop(val cause: StopCause) : SessionCommand

    data class Finished(val outcome: SessionOutcome) : SessionCommand
}

sealed interface SessionEffect {
    data class Refuse(val refusal: StartRefusal) : SessionEffect

    /** Allocate the directory, write the initial files, start foreground, start sources and recorder. */
    data class BeginRecording(val request: StartRequest) : SessionEffect

    /** Submit Stop to the recorder; stop tests; the recorder finalizes. */
    data class EndRecording(val cause: StopCause) : SessionEffect

    /** Leave foreground, stop sources, publish the outcome. */
    data class Publish(val outcome: SessionOutcome) : SessionEffect
}

data class Transition(val state: SessionState, val effects: List<SessionEffect>)

/**
 * The session lifecycle, pure. The service applies the effects.
 *
 * - IDLE + Start: refused (state stays IDLE, [SessionEffect.Refuse]) by the first failing check in
 *   [StartRefusal] order: blank name (after trim), consent not current, no precise location, location
 *   off, storage cannot start, readiness required. Otherwise STARTING + BeginRecording.
 * - STARTING/RECORDING/STOPPING + Start: Refuse(SESSION_RUNNING).
 * - STARTING + Started: RECORDING. STARTING + StartFailed: IDLE, no outcome.
 * - RECORDING + Stop: STOPPING + EndRecording. A second Stop is ignored. STARTING + Stop: STOPPING +
 *   EndRecording once Started arrives (the stop is remembered).
 * - STOPPING + Finished: IDLE + Publish, lastOutcome set.
 * - Every other pair: no change, no effects.
 *
 * Tests: every transition above, table-driven.
 *
 * Owner: workstream `session-core`.
 */
object SessionStateMachine {
    fun reduce(state: SessionState, command: SessionCommand): Transition = TODO("session-core")
}
