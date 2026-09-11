package com.fieldtap.app

import android.util.Log
import com.fieldtap.core.input.MeasurementInput
import com.fieldtap.core.live.LiveState
import com.fieldtap.core.live.LiveStateReducer
import com.fieldtap.core.time.Clock
import com.fieldtap.platform.location.LocationSource
import com.fieldtap.platform.telephony.TelephonySource
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/**
 * One shared stream of [MeasurementInput] for the Live screen and the recorder.
 *
 * [inputs] merges `TelephonySource.inputs()` and `LocationSource.inputs()` and shares them with
 * `SharingStarted.WhileSubscribed(5_000)`: the platform listeners run exactly while someone collects
 * (the Live screen while visible, the service while a session runs), and stop 5 s after the last collector
 * leaves. No replay: a new collector starts from the next callback. Collectors must not block; the recorder
 * forwards into its own channel.
 *
 * [radioInputs] is the telephony stream alone, for the soak test. Both streams share one registration per
 * source, so the soak test and the Live screen never register the telephony listeners twice.
 *
 * A source that fails is logged and restarted after a short, growing pause (at most 30 s), so one adapter bug
 * cannot silence a running session. A source that ends by itself (location without the permission, for
 * example) is subscribed again after [SOURCE_RESUBSCRIBE_MS] while someone still collects. Nothing is started
 * at construction.
 *
 * Owner: workstream `service-and-tests`.
 */
class MeasurementHub internal constructor(
    scope: CoroutineScope,
    radioSource: Flow<MeasurementInput>,
    locationSource: Flow<MeasurementInput>,
    stopTimeoutMs: Long = STOP_TIMEOUT_MS,
    private val onSourceError: (Throwable) -> Unit = {},
) {
    constructor(scope: CoroutineScope, telephony: TelephonySource, location: LocationSource) : this(
        scope = scope,
        radioSource = flow { emitAll(telephony.inputs()) },
        locationSource = flow { emitAll(location.inputs()) },
        onSourceError = ::logSourceError,
    )

    private val radioShared: SharedFlow<MeasurementInput> =
        radioSource.restarting().shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), replay = 0)

    private val locationShared: SharedFlow<MeasurementInput> =
        locationSource.restarting().shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), replay = 0)

    val inputs: SharedFlow<MeasurementInput> =
        merge(radioShared, locationShared)
            .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = stopTimeoutMs), replay = 0)

    /** Telephony inputs only; collecting it does not start the location source. */
    val radioInputs: SharedFlow<MeasurementInput> get() = radioShared

    private fun Flow<MeasurementInput>.restarting(): Flow<MeasurementInput> {
        val source = this
        return flow {
            while (true) {
                emitAll(source)
                // The source ended by itself, for example location without the permission. Subscribe
                // again after a pause, so a permission granted later is picked up while collectors stay.
                delay(SOURCE_RESUBSCRIBE_MS)
            }
        }.retryWhen { cause, attempt ->
            if (cause is CancellationException) return@retryWhen false
            onSourceError(cause)
            delay((RETRY_BASE_MS shl attempt.coerceAtMost(5L).toInt()).coerceAtMost(RETRY_MAX_MS))
            true
        }
    }

    companion object {
        /** How long the sources keep running after the last collector leaves. */
        const val STOP_TIMEOUT_MS: Long = 5_000

        /** How long a source that ended by itself (not by failing) waits before it is subscribed again. */
        const val SOURCE_RESUBSCRIBE_MS: Long = 10_000

        private const val RETRY_BASE_MS: Long = 1_000
        private const val RETRY_MAX_MS: Long = 30_000
    }
}

/**
 * [LiveFeed] over the hub: folds inputs with `com.fieldtap.core.live.LiveStateReducer`, ticks once a
 * second, and exposes the result with `stateIn(scope, WhileSubscribed(0), LiveState())` on
 * `Dispatchers.Default`.
 *
 * - The fold continues from the last state when a collector returns (after a rotation, or back from another
 *   screen), so the chart keeps its 5-minute window.
 * - The feed stops folding as soon as nobody collects; the hub keeps the sources for its own 5 s, which is
 *   what [LiveFeed] promises ("they stop 5 s after the last collector leaves").
 * - A reducer that throws on one input is logged and that input skipped; the screen never freezes on it.
 *
 * Owner: workstream `service-and-tests`.
 */
class HubLiveFeed internal constructor(
    scope: CoroutineScope,
    inputs: Flow<MeasurementInput>,
    private val clock: Clock,
    private val reduce: (LiveState, MeasurementInput) -> LiveState,
    private val tick: (LiveState, Long) -> LiveState,
    private val onError: (Throwable) -> Unit = {},
    tickPeriodMs: Long = TICK_PERIOD_MS,
    context: CoroutineContext = Dispatchers.Default,
) : LiveFeed {

    constructor(scope: CoroutineScope, hub: MeasurementHub, clock: Clock) : this(scope, hub.inputs, clock, LiveStateReducer())

    internal constructor(
        scope: CoroutineScope,
        inputs: Flow<MeasurementInput>,
        clock: Clock,
        reducer: LiveStateReducer,
    ) : this(
        scope = scope,
        inputs = inputs,
        clock = clock,
        reduce = reducer::reduce,
        tick = reducer::tick,
        onError = ::logFeedError,
    )

    private val holder = MutableStateFlow(LiveState())

    override val state: StateFlow<LiveState> =
        merge(
            inputs.map<MeasurementInput, LiveSignal> { LiveSignal.Input(it) },
            ticks(tickPeriodMs),
        )
            .map { signal -> fold(signal) }
            .flowOn(context)
            .stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), LiveState())

    private fun fold(signal: LiveSignal): LiveState {
        val previous = holder.value
        val next = try {
            when (signal) {
                is LiveSignal.Input -> reduce(previous, signal.input)
                is LiveSignal.Tick -> tick(previous, clock.elapsedRealtimeMillis())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            previous
        }
        holder.value = next
        return next
    }

    private fun ticks(periodMs: Long): Flow<LiveSignal> = flow {
        while (true) {
            emit(LiveSignal.Tick)
            delay(periodMs)
        }
    }

    private sealed interface LiveSignal {
        data class Input(val input: MeasurementInput) : LiveSignal

        data object Tick : LiveSignal
    }

    companion object {
        const val TICK_PERIOD_MS: Long = 1_000
    }
}

private const val TAG = "FieldTapHub"

private fun logSourceError(error: Throwable) {
    Log.w(TAG, "A measurement source failed; restarting it", error)
}

private fun logFeedError(error: Throwable) {
    Log.w(TAG, "The Live screen reducer failed on one input", error)
}
