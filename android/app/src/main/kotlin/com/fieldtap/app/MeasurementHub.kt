package com.fieldtap.app

import com.fieldtap.core.input.MeasurementInput
import com.fieldtap.core.live.LiveState
import com.fieldtap.core.time.Clock
import com.fieldtap.platform.location.LocationSource
import com.fieldtap.platform.telephony.TelephonySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One shared stream of [MeasurementInput] for the Live screen and the recorder.
 *
 * [inputs] merges `TelephonySource.inputs()` and `LocationSource.inputs()` and shares them with
 * `SharingStarted.WhileSubscribed(5_000)`: the platform listeners run exactly while someone collects
 * (the Live screen while visible, the service while a session or soak runs). No replay: a new
 * collector starts from the next callback. Collectors must not block; the recorder forwards into its
 * own channel.
 *
 * Owner: workstream `service-and-tests`.
 */
class MeasurementHub(
    private val scope: CoroutineScope,
    private val telephony: TelephonySource,
    private val location: LocationSource,
) {
    val inputs: SharedFlow<MeasurementInput> get() = TODO("service-and-tests")
}

/**
 * [LiveFeed] over the hub: folds inputs with `com.fieldtap.core.live.LiveStateReducer`, ticks once a
 * second, and exposes the result with `stateIn(scope, WhileSubscribed(5_000), LiveState())`.
 *
 * Owner: workstream `service-and-tests`.
 */
class HubLiveFeed(
    private val scope: CoroutineScope,
    private val hub: MeasurementHub,
    private val clock: Clock,
) : LiveFeed {
    override val state: StateFlow<LiveState> get() = TODO("service-and-tests")
}
