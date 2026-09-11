package com.fieldtap.service

import com.fieldtap.app.SessionControl
import com.fieldtap.app.SessionStatus
import com.fieldtap.app.SettingsRepository
import com.fieldtap.app.SoakControl
import com.fieldtap.app.SoakState
import com.fieldtap.app.StartResult
import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.LocationAvailability
import com.fieldtap.core.input.MeasurementInput
import com.fieldtap.core.nettest.NetTestRunner
import com.fieldtap.core.nettest.NetTestTransport
import com.fieldtap.core.nettest.TestSettings
import com.fieldtap.core.radio.ClassifiedAnswer
import com.fieldtap.core.radio.FreshnessEngine
import com.fieldtap.core.session.ExitReasons
import com.fieldtap.core.session.RecorderCommand
import com.fieldtap.core.session.RecorderSnapshot
import com.fieldtap.core.session.SessionCommand
import com.fieldtap.core.session.SessionEffect
import com.fieldtap.core.session.SessionOutcome
import com.fieldtap.core.session.SessionPhase
import com.fieldtap.core.session.SessionState
import com.fieldtap.core.session.SessionStateMachine
import com.fieldtap.core.session.StartPreconditions
import com.fieldtap.core.session.StartRefusal
import com.fieldtap.core.session.StartRequest
import com.fieldtap.core.session.StopCause
import com.fieldtap.core.session.StoragePolicy
import com.fieldtap.core.session.StorageStatus
import com.fieldtap.core.session.Transition
import com.fieldtap.core.soak.SoakEvaluator
import com.fieldtap.core.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What [SessionRuntime] needs from Android, behind an interface so its effect handling runs on a JVM.
 * Production: [AndroidSessionPlatform].
 *
 * Owner: workstream `service-and-tests`.
 */
interface SessionPlatform {
    /**
     * Starts [SessionService] with [action] through `ContextCompat.startForegroundService`. Throws when
     * Android refuses (for example `ForegroundServiceStartNotAllowedException` from the background).
     */
    fun startService(action: String)

    fun preciseLocationGranted(): Boolean

    fun locationEnabled(): Boolean

    /** A warning for logcat. Never pass session content (names, notes, places, directory names). */
    fun log(message: String, error: Throwable?)
}

/**
 * The running service as the runtime sees it. Production: [SessionService].
 *
 * Owner: workstream `service-and-tests`.
 */
interface ServiceHost {
    /** Leaves the foreground, removes the notification, and stops the service unless a newer start is pending. */
    fun stopHosting()
}

/**
 * The recorder as the runtime drives it. Production: [SessionRecorderHandle] over
 * `com.fieldtap.core.session.SessionRecorder`; tests use a fake.
 *
 * Owner: workstream `service-and-tests`.
 */
interface RecorderHandle {
    val snapshot: StateFlow<RecorderSnapshot>

    /**
     * How the session was finalized, however [run] ended (a crash included); null until then, and null for
     * ever when the session files could not be created.
     */
    val outcome: StateFlow<SessionOutcome?>

    /** Thread-safe and non-blocking. Commands submitted before the files exist are queued. Ignored after Stop was processed. */
    fun submit(command: RecorderCommand)

    /**
     * Creates the session files, calls [onFilesCreated] once they exist and before any command is processed,
     * then records until Stop, finalizes and returns the outcome. When the files cannot be created it throws
     * without calling [onFilesCreated]: nothing was recorded. Called once.
     */
    suspend fun run(onFilesCreated: () -> Unit): SessionOutcome
}

/**
 * A session ready to record: its directory is allocated and its recorder built; nothing is written yet.
 *
 * Owner: workstream `service-and-tests`.
 */
class PreparedSession(
    val dirName: String,
    val startedUtcMs: Long,
    val recorder: RecorderHandle,
    /** Where [RecorderHandle.run] runs: the session's single dispatcher. */
    val dispatcher: CoroutineDispatcher,
    /** The test targets and limits in force for this session. */
    val tests: TestSettings,
    /**
     * Removes the allocated directory when the session never started (its files could not be created). It
     * never deletes session files. Blocking file IO.
     */
    val discard: () -> Unit = {},
)

/**
 * Builds sessions. Production: [DefaultSessionFactory].
 *
 * Owner: workstream `service-and-tests`.
 */
interface SessionFactory {
    /** Allocates the directory and builds the recorder. Throws when that fails; nothing is recorded then. */
    suspend fun prepare(request: StartRequest): PreparedSession
}

/**
 * The process-wide owner of the session lifecycle: the `SessionStateMachine` state, the running
 * recorder, its dispatcher and source subscription, the test runner, and the soak test. [ServiceSessionControl]
 * (UI side) and [SessionService] (service side) both talk to it; it applies the state machine's effects.
 *
 * How a session runs:
 * 1. [start] waits for launch recovery, gathers the preconditions off the main thread and dispatches Start.
 *    A BeginRecording effect starts [SessionService] in the foreground (a running soak test is cancelled
 *    first); a watchdog fails the start if the service does not report within [SERVICE_START_TIMEOUT_MS].
 * 2. The service calls `startForeground` and then [onServiceStartCommand]; only then is the directory
 *    allocated and the recorder built ([SessionFactory.prepare]), so nothing is recorded without the
 *    foreground service. The recorder runs on its own dispatcher and receives every hub input from then on.
 * 3. Started is dispatched only once the recorder has created the session files, so [status] says Recording
 *    only when a session exists on disk; the tests start then, when the request asked for them. Files that
 *    cannot be created fail the start (StartFailed, no outcome) and the empty directory is removed.
 * 4. EndRecording submits Stop to the recorder and stops tests and inputs. When the recorder returns, Finished
 *    is dispatched; Publish records the outcome and stops the service.
 * 5. A recorder that ends by itself (storage full, a write failure, a crash) is finished with the outcome it
 *    wrote. Precise location revoked stops with `permission_revoked`; the service destroyed while recording
 *    stops with `service_destroyed`.
 *
 * Commands are applied one at a time under one lock, in order; an effect that dispatches queues its command
 * behind the current one. Every public member is thread-safe.
 *
 * Owner: workstream `service-and-tests`.
 */
class SessionRuntime internal constructor(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val platform: SessionPlatform,
    private val settings: SettingsRepository,
    private val storage: suspend () -> StorageStatus,
    private val sessions: SessionFactory,
    private val inputs: Flow<MeasurementInput>,
    private val radioInputs: Flow<MeasurementInput>,
    private val transport: NetTestTransport,
    private val recoveryGate: suspend () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val reduce: (SessionState, SessionCommand) -> Transition = SessionStateMachine::reduce,
    private val newClassifier: () -> (CellInfoAnswer) -> ClassifiedAnswer = { FreshnessEngine()::classify },
    private val serviceStartTimeoutMs: Long = SERVICE_START_TIMEOUT_MS,
) {
    private val lock = Any()
    private val queue = ArrayDeque<SessionCommand>()
    private var draining = false

    private val mutableState = MutableStateFlow(SessionState())
    private val mutableSnapshot = MutableStateFlow<RecorderSnapshot?>(null)
    private val mutableStatus = MutableStateFlow<SessionStatus>(SessionStatus.Idle)
    private val mutableLastOutcome = MutableStateFlow<SessionOutcome?>(null)
    private val mutableSoak = MutableStateFlow<SoakState>(SoakState.Idle)

    private var host: ServiceHost? = null
    private var active: ActiveSession? = null
    private var beginJob: Job? = null
    private var startWatchdog: Job? = null
    private var pendingStopCause: StopCause? = null
    private var soak: SoakRun? = null
    private var soakWatchdog: Job? = null

    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    val recorderSnapshot: StateFlow<RecorderSnapshot?> = mutableSnapshot.asStateFlow()

    /** [SessionControl.status]: updated in the same step as [state], and with every recorder snapshot. */
    val status: StateFlow<SessionStatus> = mutableStatus.asStateFlow()

    /** The last finished session of this process; kept when the next one starts. */
    val lastOutcome: StateFlow<SessionOutcome?> = mutableLastOutcome.asStateFlow()

    val soakState: StateFlow<SoakState> = mutableSoak.asStateFlow()

    /**
     * Applies a lifecycle command from outside the runtime. Only [SessionCommand.Stop] is accepted, on the same
     * path as [stop] with its cause. The other commands state facts only the runtime can know (a recorder
     * started, a session finished), so they are ignored and logged.
     */
    fun dispatch(command: SessionCommand) {
        when (command) {
            is SessionCommand.Stop -> locked { requestStop(command.cause) }
            else -> platform.log("Ignored an external ${command::class.simpleName} command", null)
        }
    }

    fun attach(service: SessionService): Unit = attachHost(service)

    fun detach(service: SessionService): Unit = detachHost(service)

    /** The running session's directory name, for recovery and delete guards. */
    fun activeDirName(): String? = synchronized(lock) {
        active?.dirName ?: mutableState.value.dirName?.takeIf { mutableState.value.phase != SessionPhase.IDLE }
    }

    /** [SessionControl.start]. */
    suspend fun start(request: StartRequest): StartResult {
        recoveryGate()
        val preconditions = preconditions()
        return synchronized(lock) {
            val transition = applyCommand(SessionCommand.Start(request, preconditions))
            val refusal = transition?.effects?.firstNotNullOfOrNull { (it as? SessionEffect.Refuse)?.refusal }
            when {
                transition == null -> StartResult.Refused(StartRefusal.SESSION_RUNNING)
                refusal != null -> StartResult.Refused(refusal)
                transition.effects.any { it is SessionEffect.BeginRecording } -> StartResult.Accepted
                else -> StartResult.Refused(transition.state.lastRefusal ?: StartRefusal.SESSION_RUNNING)
            }
        }
    }

    /** [SessionControl.mark]: false, and nothing written, unless recording and outside a privacy zone. */
    fun mark(note: String?): Boolean = synchronized(lock) {
        val session = active
        if (session == null || mutableState.value.phase != SessionPhase.RECORDING || session.recorder.snapshot.value.paused) {
            false
        } else {
            val text = note?.trim()?.takeIf { it.isNotEmpty() }
            session.recorder.submit(RecorderCommand.Mark(text, clock.wallMillis()))
            true
        }
    }

    /** [SessionControl.stop]: idempotent. */
    fun stop() {
        locked { requestStop(StopCause.USER) }
    }

    /** [SoakControl.start]: ignored while a session or another soak test runs. */
    fun startSoak(durationMs: Long) {
        locked {
            if (mutableState.value.phase != SessionPhase.IDLE) {
                platform.log("The soak test cannot start while a session runs", null)
                return
            }
            if (mutableSoak.value is SoakState.Running) return
            val duration = durationMs.coerceAtLeast(SoakEvaluator.MIN_DURATION_MS)
            mutableSoak.value = SoakState.Running(elapsedMs = 0, durationMs = duration)
            try {
                platform.startService(SessionService.ACTION_SOAK)
            } catch (e: Exception) {
                platform.log("Android refused to start the soak test service", e)
                mutableSoak.value = SoakState.Idle
                return
            }
            soakWatchdog?.cancel()
            soakWatchdog = scope.launch {
                delay(serviceStartTimeoutMs)
                locked {
                    if (mutableSoak.value is SoakState.Running && soak == null) {
                        platform.log("The soak test service did not start in time", null)
                        mutableSoak.value = SoakState.Idle
                        stopHostIfIdle()
                    }
                }
            }
        }
    }

    /** [SoakControl.cancel]. */
    fun cancelSoak() {
        locked { cancelSoakLocked(stopHost = true) }
    }

    /** The planned duration of the requested or running soak test, for its first notification. */
    fun soakDurationMs(): Long =
        (mutableSoak.value as? SoakState.Running)?.durationMs ?: SoakEvaluator.DEFAULT_DURATION_MS

    internal fun attachHost(host: ServiceHost) {
        locked { this.host = host }
    }

    internal fun detachHost(host: ServiceHost) {
        locked {
            if (this.host !== host) return
            this.host = null
            val phase = mutableState.value.phase
            if (phase == SessionPhase.STARTING || phase == SessionPhase.RECORDING) {
                platform.log("The session service was destroyed while recording", null)
                requestStop(StopCause.SERVICE_DESTROYED)
            }
            val run = soak
            if (run != null) {
                platform.log("The soak test service was destroyed before the test ended", null)
                soak = null
                run.job?.cancel()
                val result = synchronized(run.evaluator) { run.evaluator.result(clock.elapsedRealtimeMillis()) }
                mutableSoak.value = SoakState.Done(result)
            } else if (mutableSoak.value is SoakState.Running) {
                mutableSoak.value = SoakState.Idle
            }
        }
    }

    /**
     * Called by [SessionService.onStartCommand] after `startForeground` succeeded (or for actions that need
     * none). Returns whether the service is still needed; when false the service stops itself.
     */
    internal fun onServiceStartCommand(action: String?): Boolean = synchronized(lock) {
        when (action) {
            SessionService.ACTION_START -> launchBeginIfStarting()
            SessionService.ACTION_SOAK -> launchSoakIfRequested()
            SessionService.ACTION_STOP -> stopFromNotification()
            SessionService.ACTION_MARK -> mark(null)
            else -> Unit
        }
        hostNeeded()
    }

    /** Called by [SessionService] when Android refused `startForeground` for [action]. */
    internal fun onForegroundRefused(action: String?, error: Throwable) {
        locked {
            platform.log("Android refused the foreground service", error)
            if (action == SessionService.ACTION_START &&
                mutableState.value.phase == SessionPhase.STARTING && beginJob == null && active == null
            ) {
                startWatchdog?.cancel()
                startWatchdog = null
                pendingStopCause = null
                applyCommand(SessionCommand.StartFailed(FOREGROUND_REFUSED))
            }
            if (action == SessionService.ACTION_SOAK && soak == null && mutableSoak.value is SoakState.Running) {
                soakWatchdog?.cancel()
                soakWatchdog = null
                mutableSoak.value = SoakState.Idle
            }
        }
    }

    private inline fun locked(block: () -> Unit) {
        synchronized(lock) { block() }
    }

    private fun applyCommand(command: SessionCommand): Transition? = synchronized(lock) {
        if (draining) {
            queue.addLast(command)
            return@synchronized null
        }
        draining = true
        try {
            var first: Transition? = null
            var next: SessionCommand? = command
            while (next != null) {
                val transition = reduce(mutableState.value, next)
                mutableState.value = transition.state
                transition.state.lastOutcome?.let { mutableLastOutcome.value = it }
                publishStatus()
                if (first == null) first = transition
                transition.effects.forEach { applyEffect(it) }
                // A start that failed after the service came up publishes nothing; the service must still go.
                if (transition.state.phase == SessionPhase.IDLE) stopHostIfIdle()
                next = queue.removeFirstOrNull()
            }
            first
        } catch (e: RuntimeException) {
            queue.clear()
            throw e
        } finally {
            draining = false
        }
    }

    private fun applyEffect(effect: SessionEffect) {
        try {
            when (effect) {
                is SessionEffect.Refuse -> stopHostIfIdle()
                is SessionEffect.BeginRecording -> beginRecording()
                is SessionEffect.EndRecording -> endRecording(effect.cause)
                is SessionEffect.Publish -> publish(effect.outcome)
            }
        } catch (e: Exception) {
            platform.log("Could not apply a session effect", e)
        }
    }

    /**
     * A stop that arrives before the recorder exists never creates a session: with no directory yet the start
     * simply fails, and while the directory is being prepared the stop waits for it. Once the recorder exists,
     * the state machine keeps the stop until Started, so the session ends as soon as its files exist. The
     * first cause wins.
     */
    private fun requestStop(cause: StopCause) {
        if (mutableState.value.phase == SessionPhase.STARTING && active == null) {
            if (pendingStopCause == null) pendingStopCause = cause
            if (beginJob == null) {
                startWatchdog?.cancel()
                startWatchdog = null
                pendingStopCause = null
                applyCommand(SessionCommand.StartFailed(STOPPED_BEFORE_START))
            }
            return
        }
        applyCommand(SessionCommand.Stop(cause))
    }

    private fun stopFromNotification() {
        if (mutableState.value.phase != SessionPhase.IDLE) {
            requestStop(StopCause.USER)
        } else if (mutableSoak.value is SoakState.Running) {
            cancelSoakLocked(stopHost = false)
        }
    }

    private fun beginRecording() {
        pendingStopCause = null
        startWatchdog?.cancel()
        startWatchdog = null
        if (mutableSoak.value is SoakState.Running) {
            platform.log("A session start cancels the soak test", null)
            cancelSoakLocked(stopHost = false)
        }
        try {
            platform.startService(SessionService.ACTION_START)
        } catch (e: Exception) {
            platform.log("Android refused to start the session service", e)
            applyCommand(SessionCommand.StartFailed(failureDetail(e)))
            return
        }
        startWatchdog = scope.launch {
            delay(serviceStartTimeoutMs)
            locked {
                if (mutableState.value.phase == SessionPhase.STARTING && beginJob == null && active == null) {
                    platform.log("The session service did not start in time", null)
                    pendingStopCause = null
                    applyCommand(SessionCommand.StartFailed(SERVICE_DID_NOT_START))
                }
            }
        }
    }

    private fun launchBeginIfStarting() {
        val current = mutableState.value
        if (current.phase != SessionPhase.STARTING || beginJob != null || active != null) return
        startWatchdog?.cancel()
        startWatchdog = null
        val request = current.request
        if (request == null) {
            applyCommand(SessionCommand.StartFailed(NO_REQUEST))
            return
        }
        beginJob = scope.launch(io) { begin(request) }
    }

    private suspend fun begin(request: StartRequest) {
        val stoppedEarly = synchronized(lock) {
            val early = pendingStopCause != null
            if (early) {
                beginJob = null
                pendingStopCause = null
                applyCommand(SessionCommand.StartFailed(STOPPED_BEFORE_START))
            }
            early
        }
        if (stoppedEarly) return
        val prepared = try {
            sessions.prepare(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            platform.log("Could not prepare the session directory", e)
            locked {
                beginJob = null
                pendingStopCause = null
                applyCommand(SessionCommand.StartFailed(failureDetail(e)))
            }
            return
        }
        val activated = synchronized(lock) { activate(request, prepared) }
        if (!activated) discardQuietly(prepared)
    }

    /** Starts the recorder of a prepared session; false when the start was abandoned meanwhile. */
    private fun activate(request: StartRequest, prepared: PreparedSession): Boolean {
        beginJob = null
        if (mutableState.value.phase != SessionPhase.STARTING || active != null) {
            platform.log("A prepared session was abandoned before it started", null)
            return false
        }
        val session = ActiveSession(prepared, request.testsEnabled)
        active = session
        mutableSnapshot.value = prepared.recorder.snapshot.value
        pendingStopCause?.let { cause ->
            // Stopped while the directory was prepared: the state machine keeps the stop until Started.
            pendingStopCause = null
            applyCommand(SessionCommand.Stop(cause))
        }
        session.snapshotJob = scope.launch {
            prepared.recorder.snapshot.collect { onSnapshot(session, it) }
        }
        session.inputJob = scope.launch {
            inputs.collect { onInput(session, it) }
        }
        session.runJob = scope.launch(prepared.dispatcher) {
            val result = try {
                Result.success(prepared.recorder.run(onFilesCreated = { onFilesCreated(session) }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            onRecorderFinished(session, result)
        }
        return true
    }

    /** Called by the recorder, on its dispatcher, once the session files exist and before any command is processed. */
    private fun onFilesCreated(session: ActiveSession) {
        val started = try {
            synchronized(lock) {
                if (active !== session || mutableState.value.phase != SessionPhase.STARTING) return
                applyCommand(SessionCommand.Started(session.dirName, session.startedUtcMs))
                if (active === session && mutableState.value.phase == SessionPhase.RECORDING && session.testsEnabled) {
                    session.testsJob = scope.launch(io) { runTests(session) }
                }
                active === session
            }
        } catch (e: RuntimeException) {
            platform.log("Could not mark the session as started", e)
            false
        }
        if (started) scope.launch(io) { rememberSessionStart(session.startedUtcMs) }
    }

    private suspend fun rememberSessionStart(startedUtcMs: Long) {
        try {
            settings.update { it.copy(lastSessionStartedUtcMs = startedUtcMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            platform.log("Could not record the session start time in settings", e)
        }
    }

    private fun onSnapshot(session: ActiveSession, snapshot: RecorderSnapshot) {
        locked {
            if (active !== session) return
            mutableSnapshot.value = snapshot
            publishStatus()
        }
    }

    private fun onInput(session: ActiveSession, input: MeasurementInput) {
        session.recorder.submit(RecorderCommand.Measurement(input))
        if (input !is LocationAvailability || input.preciseLocationGranted) return
        locked {
            if (active === session && mutableState.value.phase == SessionPhase.RECORDING) {
                platform.log("Precise location was revoked; stopping the session", null)
                requestStop(StopCause.PERMISSION_REVOKED)
            }
        }
    }

    private fun onRecorderFinished(session: ActiveSession, result: Result<SessionOutcome>) {
        var unused: PreparedSession? = null
        locked {
            if (active !== session) return
            if (mutableState.value.phase == SessionPhase.STARTING) {
                // The files were never created: nothing was recorded, so there is no session to publish.
                val error = result.exceptionOrNull()
                platform.log("Could not create the session files", error)
                releaseActive(session)
                unused = session.prepared
                applyCommand(SessionCommand.StartFailed(error?.let { failureDetail(it) } ?: FILES_NOT_CREATED))
                return@locked
            }
            val outcome = result.getOrElse { error ->
                platform.log("The recorder stopped with an error", error)
                session.recorder.outcome.value ?: SessionOutcome(
                    dirName = session.dirName,
                    startedUtcMs = session.startedUtcMs,
                    stoppedUtcMs = maxOf(clock.wallMillis(), session.startedUtcMs),
                    stoppedBy = ExitReasons.UNKNOWN,
                    interrupted = true,
                    freshSamples = mutableSnapshot.value?.freshSamples ?: 0,
                )
            }
            val phase = mutableState.value.phase
            if (phase == SessionPhase.RECORDING || phase == SessionPhase.STOPPING) {
                applyCommand(SessionCommand.Finished(outcome))
            }
            if (active === session) {
                platform.log("The state machine did not publish a finished session", null)
                publish(outcome)
            }
        }
        unused?.let { discardQuietly(it) }
    }

    private suspend fun runTests(session: ActiveSession) {
        try {
            NetTestRunner(session.tests, transport, clock).run(
                isPaused = { session.recorder.snapshot.value.paused },
                emit = { record -> session.recorder.submit(RecorderCommand.Traffic(record)) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            platform.log("The ping and download tests stopped with an error", e)
        }
    }

    private fun endRecording(cause: StopCause) {
        val session = active
        if (session == null) {
            platform.log("A stop arrived with no recorder running", null)
            return
        }
        session.testsJob?.cancel()
        session.inputJob?.cancel()
        session.recorder.submit(RecorderCommand.Stop(cause))
    }

    private fun publish(outcome: SessionOutcome) {
        active?.let { releaseActive(it) }
        beginJob = null
        mutableLastOutcome.value = outcome
        publishStatus()
        stopHostIfIdle()
    }

    /** Forgets [session] as the running one and stops everything that fed it, except its own recorder job. */
    private fun releaseActive(session: ActiveSession) {
        if (active === session) active = null
        startWatchdog?.cancel()
        startWatchdog = null
        session.cancelCollectors()
        mutableSnapshot.value = null
    }

    private fun discardQuietly(prepared: PreparedSession) {
        try {
            prepared.discard()
        } catch (e: Exception) {
            platform.log("Could not remove an unused session directory", e)
        }
    }

    private fun launchSoakIfRequested() {
        val requested = mutableSoak.value as? SoakState.Running ?: return
        if (soak != null) return
        soakWatchdog?.cancel()
        soakWatchdog = null
        val now = clock.elapsedRealtimeMillis()
        val run = SoakRun(SoakEvaluator(now, requested.durationMs), now)
        soak = run
        run.job = scope.launch { runSoak(run) }
    }

    private suspend fun runSoak(run: SoakRun) {
        val evaluator = run.evaluator
        val classify = newClassifier()
        coroutineScope {
            val answers = launch {
                radioInputs.collect { input ->
                    // Android's cached list is not an answer the app received while the soak test ran.
                    if (input is CellInfoAnswer && !input.cached) {
                        val classified = try {
                            classify(input)
                        } catch (e: RuntimeException) {
                            null
                        }
                        if (classified != null) synchronized(evaluator) { evaluator.onAnswer(classified) }
                    }
                }
            }
            while (true) {
                val now = clock.elapsedRealtimeMillis()
                synchronized(evaluator) { evaluator.onTick(now) }
                val elapsed = (now - run.startElapsedMs).coerceAtLeast(0)
                if (elapsed >= evaluator.durationMs) break
                updateSoak(run, SoakState.Running(elapsed, evaluator.durationMs))
                delay((SOAK_TICK_MS - elapsed % SOAK_TICK_MS).coerceAtLeast(1))
            }
            answers.cancel()
        }
        val result = synchronized(evaluator) { evaluator.result(clock.elapsedRealtimeMillis()) }
        locked {
            if (soak !== run) return
            soak = null
            mutableSoak.value = SoakState.Done(result)
            stopHostIfIdle()
        }
    }

    private fun updateSoak(run: SoakRun, running: SoakState.Running) {
        locked {
            if (soak === run) mutableSoak.value = running
        }
    }

    private fun cancelSoakLocked(stopHost: Boolean) {
        soakWatchdog?.cancel()
        soakWatchdog = null
        val run = soak
        soak = null
        run?.job?.cancel()
        if (mutableSoak.value is SoakState.Running) mutableSoak.value = SoakState.Idle
        if (stopHost) stopHostIfIdle()
    }

    private fun hostNeeded(): Boolean =
        mutableState.value.phase != SessionPhase.IDLE || mutableSoak.value is SoakState.Running

    private fun stopHostIfIdle() {
        if (hostNeeded()) return
        val current = host ?: return
        host = null
        try {
            current.stopHosting()
        } catch (e: Exception) {
            platform.log("Could not stop the session service", e)
        }
    }

    private fun publishStatus() {
        val current = mutableState.value
        mutableStatus.value = when (current.phase) {
            SessionPhase.IDLE -> SessionStatus.Idle
            SessionPhase.STARTING -> SessionStatus.Starting(current.request ?: StartRequest(name = ""))
            SessionPhase.RECORDING -> SessionStatus.Recording(snapshotFor(current))
            SessionPhase.STOPPING -> SessionStatus.Stopping(snapshotFor(current).copy(stopping = true))
        }
    }

    private fun snapshotFor(current: SessionState): RecorderSnapshot =
        mutableSnapshot.value ?: RecorderSnapshot(
            dirName = current.dirName ?: active?.dirName.orEmpty(),
            startedUtcMs = current.startedUtcMs ?: active?.startedUtcMs ?: clock.wallMillis(),
            elapsedMs = 0,
            servingRat = null,
            servingRsrpDbm = null,
            newestSampleAgeMs = null,
            paused = false,
            freshSamples = 0,
            repeatsDropped = 0,
            eventsWritten = 0,
            trackRows = 0,
            hasRecentFix = false,
            stopping = current.phase == SessionPhase.STOPPING,
        )

    private suspend fun preconditions(): StartPreconditions = withContext(io) {
        val consent = try {
            settings.current().consent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            platform.log("Could not read settings before a start", e)
            null
        }
        val storageStatus = try {
            storage()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            platform.log("Could not measure storage before a start", e)
            StorageStatus(usedBytes = Long.MAX_VALUE, freeBytes = 0, policy = StoragePolicy())
        }
        StartPreconditions(
            consent = consent,
            preciseLocationGranted = platform.preciseLocationGranted(),
            locationEnabled = platform.locationEnabled(),
            storage = storageStatus,
        )
    }

    private class ActiveSession(val prepared: PreparedSession, val testsEnabled: Boolean) {
        val dirName: String get() = prepared.dirName
        val startedUtcMs: Long get() = prepared.startedUtcMs
        val recorder: RecorderHandle get() = prepared.recorder
        val tests: TestSettings get() = prepared.tests
        var snapshotJob: Job? = null
        var inputJob: Job? = null
        var testsJob: Job? = null
        var runJob: Job? = null

        fun cancelCollectors() {
            snapshotJob?.cancel()
            inputJob?.cancel()
            testsJob?.cancel()
        }
    }

    private class SoakRun(val evaluator: SoakEvaluator, val startElapsedMs: Long) {
        var job: Job? = null
    }

    companion object {
        /** How long a start or soak waits for [SessionService] to report before it fails. */
        const val SERVICE_START_TIMEOUT_MS: Long = 10_000

        private const val SOAK_TICK_MS: Long = 1_000
        private const val SERVICE_DID_NOT_START = "service did not start"
        private const val FOREGROUND_REFUSED = "foreground service refused"
        private const val STOPPED_BEFORE_START = "stopped before the session started"
        private const val NO_REQUEST = "no start request"
        private const val FILES_NOT_CREATED = "session files not created"

        private fun failureDetail(error: Throwable): String = error.javaClass.simpleName.ifEmpty { "error" }
    }
}

/**
 * [SessionControl] over the [SessionRuntime].
 *
 * Owner: workstream `service-and-tests`.
 */
class ServiceSessionControl(private val runtime: SessionRuntime) : SessionControl {
    override val status: StateFlow<SessionStatus> get() = runtime.status
    override val lastOutcome: StateFlow<SessionOutcome?> get() = runtime.lastOutcome

    override suspend fun start(request: StartRequest): StartResult = runtime.start(request)

    override fun mark(note: String?): Boolean = runtime.mark(note)

    override fun stop(): Unit = runtime.stop()
}

/**
 * [SoakControl] over the [SessionRuntime].
 *
 * Owner: workstream `service-and-tests`.
 */
class ServiceSoakControl(private val runtime: SessionRuntime) : SoakControl {
    override val state: StateFlow<SoakState> get() = runtime.soakState

    override fun start(durationMs: Long): Unit = runtime.startSoak(durationMs)

    override fun cancel(): Unit = runtime.cancelSoak()
}
