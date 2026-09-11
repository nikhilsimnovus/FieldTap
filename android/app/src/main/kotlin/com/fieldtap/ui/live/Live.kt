package com.fieldtap.ui.live

import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fieldtap.R
import com.fieldtap.app.AppGraph
import com.fieldtap.app.SessionStatus
import com.fieldtap.app.StartResult
import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.RadioListener
import com.fieldtap.core.live.AgeBadge
import com.fieldtap.core.live.ChartPoint
import com.fieldtap.core.live.LiveCell
import com.fieldtap.core.live.LiveState
import com.fieldtap.core.live.LiveStateReducer
import com.fieldtap.core.nettest.TestSettings
import com.fieldtap.core.privacy.Consent
import com.fieldtap.core.readiness.SettingsTarget
import com.fieldtap.core.session.RecorderSnapshot
import com.fieldtap.core.session.SessionOutcome
import com.fieldtap.core.session.StartRefusal
import com.fieldtap.core.session.StartRequest
import com.fieldtap.core.settings.AppSettings
import com.fieldtap.format.Rat
import com.fieldtap.format.ServingRat
import com.fieldtap.platform.Permissions
import com.fieldtap.ui.FieldTapTheme
import com.fieldtap.ui.common.DisplayTime
import com.fieldtap.ui.common.SystemSettings
import com.fieldtap.ui.common.contentWidth
import com.fieldtap.ui.common.exitReasonWords
import com.fieldtap.ui.common.findActivity
import com.fieldtap.ui.common.ratName
import com.fieldtap.ui.common.screenGutter
import com.fieldtap.ui.common.signalQualityLabels
import com.fieldtap.ui.components.CadenceIndicator
import com.fieldtap.ui.components.CellSignalRow
import com.fieldtap.ui.components.ChartMath
import com.fieldtap.ui.components.FieldTapPreviews
import com.fieldtap.ui.components.FieldTapTopBar
import com.fieldtap.ui.components.KeyValueRow
import com.fieldtap.ui.components.LimitsStatementCard
import com.fieldtap.ui.components.MetricEmphasis
import com.fieldtap.ui.components.MetricGrid
import com.fieldtap.ui.components.MetricTile
import com.fieldtap.ui.components.ReadinessProblem
import com.fieldtap.ui.components.ReadinessSheet
import com.fieldtap.ui.components.SectionCard
import com.fieldtap.ui.components.SectionDivider
import com.fieldtap.ui.components.SessionButton
import com.fieldtap.ui.components.SessionButtonState
import com.fieldtap.ui.components.SignalBar
import com.fieldtap.ui.components.SignalChartLabels
import com.fieldtap.ui.components.SignalHistoryChart
import com.fieldtap.ui.components.SignalQualityLabels
import com.fieldtap.ui.components.StatusBanner
import com.fieldtap.ui.components.StatusChip
import com.fieldtap.ui.components.ToggleRow
import com.fieldtap.ui.components.TopBarAction
import com.fieldtap.ui.components.statusIcon
import com.fieldtap.ui.settings.TestSettingsRules
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.Formats
import com.fieldtap.ui.theme.SignalMetric
import com.fieldtap.ui.theme.SignalScale
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone
import com.fieldtap.ui.theme.tabular
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    /** The Start flow: checks running, the pre-start sheet, or the start call in flight. */
    val prestart: PrestartState = PrestartState.None,
    /** A one-off message for the snackbar, until [LiveViewModel.consumeMessage] is called with its id. */
    val message: LiveMessageEvent? = null,
    /** The test targets in Settings, named in the Start dialog; null until settings were read. */
    val tests: TestSettings? = null,
) {
    /** Mark writes an event only while recording outside a privacy zone. */
    val markEnabled: Boolean get() = LivePresentation.markAllowed(status)

    /** The running session is paused because a fix showed the phone inside a privacy zone. */
    val pausedInZone: Boolean get() = LivePresentation.pausedInZone(status)

    /** The running session writes nothing until a location fix shows the phone outside its privacy zones. */
    val waitingForLocation: Boolean get() = LivePresentation.waitingForLocation(status)

    /** Location services are off: nothing new can be measured, and a running session records nothing. */
    val locationOff: Boolean get() = LivePresentation.locationOff(live, status)
}

/** A short confirmation or failure shown in the snackbar. */
enum class LiveMessage { MARKED, NOT_RECORDING, PAUSED, START_FAILED, STOP_FAILED }

/** A [LiveMessage] with an id, so the same message twice is shown twice. */
data class LiveMessageEvent(val id: Long, val message: LiveMessage)

/**
 * The Live screen's view model. Collecting [state] starts the radio and location sources (through
 * `AppGraph.live`); they stop 5 s after the screen stops collecting, unless a session runs.
 *
 * Start follows android/ARCHITECTURE.md decision 7: [start] runs the readiness checks and reads consent
 * and storage; with no problem it calls `SessionControl.start` at once, otherwise it shows the pre-start
 * sheet ([PrestartState.Review]), from which [startAnyway] starts when nothing blocks. A refusal from
 * `SessionControl.start` is kept in [LiveUiState.refusal] and, when the sheet can name it, shown there.
 *
 * Every call is made on the main thread; slow work happens inside the facades, which move it off.
 *
 * Owner: workstream `ui-session`.
 */
class LiveViewModel(private val graph: AppGraph) : ViewModel() {
    private val walkModeChoice = MutableStateFlow<Boolean?>(null)
    private val refusal = MutableStateFlow<StartRefusal?>(null)
    private val prestart = MutableStateFlow<PrestartState>(PrestartState.None)
    private val message = MutableStateFlow<LiveMessageEvent?>(null)
    private var nextMessageId = 0L
    private var startJob: Job? = null

    private val storedSettings: Flow<AppSettings?> = graph.settings.settings
        .map<AppSettings, AppSettings?> { it }
        .catch { emit(null) }

    private val screenLocal: Flow<ScreenLocal> =
        combine(walkModeChoice, refusal, prestart, message) { walk, refused, flow, event -> ScreenLocal(walk, refused, flow, event) }

    val state: StateFlow<LiveUiState> =
        combine(graph.live.state, graph.sessionControl.status, storedSettings, screenLocal, graph.recovery.closed) { live, status, settings, local, recovered ->
            LiveUiState(
                live = live,
                status = status,
                walkMode = local.walkModeChoice ?: settings?.walkModeDefault ?: false,
                testsDefaultOn = settings?.testsDefaultOn ?: false,
                refusal = local.refusal,
                recovered = recovered,
                prestart = local.prestart,
                message = local.message,
                tests = settings?.tests,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = LiveUiState(
                live = graph.live.state.value,
                status = graph.sessionControl.status.value,
                walkMode = false,
                testsDefaultOn = false,
                refusal = null,
                recovered = graph.recovery.closed.value,
            ),
        )

    /** Turns walk mode on or off for this screen; until then it follows the Settings default. */
    fun setWalkMode(on: Boolean) {
        walkModeChoice.value = on
    }

    /** Called from the Start dialog while the screen is visible. */
    fun start(request: StartRequest) {
        if (startJob?.isActive == true) return
        val normalized = request.copy(
            name = request.name.trim(),
            note = request.note?.trim()?.takeIf { it.isNotEmpty() },
            location = request.location?.trim()?.takeIf { it.isNotEmpty() },
        )
        if (graph.sessionControl.status.value != SessionStatus.Idle) {
            refusal.value = StartRefusal.SESSION_RUNNING
            return
        }
        if (normalized.name.isEmpty()) {
            refusal.value = StartRefusal.BLANK_NAME
            return
        }
        refusal.value = null
        startJob = viewModelScope.launch {
            prestart.value = PrestartState.Checking(normalized)
            val issues = findIssues(refusal = null)
            if (issues.isEmpty()) {
                begin(normalized)
            } else {
                prestart.value = PrestartState.Review(normalized, issues)
            }
        }
    }

    /** Starts the reviewed session when no problem on the sheet blocks. */
    fun startAnyway() {
        val review = prestart.value as? PrestartState.Review ?: return
        if (!review.canStartAnyway || startJob?.isActive == true) return
        startJob = viewModelScope.launch { begin(review.request) }
    }

    /** Runs the checks again while the pre-start sheet shows, for example back from a settings screen. */
    fun recheckReadiness() {
        val review = prestart.value as? PrestartState.Review ?: return
        if (startJob?.isActive == true) return
        startJob = viewModelScope.launch {
            val issues = findIssues(refusal = null)
            if (prestart.value is PrestartState.Review) {
                prestart.value = PrestartState.Review(review.request, issues)
                if (issues.none { it.blocking }) refusal.value = null
            }
        }
    }

    /** Closes the pre-start sheet, or abandons the checks; a start call already made is not cancelled. */
    fun dismissPrestart() {
        when (prestart.value) {
            is PrestartState.Checking, is PrestartState.Review -> {
                startJob?.cancel()
                prestart.value = PrestartState.None
            }
            PrestartState.None, is PrestartState.Starting -> Unit
        }
    }

    fun mark(note: String?) {
        val status = graph.sessionControl.status.value
        if (!LivePresentation.markAllowed(status)) {
            post(if (LivePresentation.pausedInZone(status)) LiveMessage.PAUSED else LiveMessage.NOT_RECORDING)
            return
        }
        val accepted = try {
            graph.sessionControl.mark(note?.trim()?.takeIf { it.isNotEmpty() })
        } catch (e: RuntimeException) {
            false
        }
        post(
            when {
                accepted -> LiveMessage.MARKED
                LivePresentation.pausedInZone(graph.sessionControl.status.value) -> LiveMessage.PAUSED
                else -> LiveMessage.NOT_RECORDING
            },
        )
    }

    fun stop() {
        try {
            graph.sessionControl.stop()
        } catch (e: RuntimeException) {
            post(LiveMessage.STOP_FAILED)
        }
    }

    fun dismissRefusal() {
        refusal.value = null
    }

    fun acknowledgeRecovered(dirName: String) {
        graph.recovery.acknowledge(dirName)
    }

    /** Clears the message with [id] once the snackbar has shown it; a newer message stays. */
    fun consumeMessage(id: Long) {
        message.update { current -> if (current?.id == id) null else current }
    }

    /** The app's wall clock, for the Start dialog's suggested name. */
    fun nowWallMs(): Long = graph.clock.wallMillis()

    private suspend fun begin(request: StartRequest) {
        prestart.value = PrestartState.Starting(request)
        val outcomeBefore = graph.sessionControl.lastOutcome.value
        when (val result = attempt { graph.sessionControl.start(request) }) {
            null -> {
                prestart.value = PrestartState.None
                post(LiveMessage.START_FAILED)
            }
            StartResult.Accepted -> {
                prestart.value = PrestartState.None
                refusal.value = null
                watchAcceptedStart(outcomeBefore)
            }
            is StartResult.Refused -> {
                refusal.value = result.refusal
                val issues = if (PrestartPlanner.kindOf(result.refusal) != null) findIssues(result.refusal) else emptyList()
                prestart.value = if (issues.isEmpty()) PrestartState.None else PrestartState.Review(request, issues)
            }
        }
    }

    /**
     * An accepted start can still fail before it records: Android refuses the foreground service, the service
     * does not come up in time, or the session files cannot be created. `SessionControl` then moves from
     * Starting back to Idle without a new outcome, which on its own would look as if nothing happened, so the
     * screen says the start failed.
     */
    private fun watchAcceptedStart(outcomeBefore: SessionOutcome?) {
        viewModelScope.launch {
            val settled = graph.sessionControl.status.first { it !is SessionStatus.Starting }
            if (settled == SessionStatus.Idle && graph.sessionControl.lastOutcome.value == outcomeBefore) {
                post(LiveMessage.START_FAILED)
            }
        }
    }

    private suspend fun findIssues(refusal: StartRefusal?): List<PrestartIssue> {
        val report = attempt { graph.readiness.check() }
        val consentCurrent = attempt { graph.settings.current() }?.let { Consent.isCurrent(it.consent) }
        val storageCanStart = attempt { graph.sessions.storage() }?.canStart
        return PrestartPlanner.issues(report, consentCurrent, storageCanStart, refusal, checkFailed = report == null)
    }

    private fun post(kind: LiveMessage) {
        nextMessageId += 1
        message.value = LiveMessageEvent(nextMessageId, kind)
    }

    private data class ScreenLocal(
        val walkModeChoice: Boolean?,
        val refusal: StartRefusal?,
        val prestart: PrestartState,
        val message: LiveMessageEvent?,
    )

    private companion object {
        const val STOP_TIMEOUT_MS: Long = 5_000
    }
}

/** [block]'s value, or null when it throws anything but cancellation. */
private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
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
 * @param onOpenDisclosure opens the consent notice, the fix for a start refused for want of consent.
 * @param onOpenSession opens one session's detail, from the banner of a session that recovery closed.
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
    onOpenDisclosure: () -> Unit = onOpenSettings,
    onOpenSession: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.recheckReadiness() }
    WalkModeEffect(enabled = state.walkMode)
    val actions = LiveActions(
        onStart = viewModel::start,
        onStartAnyway = viewModel::startAnyway,
        onDismissPrestart = viewModel::dismissPrestart,
        onRecheck = viewModel::recheckReadiness,
        onMark = viewModel::mark,
        onStop = viewModel::stop,
        onWalkModeChange = viewModel::setWalkMode,
        onDismissRefusal = viewModel::dismissRefusal,
        onAcknowledgeRecovered = viewModel::acknowledgeRecovered,
        onConsumeMessage = viewModel::consumeMessage,
        onOpenSessions = onOpenSessions,
        onOpenReadiness = onOpenReadiness,
        onOpenProbe = onOpenProbe,
        onOpenSettings = onOpenSettings,
        onOpenAbout = onOpenAbout,
        onOpenDisclosure = onOpenDisclosure,
        onOpenSession = onOpenSession,
        nowWallMs = viewModel::nowWallMs,
    )
    // Walk mode forces the dark surface; otherwise this follows the system like the activity's theme.
    FieldTapTheme(darkTheme = state.walkMode || isSystemInDarkTheme()) {
        LiveContent(state = state, actions = actions, modifier = modifier)
    }
}

/**
 * RSRP (dBm, -140..-40) and SINR (dB, -25..40) against time over the last 5 minutes, drawn on a Compose
 * Canvas (no chart library). Points only from fresh samples; gaps are not bridged.
 *
 * @param gapThresholdMs lines break where two points are further apart; pass
 *   `ChartMath.gapThresholdMs(live.shortInterval)` so a gap on the 2 s interval is not drawn as data.
 *
 * Owner: workstream `ui-session`.
 */
@Composable
fun SignalChart(
    rsrp: List<ChartPoint>,
    sinr: List<ChartPoint>,
    nowElapsedMs: Long,
    modifier: Modifier = Modifier,
    gapThresholdMs: Long = ChartMath.DEFAULT_GAP_THRESHOLD_MS,
) {
    val windowMs = LiveStateReducer.WINDOW_MS
    val rsrpStats = ChartMath.stats(rsrp, nowElapsedMs, windowMs)
    val sinrStats = ChartMath.stats(sinr, nowElapsedMs, windowMs)
    val rsrpSummary = if (rsrpStats != null) {
        stringResource(R.string.chart_summary_rsrp, rsrpStats.latest, rsrpStats.min, rsrpStats.max)
    } else {
        stringResource(R.string.chart_summary_rsrp_empty)
    }
    val sinrSummary = if (sinrStats != null) {
        stringResource(R.string.chart_summary_sinr, sinrStats.latest, sinrStats.min, sinrStats.max)
    } else {
        stringResource(R.string.chart_summary_sinr_empty)
    }
    SignalHistoryChart(
        rsrp = rsrp,
        sinr = sinr,
        nowElapsedMs = nowElapsedMs,
        labels = SignalChartLabels(
            rsrpTitle = stringResource(R.string.chart_rsrp),
            rsrpUnit = stringResource(R.string.unit_dbm),
            sinrTitle = stringResource(R.string.chart_sinr),
            sinrUnit = stringResource(R.string.unit_db),
            windowStart = stringResource(R.string.chart_window_start),
            windowEnd = stringResource(R.string.chart_window_end),
            noData = stringResource(R.string.chart_no_data),
        ),
        summary = "$rsrpSummary $sinrSummary",
        modifier = modifier,
        windowMs = windowMs,
        gapThresholdMs = gapThresholdMs,
    )
}

/**
 * Walk mode: while [enabled], keeps the screen on (`FLAG_KEEP_SCREEN_ON`) with a dark surface, and prompts to turn
 * Wi-Fi off or plug in so Android's 2 s interval applies. Clears the flag on dispose. No wake lock.
 *
 * It never sets the window brightness. A window brightness overrides adaptive brightness and the user's own slider,
 * so a fixed low level would leave the numbers unreadable in daylight, where walks happen; the dark surface is what
 * saves power on a screen that stays on.
 *
 * This effect owns the window flag; the dark surface and the Wi-Fi prompt are drawn by [LiveScreen]. Outside an
 * activity (previews) it does nothing.
 *
 * Owner: workstream `ui-session`.
 */
@Composable
fun WalkModeEffect(enabled: Boolean) {
    val activity = LocalActivity.current
    DisposableEffect(activity, enabled) {
        val window = activity?.window
        if (!enabled || window == null) {
            onDispose { }
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
}

/** Longest name, note or place the Start dialog accepts. */
private const val MAX_TEXT_LENGTH: Int = 120

/** Shown for a value Android did not report. Unknown is never 0. */
private const val UNKNOWN_VALUE: String = "—"

/** Everything the Live content can ask for, so the stateless content can be previewed. */
private data class LiveActions(
    val onStart: (StartRequest) -> Unit,
    val onStartAnyway: () -> Unit,
    val onDismissPrestart: () -> Unit,
    val onRecheck: () -> Unit,
    val onMark: (String?) -> Unit,
    val onStop: () -> Unit,
    val onWalkModeChange: (Boolean) -> Unit,
    val onDismissRefusal: () -> Unit,
    val onAcknowledgeRecovered: (String) -> Unit,
    val onConsumeMessage: (Long) -> Unit,
    val onOpenSessions: () -> Unit,
    val onOpenReadiness: () -> Unit,
    val onOpenProbe: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenAbout: () -> Unit,
    val onOpenDisclosure: () -> Unit,
    val onOpenSession: (String) -> Unit,
    val nowWallMs: () -> Long,
)

@Composable
private fun LiveContent(state: LiveUiState, actions: LiveActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var startDialogOpen by rememberSaveable { mutableStateOf(false) }
    var markDialogOpen by rememberSaveable { mutableStateOf(false) }
    var stopDialogOpen by rememberSaveable { mutableStateOf(false) }
    val buttonState = LivePresentation.buttonState(state.status, state.prestart)

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Permissions.FINE_LOCATION] != true) {
            // Android shows no dialog once the user chose "Don't allow" twice; only app settings can help then.
            val canAskAgain = context.findActivity()?.shouldShowRequestPermissionRationale(Permissions.FINE_LOCATION) == true
            if (!canAskAgain) SystemSettings.open(context, SettingsTarget.APP_DETAILS)
        }
        actions.onRecheck()
    }
    val requestPreciseLocation: () -> Unit = { locationPermission.launch(Permissions.LOCATION.toTypedArray()) }

    val event = state.message
    val eventText = event?.let { liveMessageText(it.message) }
    LaunchedEffect(event?.id) {
        if (event != null && eventText != null) {
            try {
                snackbarHostState.showSnackbar(eventText)
            } finally {
                actions.onConsumeMessage(event.id)
            }
        }
    }
    LaunchedEffect(buttonState) {
        if (buttonState != SessionButtonState.RECORDING) {
            markDialogOpen = false
            stopDialogOpen = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                FieldTapTopBar(
                    title = stringResource(R.string.live_title),
                    actions = {
                        TopBarAction(
                            icon = FieldTapIcons.Sessions,
                            contentDescription = stringResource(R.string.live_action_sessions),
                            onClick = actions.onOpenSessions,
                        )
                        LiveOverflowMenu(actions)
                    },
                )
                // Pinned under the bar while a session runs: whether it is collecting stays in view however far the list scrolls.
                LivePresentation.recordingStrip(state.status, state.live)?.let { RecordingStatusStrip(it) }
            }
        },
        bottomBar = {
            LiveActionBar(
                state = state,
                buttonState = buttonState,
                onStart = { startDialogOpen = true },
                onStop = { stopDialogOpen = true },
                onMark = { markDialogOpen = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LiveList(
            state = state,
            actions = actions,
            requestPreciseLocation = requestPreciseLocation,
            modifier = Modifier.padding(padding),
        )
    }

    if (startDialogOpen) {
        StartSessionDialog(
            tests = state.tests,
            testsDefaultOn = state.testsDefaultOn,
            walkMode = state.walkMode,
            nowWallMs = actions.nowWallMs,
            onDismiss = { startDialogOpen = false },
            onStart = { request ->
                startDialogOpen = false
                actions.onStart(request)
            },
        )
    }
    if (markDialogOpen) {
        MarkDialog(
            onDismiss = { markDialogOpen = false },
            onMark = { note ->
                markDialogOpen = false
                actions.onMark(note)
            },
        )
    }
    if (stopDialogOpen) {
        StopDialog(
            onDismiss = { stopDialogOpen = false },
            onConfirm = {
                stopDialogOpen = false
                actions.onStop()
            },
        )
    }
    val review = state.prestart as? PrestartState.Review
    if (review != null) {
        PrestartSheet(review = review, actions = actions, requestPreciseLocation = requestPreciseLocation)
    }
}

@Composable
private fun LiveOverflowMenu(actions: LiveActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TopBarAction(
            icon = FieldTapIcons.MoreVert,
            contentDescription = stringResource(R.string.live_action_more),
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            OverflowItem(R.string.live_menu_readiness, FieldTapIcons.CheckCircle) {
                expanded = false
                actions.onOpenReadiness()
            }
            OverflowItem(R.string.live_menu_probe, FieldTapIcons.Search) {
                expanded = false
                actions.onOpenProbe()
            }
            OverflowItem(R.string.live_menu_settings, FieldTapIcons.Tune) {
                expanded = false
                actions.onOpenSettings()
            }
            OverflowItem(R.string.live_menu_about, FieldTapIcons.Info) {
                expanded = false
                actions.onOpenAbout()
            }
        }
    }
}

@Composable
private fun OverflowItem(@StringRes label: Int, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = stringResource(label)) },
        onClick = onClick,
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
    )
}

/** What the Live list items need, gathered once, so the phone layout and the wide layout share the same items. */
private class LiveParts(
    val state: LiveUiState,
    val actions: LiveActions,
    val labels: SignalQualityLabels,
    val notes: List<ListenerNote>,
    val locationMissing: Boolean,
    @StringRes val refusalText: Int?,
    val requestPreciseLocation: () -> Unit,
    val openLocationSettings: () -> Unit,
    val openWifiSettings: () -> Unit,
)

/**
 * Upright on a phone: the serving cell, its 5-minute trend, the cadence and the network and GPS state first, so what an
 * engineer glances at while walking is on the first screen; the cell details and neighbours follow. From
 * [Sizes.WideLayoutMinWidth] (landscape phones, tablets) two panes: the serving cell and its details on one side, the
 * trend, cadence and network state on the other, both in view.
 */
@Composable
private fun LiveList(
    state: LiveUiState,
    actions: LiveActions,
    requestPreciseLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val notes = LivePresentation.listenerNotes(state.live.listeners)
    val requestMissing: (ListenerNote) -> Boolean = { it.listener == RadioListener.CELL_INFO_REQUEST && it.outcome == ListenerOutcome.MISSING_PERMISSION }
    val parts = LiveParts(
        state = state,
        actions = actions,
        labels = signalQualityLabels(),
        notes = notes.filterNot(requestMissing),
        locationMissing = notes.any(requestMissing),
        refusalText = refusalMessageRes(state.refusal),
        requestPreciseLocation = requestPreciseLocation,
        openLocationSettings = { SystemSettings.open(context, SettingsTarget.LOCATION_SOURCE) },
        openWifiSettings = { SystemSettings.open(context, SettingsTarget.WIFI) },
    )
    val gutter = screenGutter()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth >= Sizes.WideLayoutMinWidth) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.SectionGap),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(vertical = Spacing.Lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.SectionGap),
                ) {
                    bannerItems(parts)
                    servingTilesItem(parts)
                    servingCardItem(parts)
                    neighboursItem(parts)
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(vertical = Spacing.Lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.SectionGap),
                ) {
                    chartItem(parts)
                    cadenceItem(parts)
                    networkItem(parts)
                    limitsItem(parts)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = gutter, vertical = Spacing.Lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.SectionGap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                bannerItems(parts)
                servingTilesItem(parts)
                chartItem(parts)
                cadenceItem(parts)
                networkItem(parts)
                servingCardItem(parts)
                neighboursItem(parts)
                limitsItem(parts)
            }
        }
    }
}

/** Recovered sessions, a refusal, then what stops measuring (location off, no permission, waiting for a fix, a zone), then the Wi-Fi prompt. */
private fun LazyListScope.bannerItems(parts: LiveParts) {
    val state = parts.state
    val actions = parts.actions
    items(state.recovered, key = { "recovered:" + it.dirName }) { outcome ->
        val name = outcome.name ?: outcome.dirName
        StatusBanner(
            title = stringResource(if (outcome.interrupted) R.string.live_recovered_title else R.string.live_recovered_stopped_title),
            message = if (outcome.interrupted) {
                stringResource(R.string.live_recovered_message, name, exitReasonWords(outcome.stoppedBy))
            } else {
                stringResource(R.string.live_recovered_stopped_message, name)
            },
            tone = StatusTone.WARNING,
            actionLabel = stringResource(R.string.live_action_open_session),
            onAction = {
                actions.onAcknowledgeRecovered(outcome.dirName)
                actions.onOpenSession(outcome.dirName)
            },
            dismissContentDescription = stringResource(R.string.action_dismiss),
            onDismiss = { actions.onAcknowledgeRecovered(outcome.dirName) },
            modifier = Modifier.contentWidth(),
        )
    }
    val refusalText = parts.refusalText
    if (refusalText != null) {
        item(key = "refusal") {
            StatusBanner(
                message = stringResource(refusalText),
                tone = StatusTone.WARNING,
                dismissContentDescription = stringResource(R.string.action_dismiss),
                onDismiss = actions.onDismissRefusal,
                modifier = Modifier.contentWidth(),
            )
        }
    }
    if (state.locationOff) {
        item(key = "location-off") {
            StatusBanner(
                title = stringResource(R.string.issue_location_off_title),
                message = stringResource(
                    if (state.status is SessionStatus.Recording) R.string.live_location_off_recording else R.string.issue_location_off_detail,
                ),
                tone = StatusTone.ERROR,
                icon = FieldTapIcons.Location,
                actionLabel = stringResource(R.string.issue_location_off_fix),
                onAction = parts.openLocationSettings,
                modifier = Modifier.contentWidth(),
            )
        }
    }
    if (parts.locationMissing) {
        item(key = "location-missing") {
            StatusBanner(
                message = stringResource(R.string.live_note_request_missing),
                tone = StatusTone.ERROR,
                icon = FieldTapIcons.Location,
                actionLabel = stringResource(R.string.live_action_allow_location),
                onAction = parts.requestPreciseLocation,
                modifier = Modifier.contentWidth(),
            )
        }
    }
    if (state.waitingForLocation && !state.locationOff) {
        item(key = "waiting-for-location") {
            StatusBanner(
                message = stringResource(R.string.live_waiting_location_banner),
                tone = StatusTone.INFO,
                icon = FieldTapIcons.Shield,
                modifier = Modifier.contentWidth(),
            )
        }
    }
    if (state.pausedInZone) {
        item(key = "paused") {
            StatusBanner(
                message = stringResource(R.string.live_paused_banner),
                tone = StatusTone.INFO,
                icon = FieldTapIcons.Shield,
                modifier = Modifier.contentWidth(),
            )
        }
    }
    if (LivePresentation.showWalkModeWifiPrompt(state.walkMode, state.live.conditions)) {
        item(key = "walk-wifi") {
            StatusBanner(
                message = stringResource(R.string.live_walk_wifi_prompt),
                tone = StatusTone.WARNING,
                icon = FieldTapIcons.Wifi,
                actionLabel = stringResource(R.string.live_action_wifi_settings),
                onAction = parts.openWifiSettings,
                modifier = Modifier.contentWidth(),
            )
        }
    }
}

private fun LazyListScope.servingTilesItem(parts: LiveParts) {
    item(key = "serving-tiles") {
        ServingTiles(live = parts.state.live, labels = parts.labels, modifier = Modifier.contentWidth())
    }
}

private fun LazyListScope.servingCardItem(parts: LiveParts) {
    if (parts.state.live.serving != null) {
        item(key = "serving-card") {
            ServingCard(live = parts.state.live, labels = parts.labels, modifier = Modifier.contentWidth())
        }
    }
}

private fun LazyListScope.chartItem(parts: LiveParts) {
    item(key = "chart") {
        val live = parts.state.live
        SectionCard(title = stringResource(R.string.live_section_chart), modifier = Modifier.contentWidth()) {
            SignalChart(
                rsrp = live.rsrpSeries,
                sinr = live.sinrSeries,
                nowElapsedMs = live.nowElapsedMs,
                gapThresholdMs = ChartMath.gapThresholdMs(live.shortInterval),
            )
        }
    }
}

private fun LazyListScope.cadenceItem(parts: LiveParts) {
    item(key = "cadence") {
        CadenceCard(
            live = parts.state.live,
            walkMode = parts.state.walkMode,
            notes = parts.notes,
            onWalkModeChange = parts.actions.onWalkModeChange,
            modifier = Modifier.contentWidth(),
        )
    }
}

private fun LazyListScope.networkItem(parts: LiveParts) {
    item(key = "network") {
        NetworkCard(live = parts.state.live, modifier = Modifier.contentWidth())
    }
}

private fun LazyListScope.neighboursItem(parts: LiveParts) {
    item(key = "neighbours") {
        NeighboursCard(neighbours = parts.state.live.neighbours, labels = parts.labels, modifier = Modifier.contentWidth())
    }
}

private fun LazyListScope.limitsItem(parts: LiveParts) {
    if (parts.state.live.serving == null) {
        item(key = "limits") {
            LimitsStatementCard(
                title = stringResource(R.string.live_limits_title),
                statement = stringResource(R.string.limits_statement),
                modifier = Modifier.contentWidth(),
            )
        }
    }
}

@Composable
private fun ServingTiles(live: LiveState, labels: SignalQualityLabels, modifier: Modifier = Modifier) {
    val serving = live.serving
    val rsrpQuality = SignalScale.quality(SignalMetric.RSRP, serving?.rsrp)
    val rsrqQuality = SignalScale.quality(SignalMetric.RSRQ, serving?.rsrq)
    val sinrQuality = SignalScale.quality(SignalMetric.SINR, serving?.sinr)
    val ageMs = live.servingAgeMs
    val absence = LivePresentation.servingAbsence(live)
    val problem = LivePresentation.servingProblem(live)
    val ageText = if (ageMs != null) stringResource(R.string.age_old, Formats.ageSeconds(ageMs)) else absenceBadge(absence)
    // With a cell: its identity and operator, then why it may be ageing. Without: why there is none.
    val supportingText = if (serving != null) {
        listOfNotNull(servingSummary(serving), problem?.let { servingProblemLine(it) }).joinToString("\n")
    } else {
        absenceDetail(absence)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
        MetricTile(
            label = stringResource(rsrpLabelRes(serving?.rat)),
            value = serving?.rsrp?.toString(),
            unit = stringResource(R.string.unit_dbm),
            emphasis = MetricEmphasis.HERO,
            quality = rsrpQuality,
            qualityLabel = if (serving != null) labels.of(rsrpQuality) else null,
            ageText = ageText,
            badge = live.badge,
            supportingText = supportingText,
            placeholder = UNKNOWN_VALUE,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SignalBar(metric = SignalMetric.RSRP, value = serving?.rsrp, stateDescription = labels.of(rsrpQuality))
        }
        MetricGrid {
            MetricTile(
                label = stringResource(R.string.live_rsrq),
                value = serving?.rsrq?.toString(),
                unit = stringResource(R.string.unit_db),
                quality = rsrqQuality,
                qualityLabel = if (serving != null) labels.of(rsrqQuality) else null,
                badge = live.badge,
                placeholder = UNKNOWN_VALUE,
            )
            MetricTile(
                label = stringResource(R.string.live_sinr),
                value = serving?.sinr?.toString(),
                unit = stringResource(R.string.unit_db),
                quality = sinrQuality,
                qualityLabel = if (serving != null) labels.of(sinrQuality) else null,
                badge = live.badge,
                placeholder = UNKNOWN_VALUE,
            )
        }
    }
}

@Composable
private fun ServingCard(live: LiveState, labels: SignalQualityLabels, modifier: Modifier = Modifier) {
    val serving = live.serving ?: return
    SectionCard(
        title = stringResource(R.string.live_section_serving),
        icon = FieldTapIcons.SignalBars,
        modifier = modifier,
    ) {
        val network = LivePresentation.servingNetwork(serving, live.nsaLeg)
        if (network != null) {
            KeyValueRow(key = stringResource(R.string.live_row_network), value = stringResource(networkRes(network)), tabular = false)
        }
        KeyValueRow(key = stringResource(R.string.live_row_operator), value = operatorText(serving), tabular = false)
        KeyValueRow(key = stringResource(R.string.live_row_pci), value = serving.pci?.toString() ?: UNKNOWN_VALUE)
        KeyValueRow(
            key = stringResource(R.string.live_row_channel),
            value = serving.arfcn?.let { stringResource(channelRes(serving.rat), it) } ?: UNKNOWN_VALUE,
        )
        KeyValueRow(
            key = stringResource(R.string.live_row_band),
            value = serving.band?.let { if (serving.rat == Rat.NR) "n$it" else it.toString() } ?: UNKNOWN_VALUE,
        )
        val report = LivePresentation.newerSignalReport(serving, live.signal, live.nowElapsedMs)
        if (report != null) {
            KeyValueRow(
                key = stringResource(R.string.live_row_signal_report),
                value = stringResource(R.string.live_signal_report_value, report.rsrpDbm, Formats.ageSeconds(report.ageMs)),
            )
        }
        val leg = live.nsaLeg
        if (leg != null) {
            val quality = SignalScale.quality(SignalMetric.RSRP, leg.rsrp)
            SectionDivider()
            CellSignalRow(
                title = stringResource(R.string.live_nsa_leg),
                valueText = leg.rsrp?.toString(),
                unit = stringResource(R.string.unit_dbm),
                quality = quality,
                qualityLabel = labels.of(quality),
                supportingText = cellIdentity(leg),
                placeholder = UNKNOWN_VALUE,
            )
        }
    }
}

@Composable
private fun CadenceCard(
    live: LiveState,
    walkMode: Boolean,
    notes: List<ListenerNote>,
    onWalkModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = stringResource(R.string.live_section_cadence),
        icon = FieldTapIcons.Timer,
        modifier = modifier,
    ) {
        CadenceIndicator(
            intervalText = stringResource(
                when (live.shortInterval) {
                    true -> R.string.live_cadence_short
                    false -> R.string.live_cadence_long
                    null -> R.string.live_cadence_unknown
                },
            ),
            shortInterval = live.shortInterval,
            reason = stringResource(cadenceReasonRes(LivePresentation.cadenceReason(live.conditions))),
            modifier = Modifier.fillMaxWidth(),
        )
        KeyValueRow(
            key = stringResource(R.string.live_row_measured_interval),
            value = live.recentFreshIntervalMs?.let { stringResource(R.string.seconds_value, DisplayTime.seconds(it)) } ?: UNKNOWN_VALUE,
        )
        ToggleRow(
            title = stringResource(R.string.live_walk_mode),
            checked = walkMode,
            onCheckedChange = onWalkModeChange,
            supportingText = stringResource(R.string.live_walk_mode_supporting),
            icon = FieldTapIcons.Walk,
        )
        for (note in notes) {
            ListenerNoteLine(note)
        }
    }
}

@Composable
private fun ListenerNoteLine(note: ListenerNote) {
    val family = FieldTapDesign.colors.status(note.tone)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        Icon(
            imageVector = statusIcon(note.tone),
            contentDescription = null,
            tint = family.color,
            modifier = Modifier
                .padding(top = Spacing.Xxs)
                .size(Sizes.IconSmall),
        )
        Text(
            text = listenerNoteText(note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NetworkCard(live: LiveState, modifier: Modifier = Modifier) {
    SectionCard(title = stringResource(R.string.live_section_network), modifier = modifier) {
        val service = LivePresentation.serviceChip(live.service)
        val data = LivePresentation.dataChip(live.data)
        val dataNetwork = LivePresentation.dataNetworkName(live.data)
        val fiveG = LivePresentation.fiveGIcon(live.display)
        val gps = LivePresentation.gpsChip(live.lastFix, live.nowElapsedMs)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            StatusChip(text = stringResource(serviceRes(service)), tone = service.tone)
            StatusChip(
                text = if (data == DataChip.CONNECTED && dataNetwork != null) {
                    stringResource(R.string.live_data_connected_type, dataNetwork)
                } else {
                    stringResource(dataRes(data))
                },
                tone = data.tone,
                icon = FieldTapIcons.Transfer,
            )
            if (fiveG != null) {
                StatusChip(
                    text = stringResource(if (fiveG) R.string.live_5g_icon_on else R.string.live_5g_icon_off),
                    tone = if (fiveG) StatusTone.INFO else StatusTone.NEUTRAL,
                    icon = FieldTapIcons.SignalBars,
                )
            }
            StatusChip(
                text = gpsText(gps),
                tone = gps.tone,
                icon = if (gps is GpsChip.Fix) FieldTapIcons.GpsFixed else FieldTapIcons.GpsOff,
            )
        }
        val gnss = live.gnss
        if (gnss != null) {
            KeyValueRow(
                key = stringResource(R.string.live_row_satellites),
                value = stringResource(R.string.live_satellites_value, gnss.satellitesUsedInFix, gnss.satellitesVisible),
            )
        }
    }
}

@Composable
private fun NeighboursCard(neighbours: List<LiveCell>, labels: SignalQualityLabels, modifier: Modifier = Modifier) {
    SectionCard(
        title = stringResource(R.string.live_section_neighbours),
        subtitle = if (neighbours.isEmpty()) null else pluralStringResource(R.plurals.live_neighbours_count, neighbours.size, neighbours.size),
        modifier = modifier,
    ) {
        if (neighbours.isEmpty()) {
            Text(
                text = stringResource(R.string.live_neighbours_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            neighbours.forEachIndexed { index, cell ->
                if (index > 0) SectionDivider()
                val quality = SignalScale.quality(SignalMetric.RSRP, cell.rsrp)
                CellSignalRow(
                    title = neighbourTitle(cell),
                    valueText = cell.rsrp?.toString(),
                    unit = stringResource(R.string.unit_dbm),
                    quality = quality,
                    qualityLabel = labels.of(quality),
                    supportingText = neighbourSupporting(cell),
                    placeholder = UNKNOWN_VALUE,
                )
            }
        }
    }
}

@Composable
private fun LiveActionBar(
    state: LiveUiState,
    buttonState: SessionButtonState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMark: () -> Unit,
) {
    val elapsedMs = (state.status as? SessionStatus.Recording)?.snapshot?.elapsedMs
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = screenGutter(), vertical = Spacing.Md),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.contentWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SessionButton(
                    state = buttonState,
                    startLabel = stringResource(R.string.live_start),
                    stopLabel = stringResource(R.string.live_stop),
                    onStart = onStart,
                    onStop = onStop,
                    modifier = Modifier.weight(1f),
                    busyLabel = stringResource(
                        when {
                            buttonState == SessionButtonState.STOPPING -> R.string.live_stopping
                            state.prestart is PrestartState.Checking -> R.string.live_checking
                            else -> R.string.live_starting
                        },
                    ),
                    recordingLabel = stringResource(R.string.live_recording),
                    elapsedText = elapsedMs?.let { Formats.elapsed(it) },
                )
                if (buttonState == SessionButtonState.RECORDING) {
                    val unavailable = stringResource(R.string.live_mark_unavailable)
                    FilledTonalButton(
                        onClick = onMark,
                        enabled = state.markEnabled,
                        modifier = Modifier
                            .heightIn(min = Sizes.PrimaryButtonHeight)
                            .then(if (state.markEnabled) Modifier else Modifier.semantics { contentDescription = unavailable }),
                    ) {
                        Icon(imageVector = FieldTapIcons.Flag, contentDescription = null, modifier = Modifier.size(Sizes.Icon))
                        Spacer(modifier = Modifier.width(Spacing.Sm))
                        Text(text = stringResource(R.string.live_mark))
                    }
                }
            }
        }
    }
}

@Composable
private fun StartSessionDialog(
    tests: TestSettings?,
    testsDefaultOn: Boolean,
    walkMode: Boolean,
    nowWallMs: () -> Long,
    onDismiss: () -> Unit,
    onStart: (StartRequest) -> Unit,
) {
    val openedAtMs = remember { nowWallMs() }
    val suggestedName = stringResource(R.string.live_default_name, DisplayTime.time(openedAtMs))
    var name by rememberSaveable { mutableStateOf(suggestedName) }
    var note by rememberSaveable { mutableStateOf("") }
    var place by rememberSaveable { mutableStateOf("") }
    var testsEnabled by rememberSaveable { mutableStateOf(testsDefaultOn) }
    val nameValid = name.isNotBlank()
    val nameError = stringResource(R.string.live_field_name_error)
    val placeSupporting = stringResource(R.string.live_field_place_supporting)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onStart(
                        StartRequest(
                            name = name.trim(),
                            note = note.trim().ifEmpty { null },
                            location = place.trim().ifEmpty { null },
                            testsEnabled = testsEnabled,
                            walkMode = walkMode,
                        ),
                    )
                },
                enabled = nameValid,
            ) {
                Text(text = stringResource(R.string.live_start_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) }
        },
        title = { Text(text = stringResource(R.string.live_start_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_TEXT_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = stringResource(R.string.live_field_name)) },
                    supportingText = { if (!nameValid) Text(text = nameError) },
                    isError = !nameValid,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(MAX_TEXT_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = stringResource(R.string.live_field_note)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = place,
                    onValueChange = { place = it.take(MAX_TEXT_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = stringResource(R.string.live_field_place)) },
                    supportingText = { Text(text = placeSupporting) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget)
                        .toggleable(value = testsEnabled, role = Role.Checkbox, onValueChange = { testsEnabled = it }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    Checkbox(checked = testsEnabled, onCheckedChange = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.live_tests_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = tests?.let { testsTargetsText(it) } ?: stringResource(R.string.live_tests_supporting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun MarkDialog(onDismiss: () -> Unit, onMark: (String?) -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onMark(note.trim().ifEmpty { null }) }) {
                Text(text = stringResource(R.string.live_mark_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) }
        },
        icon = { Icon(imageVector = FieldTapIcons.Flag, contentDescription = null) },
        title = { Text(text = stringResource(R.string.live_mark_dialog_title)) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(MAX_TEXT_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = stringResource(R.string.live_mark_note)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                singleLine = true,
            )
        },
    )
}

@Composable
private fun StopDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = stringResource(R.string.live_stop_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) }
        },
        icon = { Icon(imageVector = FieldTapIcons.Stop, contentDescription = null) },
        title = { Text(text = stringResource(R.string.live_stop_dialog_title)) },
        text = { Text(text = stringResource(R.string.live_stop_dialog_text)) },
    )
}

@Composable
private fun PrestartSheet(review: PrestartState.Review, actions: LiveActions, requestPreciseLocation: () -> Unit) {
    val context = LocalContext.current
    val blocked = !review.canStartAnyway
    val empty = review.issues.isEmpty()
    val problems = review.issues.map { issue ->
        issueProblem(issue = issue, onFix = issueFix(issue, context, actions, requestPreciseLocation))
    }
    ReadinessSheet(
        title = stringResource(
            when {
                blocked -> R.string.prestart_title_blocked
                empty -> R.string.prestart_title_ready
                else -> R.string.prestart_title
            },
        ),
        problems = problems,
        startAnywayLabel = stringResource(if (empty) R.string.live_start else R.string.prestart_start_anyway),
        cancelLabel = stringResource(R.string.action_cancel),
        onStartAnyway = actions.onStartAnyway,
        onDismissRequest = {
            actions.onDismissPrestart()
            actions.onDismissRefusal()
        },
        message = when {
            blocked -> null
            empty -> stringResource(R.string.prestart_message_ready)
            else -> stringResource(R.string.prestart_message)
        },
        blockedMessage = stringResource(R.string.prestart_blocked_message),
        blockingTag = stringResource(R.string.prestart_tag_required),
        adviceTag = stringResource(R.string.prestart_tag_recommended),
    )
}

/** The words, icon and fix label of an issue kind. */
private data class IssueCopy(
    @StringRes val title: Int,
    @StringRes val detail: Int,
    @StringRes val fix: Int?,
    val icon: ImageVector,
)

private fun issueCopy(kind: PrestartIssueKind): IssueCopy = when (kind) {
    PrestartIssueKind.NO_CONSENT ->
        IssueCopy(R.string.issue_no_consent_title, R.string.issue_no_consent_detail, R.string.issue_no_consent_fix, FieldTapIcons.Shield)
    PrestartIssueKind.NO_PRECISE_LOCATION ->
        IssueCopy(R.string.issue_precise_location_title, R.string.issue_precise_location_detail, R.string.live_action_allow_location, FieldTapIcons.Location)
    PrestartIssueKind.LOCATION_OFF ->
        IssueCopy(R.string.issue_location_off_title, R.string.issue_location_off_detail, R.string.issue_location_off_fix, FieldTapIcons.Location)
    PrestartIssueKind.STORAGE_FULL ->
        IssueCopy(R.string.issue_storage_full_title, R.string.issue_storage_full_detail, R.string.issue_storage_full_fix, FieldTapIcons.Storage)
    PrestartIssueKind.NOTIFICATIONS_OFF ->
        IssueCopy(R.string.issue_notifications_title, R.string.issue_notifications_detail, R.string.issue_notifications_fix, FieldTapIcons.Notifications)
    PrestartIssueKind.BATTERY_OPTIMISATION ->
        IssueCopy(R.string.issue_battery_title, R.string.issue_battery_detail, R.string.issue_battery_fix, FieldTapIcons.Battery)
    PrestartIssueKind.BACKGROUND_RESTRICTED ->
        IssueCopy(R.string.issue_background_title, R.string.issue_background_detail, R.string.issue_app_settings_fix, FieldTapIcons.Battery)
    PrestartIssueKind.STANDBY_BUCKET ->
        IssueCopy(R.string.issue_standby_title, R.string.issue_standby_detail, R.string.issue_app_settings_fix, FieldTapIcons.Timer)
    PrestartIssueKind.NO_SIM ->
        IssueCopy(R.string.issue_no_sim_title, R.string.issue_no_sim_detail, null, FieldTapIcons.Sim)
    PrestartIssueKind.WIFI_ON_BATTERY ->
        IssueCopy(R.string.issue_wifi_title, R.string.issue_wifi_detail, R.string.live_action_wifi_settings, FieldTapIcons.Wifi)
    PrestartIssueKind.AGGRESSIVE_OEM ->
        IssueCopy(R.string.issue_oem_title, R.string.issue_oem_detail, R.string.issue_open_readiness_fix, FieldTapIcons.Warning)
    PrestartIssueKind.CHECK_FAILED ->
        IssueCopy(R.string.issue_check_failed_title, R.string.issue_check_failed_detail, R.string.issue_open_readiness_fix, FieldTapIcons.Info)
}

@Composable
private fun issueProblem(issue: PrestartIssue, onFix: (() -> Unit)?): ReadinessProblem {
    val copy = issueCopy(issue.kind)
    val fixLabel = copy.fix?.let { stringResource(it) }
    return ReadinessProblem(
        id = issue.kind.name,
        title = stringResource(copy.title),
        detail = stringResource(copy.detail),
        blocking = issue.blocking,
        icon = copy.icon,
        fixLabel = if (onFix != null) fixLabel else null,
        onFix = if (fixLabel != null) onFix else null,
    )
}

/** What the fix button of [issue] does: a system settings screen, an app screen, or the permission prompt. */
private fun issueFix(
    issue: PrestartIssue,
    context: Context,
    actions: LiveActions,
    requestPreciseLocation: () -> Unit,
): (() -> Unit)? = when (issue.kind) {
    PrestartIssueKind.NO_PRECISE_LOCATION -> requestPreciseLocation
    PrestartIssueKind.NO_CONSENT -> leaveSheetThen(actions, actions.onOpenDisclosure)
    PrestartIssueKind.STORAGE_FULL -> leaveSheetThen(actions, actions.onOpenSessions)
    PrestartIssueKind.AGGRESSIVE_OEM, PrestartIssueKind.CHECK_FAILED -> leaveSheetThen(actions, actions.onOpenReadiness)
    PrestartIssueKind.NO_SIM -> null
    PrestartIssueKind.LOCATION_OFF, PrestartIssueKind.NOTIFICATIONS_OFF, PrestartIssueKind.BATTERY_OPTIMISATION,
    PrestartIssueKind.BACKGROUND_RESTRICTED, PrestartIssueKind.STANDBY_BUCKET, PrestartIssueKind.WIFI_ON_BATTERY ->
        openSettings(context, PrestartPlanner.settingsTarget(issue))
}

/** Closes the sheet before leaving the screen, so it does not linger over the next one. */
private fun leaveSheetThen(actions: LiveActions, navigate: () -> Unit): () -> Unit = {
    actions.onDismissPrestart()
    actions.onDismissRefusal()
    navigate()
}

/** Opens a system settings screen; the sheet stays, and the checks run again when the screen resumes. */
private fun openSettings(context: Context, target: SettingsTarget): () -> Unit = {
    SystemSettings.open(context, target)
}

@StringRes
private fun rsrpLabelRes(rat: Rat?): Int = when (rat) {
    Rat.LTE -> R.string.live_rsrp_lte
    Rat.NR -> R.string.live_rsrp_nr
    else -> R.string.live_rsrp
}

@StringRes
private fun channelRes(rat: Rat): Int = when (rat) {
    Rat.LTE -> R.string.live_earfcn
    Rat.NR -> R.string.live_nrarfcn
    else -> R.string.live_arfcn
}

@StringRes
private fun networkRes(network: ServingNetwork): Int = when (network) {
    ServingNetwork.LTE -> R.string.live_network_lte
    ServingNetwork.LTE_WITH_NR_LEG -> R.string.live_network_lte_nsa
    ServingNetwork.NR_STANDALONE -> R.string.live_network_nr_sa
    ServingNetwork.OTHER -> R.string.live_network_other
}

@StringRes
private fun cadenceReasonRes(reason: CadenceReason?): Int = when (reason) {
    null -> R.string.live_cadence_waiting
    CadenceReason.SCREEN_ON_WIFI_OFF -> R.string.live_cadence_screen_on
    CadenceReason.CHARGING_WITH_WIFI -> R.string.live_cadence_charging
    CadenceReason.SCREEN_OFF -> R.string.live_cadence_screen_off
    CadenceReason.WIFI_ON_BATTERY -> R.string.live_cadence_wifi_on_battery
}

@StringRes
private fun serviceRes(chip: ServiceChip): Int = when (chip) {
    ServiceChip.WAITING -> R.string.live_service_waiting
    ServiceChip.IN_SERVICE -> R.string.live_service_in
    ServiceChip.ROAMING -> R.string.live_service_roaming
    ServiceChip.EMERGENCY_ONLY -> R.string.live_service_emergency
    ServiceChip.NO_SERVICE -> R.string.live_service_none
    ServiceChip.RADIO_OFF -> R.string.live_service_radio_off
    ServiceChip.UNKNOWN -> R.string.live_service_unknown
}

@StringRes
private fun dataRes(chip: DataChip): Int = when (chip) {
    DataChip.WAITING -> R.string.live_data_waiting
    DataChip.CONNECTED -> R.string.live_data_connected
    DataChip.CONNECTING -> R.string.live_data_connecting
    DataChip.DISCONNECTED -> R.string.live_data_off
    DataChip.SUSPENDED -> R.string.live_data_suspended
    DataChip.UNKNOWN -> R.string.live_data_unknown
}

@StringRes
private fun refusalMessageRes(refusal: StartRefusal?): Int? = when (refusal) {
    StartRefusal.BLANK_NAME -> R.string.live_refusal_blank_name
    StartRefusal.SESSION_RUNNING -> R.string.live_refusal_running
    else -> null
}

@StringRes
private fun listenerNameRes(listener: RadioListener): Int = when (listener) {
    RadioListener.CELL_INFO_REQUEST -> R.string.live_listener_request
    RadioListener.CELL_INFO_PUSH -> R.string.live_listener_push
    RadioListener.SIGNAL_STRENGTHS -> R.string.live_listener_signal
    RadioListener.SERVICE_STATE -> R.string.live_listener_service
    RadioListener.DISPLAY_INFO -> R.string.live_listener_display
    RadioListener.DATA_CONNECTION_STATE -> R.string.live_listener_data
    RadioListener.PHYSICAL_CHANNEL_CONFIG, RadioListener.BARRING_INFO, RadioListener.REGISTRATION_FAILED -> R.string.live_listener_other
}

@Composable
private fun listenerNoteText(note: ListenerNote): String = when {
    note.listener == RadioListener.CELL_INFO_PUSH && note.outcome == ListenerOutcome.MISSING_PERMISSION ->
        stringResource(R.string.live_note_push_missing)
    note.listener == RadioListener.CELL_INFO_REQUEST && note.outcome == ListenerOutcome.MISSING_PERMISSION ->
        stringResource(R.string.live_note_request_missing)
    note.outcome == ListenerOutcome.REFUSED_BY_PLATFORM ->
        stringResource(R.string.live_note_listener_refused, stringResource(listenerNameRes(note.listener)))
    else ->
        stringResource(R.string.live_note_listener_failed, stringResource(listenerNameRes(note.listener)))
}

@Composable
private fun liveMessageText(message: LiveMessage): String = stringResource(
    when (message) {
        LiveMessage.MARKED -> R.string.live_message_marked
        LiveMessage.NOT_RECORDING -> R.string.live_message_not_recording
        LiveMessage.PAUSED -> R.string.live_message_paused
        LiveMessage.START_FAILED -> R.string.live_message_start_failed
        LiveMessage.STOP_FAILED -> R.string.live_message_stop_failed
    },
)

@Composable
private fun gpsText(chip: GpsChip): String = when (chip) {
    GpsChip.Waiting -> stringResource(R.string.live_gps_waiting)
    is GpsChip.Fix -> {
        val accuracy = chip.accuracyM?.takeIf { it.isFinite() && it >= 0 }
        if (accuracy != null) stringResource(R.string.live_gps_fix_accuracy, accuracy.roundToInt()) else stringResource(R.string.live_gps_fix)
    }
    is GpsChip.Lost -> stringResource(R.string.live_gps_lost, Formats.ageSeconds(chip.ageMs))
}

/** The hero tile's badge when there is no serving cell. */
@Composable
private fun absenceBadge(absence: ServingAbsence?): String = stringResource(
    when (absence) {
        ServingAbsence.LocationOff -> R.string.live_absence_location_off
        ServingAbsence.RadioOff -> R.string.live_absence_radio_off
        ServingAbsence.NoService -> R.string.live_absence_no_service
        ServingAbsence.EmergencyOnly -> R.string.live_absence_emergency
        is ServingAbsence.NoLteOrNrServing -> R.string.live_no_lte_nr_badge
        ServingAbsence.WaitingForAnswer, null -> R.string.live_waiting_first_measurement
    },
)

/** The hero tile's line under the value when there is no serving cell: what stops it, or null while waiting. */
@Composable
private fun absenceDetail(absence: ServingAbsence?): String? = when (absence) {
    ServingAbsence.LocationOff -> stringResource(R.string.issue_location_off_detail)
    ServingAbsence.RadioOff -> stringResource(R.string.live_absence_radio_off_detail)
    ServingAbsence.NoService -> stringResource(R.string.live_absence_no_service_detail)
    ServingAbsence.EmergencyOnly -> stringResource(R.string.live_absence_emergency_detail)
    is ServingAbsence.NoLteOrNrServing ->
        absence.network?.let { stringResource(R.string.live_no_lte_nr_detail_on, it) } ?: stringResource(R.string.live_no_lte_nr_detail)
    ServingAbsence.WaitingForAnswer, null -> null
}

/** Why the serving cell on screen may be ageing, or that it is an emergency-only camp. */
@Composable
private fun servingProblemLine(problem: ServingAbsence): String? = when (problem) {
    ServingAbsence.LocationOff -> stringResource(R.string.live_problem_location_off)
    ServingAbsence.RadioOff -> stringResource(R.string.live_problem_radio_off)
    ServingAbsence.NoService -> stringResource(R.string.live_problem_no_service)
    ServingAbsence.EmergencyOnly -> stringResource(R.string.live_problem_emergency)
    ServingAbsence.WaitingForAnswer, is ServingAbsence.NoLteOrNrServing -> null
}

/** "LTE · PCI 212 · EARFCN 66786 · band 66 · Verizon · 311480": the cell, then who runs it. */
@Composable
private fun servingSummary(cell: LiveCell): String =
    (listOf(cellIdentity(cell)) + listOfNotNull(cell.operator, cell.plmn)).joinToString(stringResource(R.string.value_separator))

/** What the tests reach, as Settings has them, under the Start dialog's tests choice. */
@Composable
private fun testsTargetsText(tests: TestSettings): String {
    val ping = tests.pingTarget.trim().takeIf { it.isNotEmpty() }
    val host = TestSettingsRules.downloadHost(tests.downloadUrl)
    return when {
        ping != null && host != null -> stringResource(R.string.live_tests_targets_both, ping, host)
        ping != null -> stringResource(R.string.live_tests_targets_ping, ping)
        host != null -> stringResource(R.string.live_tests_targets_download, host)
        else -> stringResource(R.string.live_tests_targets_none)
    }
}

/** The running session's state, its fresh samples and GPS, in one line under the top bar. */
@Composable
private fun RecordingStatusStrip(strip: RecordingStrip, modifier: Modifier = Modifier) {
    val tone = when (strip.state) {
        RecordingState.RECORDING -> StatusTone.SUCCESS
        RecordingState.LOCATION_OFF -> StatusTone.ERROR
        RecordingState.PAUSED_IN_ZONE, RecordingState.WAITING_FOR_LOCATION, RecordingState.SAVING -> StatusTone.INFO
    }
    val family = FieldTapDesign.colors.status(tone)
    val stateText = stringResource(
        when (strip.state) {
            RecordingState.RECORDING -> R.string.live_recording
            RecordingState.PAUSED_IN_ZONE -> R.string.live_strip_paused
            RecordingState.WAITING_FOR_LOCATION -> R.string.live_strip_waiting
            RecordingState.LOCATION_OFF -> R.string.live_strip_location_off
            RecordingState.SAVING -> R.string.live_stopping
        },
    )
    val samples = pluralStringResource(
        R.plurals.live_strip_samples,
        strip.freshSamples.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        strip.freshSamples,
    )
    val gpsText = stringResource(
        when (strip.gps) {
            StripGps.FIX -> R.string.live_strip_gps_fix
            StripGps.LOST -> R.string.live_strip_gps_lost
            StripGps.WAITING -> R.string.live_strip_gps_waiting
        },
    )
    Surface(color = family.container, contentColor = family.onContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = screenGutter(), vertical = Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            Icon(imageVector = statusIcon(tone), contentDescription = null, modifier = Modifier.size(Sizes.IconSmall))
            Text(
                text = stateText + stringResource(R.string.value_separator) + samples,
                style = MaterialTheme.typography.labelLarge.tabular(),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (strip.gps == StripGps.FIX) FieldTapIcons.GpsFixed else FieldTapIcons.GpsOff,
                contentDescription = null,
                modifier = Modifier.size(Sizes.IconSmall),
            )
            Text(text = gpsText, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** "LTE · PCI 212 · EARFCN 66786 · band 66". */
@Composable
private fun cellIdentity(cell: LiveCell): String {
    val parts = mutableListOf(ratName(cell.rat))
    cell.pci?.let { parts += stringResource(R.string.live_pci, it) }
    cell.arfcn?.let { parts += stringResource(channelRes(cell.rat), it) }
    cell.band?.let { parts += stringResource(if (cell.rat == Rat.NR) R.string.live_band_nr else R.string.live_band_lte, it) }
    return parts.joinToString(stringResource(R.string.value_separator))
}

/** "PCI 212 · EARFCN 1300", or the RAT when neither is known. */
@Composable
private fun neighbourTitle(cell: LiveCell): String {
    val parts = mutableListOf<String>()
    cell.pci?.let { parts += stringResource(R.string.live_pci, it) }
    cell.arfcn?.let { parts += stringResource(channelRes(cell.rat), it) }
    return if (parts.isEmpty()) ratName(cell.rat) else parts.joinToString(stringResource(R.string.value_separator))
}

/** "LTE · band 3". */
@Composable
private fun neighbourSupporting(cell: LiveCell): String {
    val parts = mutableListOf(ratName(cell.rat))
    cell.band?.let { parts += stringResource(if (cell.rat == Rat.NR) R.string.live_band_nr else R.string.live_band_lte, it) }
    return parts.joinToString(stringResource(R.string.value_separator))
}

/** "Verizon · 311480", either part alone, or a dash. */
@Composable
private fun operatorText(cell: LiveCell): String {
    val parts = listOfNotNull(cell.operator, cell.plmn)
    return if (parts.isEmpty()) UNKNOWN_VALUE else parts.joinToString(stringResource(R.string.value_separator))
}

@FieldTapPreviews
@Composable
private fun LiveContentRecordingPreview() {
    val now = 1_000_000L
    val serving = LiveCell(Rat.LTE, 212, 66_786, 66, -92, -11, 14, "311480", "Verizon", 1, now - 1_200)
    val leg = LiveCell(Rat.NR, 393, 650_000, 77, -97, -12, 9, null, null, 2, now - 1_200)
    val rsrp = (0 until 120).map { ChartPoint(now - 240_000 + it * 2_000L, -95 + (it * 7) % 11) }
    FieldTapTheme {
        LiveContent(
            state = LiveUiState(
                live = LiveState(
                    serving = serving,
                    nsaLeg = leg,
                    servingAgeMs = 1_200,
                    badge = AgeBadge.FRESH,
                    neighbours = listOf(
                        LiveCell(Rat.LTE, 101, 66_786, 66, -101, -14, null, null, null, 0, now - 1_200),
                        LiveCell(Rat.GSM, null, 128, null, null, null, null, null, null, 0, now - 1_200),
                    ),
                    rsrpSeries = rsrp,
                    sinrSeries = rsrp.map { ChartPoint(it.elapsedMs, it.value + 108) },
                    shortInterval = true,
                    recentFreshIntervalMs = 2_000,
                    nowElapsedMs = now,
                ),
                status = SessionStatus.Recording(
                    RecorderSnapshot(
                        dirName = "20260910-143000_Walk-14-30",
                        startedUtcMs = 1_789_050_600_000L,
                        elapsedMs = 754_000,
                        servingRat = ServingRat.LTE,
                        servingRsrpDbm = -92,
                        newestSampleAgeMs = 1_200,
                        paused = false,
                        freshSamples = 377,
                        repeatsDropped = 377,
                        eventsWritten = 4,
                        trackRows = 754,
                        hasRecentFix = true,
                        stopping = false,
                    ),
                ),
                walkMode = false,
                testsDefaultOn = false,
                refusal = null,
                recovered = emptyList(),
            ),
            actions = PreviewActions,
        )
    }
}

@FieldTapPreviews
@Composable
private fun LiveContentWaitingPreview() {
    FieldTapTheme {
        LiveContent(
            state = LiveUiState(
                live = LiveState(),
                status = SessionStatus.Idle,
                walkMode = false,
                testsDefaultOn = false,
                refusal = null,
                recovered = listOf(SessionOutcome("20260909-180200_Car-park", 1_789_000_000_000L, 1_789_000_370_000L, "low_memory", true, 180)),
            ),
            actions = PreviewActions,
        )
    }
}

private val PreviewActions = LiveActions(
    onStart = {},
    onStartAnyway = {},
    onDismissPrestart = {},
    onRecheck = {},
    onMark = {},
    onStop = {},
    onWalkModeChange = {},
    onDismissRefusal = {},
    onAcknowledgeRecovered = {},
    onConsumeMessage = {},
    onOpenSessions = {},
    onOpenReadiness = {},
    onOpenProbe = {},
    onOpenSettings = {},
    onOpenAbout = {},
    onOpenDisclosure = {},
    onOpenSession = {},
    nowWallMs = { 1_789_050_600_000L },
)
