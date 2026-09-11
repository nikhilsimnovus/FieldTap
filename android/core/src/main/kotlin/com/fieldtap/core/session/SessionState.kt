package com.fieldtap.core.session

import com.fieldtap.core.privacy.Consent
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
    /** The stored consent, or null when the user never agreed. It must match `Consent.CURRENT`. */
    val consent: ConsentRecord?,
    val preciseLocationGranted: Boolean,
    val locationEnabled: Boolean,
    val storage: StorageStatus,
)

/**
 * Why a start was refused, in the order [SessionStateMachine] checks them. A due readiness check is not among
 * them: tapping Start runs the checks and shows a pre-start sheet with "Start anyway" instead
 * (android/ARCHITECTURE.md section 0, decision 7).
 */
enum class StartRefusal {
    /** The name is empty or white space only. */
    BLANK_NAME,

    /** No consent, or consent to an older text than `Consent.CURRENT`. */
    NO_CONSENT,

    /** Precise (fine) location is not granted. */
    NO_PRECISE_LOCATION,

    /** Location services are switched off. */
    LOCATION_OFF,

    /** Sessions use the storage cap, or too little space is free ([StorageStatus.canStart]). */
    STORAGE_FULL,

    /** A session is starting, recording or stopping. */
    SESSION_RUNNING,
}

/**
 * Why a running session stopped, as written to `summary.stopped_by`. Exit reasons after a kill are
 * written by recovery instead (see [ExitReasons]). Each token matches `^[a-z0-9_]+$`.
 */
enum class StopCause(val token: String) {
    /** Stop on the Live screen or the notification. */
    USER("user"),

    /** Storage cap or free space reached while recording, or a session file could not be written. */
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
    /** The stop asked for; while STARTING it is remembered until [SessionCommand.Started] arrives. */
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
 * - IDLE + Start: refused (phase stays IDLE, [SessionState.lastRefusal] set, [SessionEffect.Refuse]) by the
 *   first failing check of [refusal]: blank name (after trim), consent not current, no precise location,
 *   location off, storage cannot start. Otherwise STARTING + BeginRecording. Readiness never refuses
 *   (android/ARCHITECTURE.md section 0, decision 7).
 * - STARTING/RECORDING/STOPPING + Start: Refuse(SESSION_RUNNING), nothing else changes.
 * - STARTING + Started: RECORDING. STARTING + StartFailed: IDLE, no outcome.
 * - RECORDING + Stop: STOPPING + EndRecording. A second Stop is ignored. STARTING + Stop: the stop is
 *   remembered (the first cause wins) and STARTING + Started then gives STOPPING + EndRecording.
 * - STOPPING + Finished: IDLE + Publish, lastOutcome set.
 * - STARTING or RECORDING + Finished: also IDLE + Publish. The recorder can end on its own (storage full,
 *   a write failure, cancellation); a machine that ignored its outcome would stay "running" forever and
 *   refuse every later start.
 * - Every other pair: no change, no effects.
 *
 * Owner: workstream `session-core`.
 */
object SessionStateMachine {
    fun reduce(state: SessionState, command: SessionCommand): Transition = when (command) {
        is SessionCommand.Start -> onStart(state, command.request, command.preconditions)
        is SessionCommand.Started -> onStarted(state, command)
        is SessionCommand.StartFailed -> onStartFailed(state)
        is SessionCommand.Stop -> onStop(state, command.cause)
        is SessionCommand.Finished -> onFinished(state, command.outcome)
    }

    /**
     * The first failing start check, in [StartRefusal] order, or null when a session may start. Never
     * [StartRefusal.SESSION_RUNNING], which depends on the state.
     */
    fun refusal(request: StartRequest, preconditions: StartPreconditions): StartRefusal? = when {
        request.name.isBlank() -> StartRefusal.BLANK_NAME
        !Consent.isCurrent(preconditions.consent) -> StartRefusal.NO_CONSENT
        !preconditions.preciseLocationGranted -> StartRefusal.NO_PRECISE_LOCATION
        !preconditions.locationEnabled -> StartRefusal.LOCATION_OFF
        !preconditions.storage.canStart -> StartRefusal.STORAGE_FULL
        else -> null
    }

    private fun onStart(state: SessionState, request: StartRequest, preconditions: StartPreconditions): Transition {
        if (state.phase != SessionPhase.IDLE) return refuse(state, StartRefusal.SESSION_RUNNING)
        val refusal = refusal(request, preconditions)
        if (refusal != null) return refuse(state, refusal)
        return Transition(
            SessionState(phase = SessionPhase.STARTING, request = request, lastOutcome = state.lastOutcome),
            listOf(SessionEffect.BeginRecording(request)),
        )
    }

    private fun onStarted(state: SessionState, command: SessionCommand.Started): Transition {
        if (state.phase != SessionPhase.STARTING) return unchanged(state)
        val rememberedStop = state.stopCause
        return if (rememberedStop == null) {
            Transition(
                state.copy(
                    phase = SessionPhase.RECORDING,
                    dirName = command.dirName,
                    startedUtcMs = command.startedUtcMs,
                ),
                emptyList(),
            )
        } else {
            Transition(
                state.copy(
                    phase = SessionPhase.STOPPING,
                    dirName = command.dirName,
                    startedUtcMs = command.startedUtcMs,
                ),
                listOf(SessionEffect.EndRecording(rememberedStop)),
            )
        }
    }

    private fun onStartFailed(state: SessionState): Transition =
        if (state.phase != SessionPhase.STARTING) {
            unchanged(state)
        } else {
            Transition(SessionState(lastOutcome = state.lastOutcome), emptyList())
        }

    private fun onStop(state: SessionState, cause: StopCause): Transition = when (state.phase) {
        SessionPhase.STARTING ->
            if (state.stopCause == null) Transition(state.copy(stopCause = cause), emptyList()) else unchanged(state)
        SessionPhase.RECORDING ->
            Transition(
                state.copy(phase = SessionPhase.STOPPING, stopCause = cause),
                listOf(SessionEffect.EndRecording(cause)),
            )
        SessionPhase.IDLE, SessionPhase.STOPPING -> unchanged(state)
    }

    private fun onFinished(state: SessionState, outcome: SessionOutcome): Transition =
        if (state.phase == SessionPhase.IDLE) {
            unchanged(state)
        } else {
            Transition(SessionState(lastOutcome = outcome), listOf(SessionEffect.Publish(outcome)))
        }

    private fun refuse(state: SessionState, refusal: StartRefusal): Transition =
        Transition(state.copy(lastRefusal = refusal), listOf(SessionEffect.Refuse(refusal)))

    private fun unchanged(state: SessionState): Transition = Transition(state, emptyList())
}
