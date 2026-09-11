package com.fieldtap.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import com.fieldtap.app.AppGraph
import com.fieldtap.app.SessionStatus
import com.fieldtap.core.live.ChartPoint
import com.fieldtap.core.live.LiveState
import com.fieldtap.core.session.SessionOutcome
import com.fieldtap.core.session.StartRefusal
import com.fieldtap.core.session.StartRequest
import kotlinx.coroutines.flow.StateFlow

/** Everything the Live screen renders. */
data class LiveUiState(
    val live: LiveState,
    val status: SessionStatus,
    val walkMode: Boolean,
    val testsDefaultOn: Boolean,
    /** The last refusal, until dismissed. */
    val refusal: StartRefusal?,
    /** Sessions closed by launch recovery, shown as a banner until acknowledged. */
    val recovered: List<SessionOutcome>,
)

/**
 * Owner: workstream `ui-session`.
 */
class LiveViewModel(private val graph: AppGraph) : ViewModel() {
    val state: StateFlow<LiveUiState> get() = TODO("ui-session")

    fun setWalkMode(on: Boolean): Unit = TODO("ui-session")

    /** Called from the Start dialog while the screen is visible. */
    fun start(request: StartRequest): Unit = TODO("ui-session")

    fun mark(note: String?): Unit = TODO("ui-session")

    fun stop(): Unit = TODO("ui-session")

    fun dismissRefusal(): Unit = TODO("ui-session")

    fun acknowledgeRecovered(dirName: String): Unit = TODO("ui-session")
}

/**
 * The Live screen: serving tile (RAT, PCI, ARFCN, band, RSRP/RSRQ/SINR, PLMN) with its age badge; the
 * NSA NR leg; neighbours; the 5-minute RSRP and SINR chart; the cadence indicator ("2 s cadence" or
 * "10 s cadence", with the reason: screen off, Wi-Fi on while not charging); service, data and 5G icon
 * state; GPS state; the walk-mode toggle; Start (with name, note, place and tests opt-in), Mark (with a
 * note) and Stop; a line when a listener was refused ("Phone permission not granted: no push updates").
 * With the screen off on battery, signal-strength fill stops, and pocket mode says so.
 * Language never implies decoding or signalling.
 *
 * Owner: workstream `ui-session`.
 */
@Composable
fun LiveScreen(
    viewModel: LiveViewModel,
    onOpenSessions: () -> Unit,
    onOpenReadiness: () -> Unit,
    onOpenProbe: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TODO("ui-session")
}

/**
 * RSRP (dBm, -140..-40) and SINR (dB, -25..40) against time over the last 5 minutes, drawn on a Compose
 * Canvas (no chart library). Points only from fresh samples; gaps are not bridged.
 *
 * Owner: workstream `ui-session`.
 */
@Composable
fun SignalChart(
    rsrp: List<ChartPoint>,
    sinr: List<ChartPoint>,
    nowElapsedMs: Long,
    modifier: Modifier = Modifier,
) {
    TODO("ui-session")
}

/**
 * Walk mode: while [enabled], keeps the screen on (`FLAG_KEEP_SCREEN_ON`), dims it
 * (`WindowManager.LayoutParams.screenBrightness` low) and uses a dark surface, and prompts to turn Wi-Fi
 * off or plug in so Android's 2 s interval applies. Restores the window on dispose. No wake lock.
 *
 * Owner: workstream `ui-session`.
 */
@Composable
fun WalkModeEffect(enabled: Boolean) {
    TODO("ui-session")
}
