package com.fieldtap.ui.readiness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fieldtap.R
import com.fieldtap.app.AppGraph
import com.fieldtap.app.SessionStatus
import com.fieldtap.app.SoakState
import com.fieldtap.core.readiness.ReadinessCheck
import com.fieldtap.core.readiness.ReadinessFacts
import com.fieldtap.core.readiness.ReadinessLevel
import com.fieldtap.core.readiness.ReadinessPolicy
import com.fieldtap.core.readiness.ReadinessReport
import com.fieldtap.core.readiness.SettingsTarget
import com.fieldtap.core.soak.SoakResult
import com.fieldtap.ui.components.ChecklistRow
import com.fieldtap.ui.components.EmptyState
import com.fieldtap.ui.components.FieldTapPreviews
import com.fieldtap.ui.components.KeyValueRow
import com.fieldtap.ui.components.LoadingState
import com.fieldtap.ui.components.PreviewSurface
import com.fieldtap.ui.components.SectionCard
import com.fieldtap.ui.components.SectionDivider
import com.fieldtap.ui.components.StatusBanner
import com.fieldtap.ui.components.TopBarAction
import com.fieldtap.ui.setup.SettingsIntents
import com.fieldtap.ui.setup.SetupFormats
import com.fieldtap.ui.setup.SetupParagraph
import com.fieldtap.ui.setup.SetupScreenScaffold
import com.fieldtap.ui.setup.setupContentWidth
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.Formats
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the Readiness screen shows.
 *
 * @param report the newest readiness report, or null before the first check finished.
 * @param checking a check is running.
 * @param soak the soak test's state, from `AppGraph.soak`.
 */
data class ReadinessUiState(
    val report: ReadinessReport?,
    val checking: Boolean,
    val soak: SoakState,
)

/** Why the soak test is not running although the user asked for it. */
enum class SoakNotice {
    /** A session is recording; the soak test runs only without one. */
    SESSION_RUNNING,

    /** Android refused, or did not manage in time, to start the foreground service the test runs in. */
    START_REFUSED,
}

/**
 * The readiness checks and the soak test.
 *
 * - [check] runs `ReadinessChecker.check` (which also records `readinessLastRunUtcMs`). A call while a check runs
 *   queues one more run, so the answers after a return from a settings screen are never stale. A failed check keeps
 *   the previous report and sets [checkFailed].
 * - [startSoak] starts `SoakControl` unless a session records, and reports a refusal in [soakNotice]: Android
 *   refusing the service, or its start timing out (Running falls back to Idle without [cancelSoak]).
 * - [state] follows `SoakControl.state`; [sessionRunning] follows `SessionControl.status`.
 *
 * Owner: workstream `ui-setup`.
 */
class ReadinessViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutableState = MutableStateFlow(
        ReadinessUiState(report = null, checking = false, soak = graph.soak.state.value),
    )
    private val mutableCheckFailed = MutableStateFlow(false)
    private val mutableSoakNotice = MutableStateFlow<SoakNotice?>(null)
    private val mutableSessionRunning = MutableStateFlow(graph.sessionControl.status.value !is SessionStatus.Idle)
    private var checkJob: Job? = null
    private var recheckRequested = false
    private var cancelRequested = false

    val state: StateFlow<ReadinessUiState> = mutableState.asStateFlow()

    /** The newest check failed; [state] still holds the report before it, if any. */
    val checkFailed: StateFlow<Boolean> = mutableCheckFailed.asStateFlow()

    /** Why the requested soak test is not running, or null. */
    val soakNotice: StateFlow<SoakNotice?> = mutableSoakNotice.asStateFlow()

    /** A session is starting, recording or stopping, so the soak test cannot start. */
    val sessionRunning: StateFlow<Boolean> = mutableSessionRunning.asStateFlow()

    init {
        viewModelScope.launch {
            graph.soak.state.collect { soak -> onSoak(soak) }
        }
        viewModelScope.launch {
            graph.sessionControl.status.collect { status -> mutableSessionRunning.value = status !is SessionStatus.Idle }
        }
    }

    /** Runs the checks; call again on resume, since settings screens change the answers. */
    fun check() {
        if (checkJob?.isActive == true) {
            recheckRequested = true
            return
        }
        mutableState.update { it.copy(checking = true) }
        checkJob = viewModelScope.launch {
            try {
                do {
                    recheckRequested = false
                    runCheck()
                } while (recheckRequested)
            } finally {
                mutableState.update { it.copy(checking = false) }
            }
        }
    }

    /** Starts the 10-minute soak test, unless a session records or one already runs. */
    fun startSoak() {
        if (mutableState.value.soak is SoakState.Running) return
        if (graph.sessionControl.status.value !is SessionStatus.Idle) {
            mutableSoakNotice.value = SoakNotice.SESSION_RUNNING
            return
        }
        mutableSoakNotice.value = null
        cancelRequested = false
        try {
            graph.soak.start()
        } catch (e: RuntimeException) {
            mutableSoakNotice.value = SoakNotice.START_REFUSED
            return
        }
        // SoakControl falls back to Idle at once when Android refuses the service.
        if (graph.soak.state.value is SoakState.Idle) mutableSoakNotice.value = SoakNotice.START_REFUSED
    }

    /** Stops a running soak test. */
    fun cancelSoak() {
        cancelRequested = true
        mutableSoakNotice.value = null
        graph.soak.cancel()
    }

    private suspend fun runCheck() {
        try {
            val report = graph.readiness.check()
            mutableState.update { it.copy(report = report) }
            mutableCheckFailed.value = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            mutableCheckFailed.value = true
        } catch (e: RuntimeException) {
            mutableCheckFailed.value = true
        }
    }

    private fun onSoak(soak: SoakState) {
        val previous = mutableState.value.soak
        if (previous is SoakState.Running && soak is SoakState.Idle && !cancelRequested) {
            mutableSoakNotice.value = SoakNotice.START_REFUSED
        }
        mutableState.update { it.copy(soak = soak) }
    }
}

/**
 * Readiness: one row per check with its level and a button to its settings screen
 * (com.fieldtap.ui.setup.SettingsIntents), OEM guidance on OnePlus, OPPO and realme, and the optional
 * 10-minute screen-off soak test with "seconds logged / seconds elapsed".
 *
 * The checks run on every resume, so returning from a settings screen shows the new answer. Links open system
 * settings only; the app never requests a battery-optimisation exemption.
 *
 * Owner: workstream `ui-setup`.
 */
@Composable
fun ReadinessScreen(
    viewModel: ReadinessViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val checkFailed by viewModel.checkFailed.collectAsStateWithLifecycle()
    val soakNotice by viewModel.soakNotice.collectAsStateWithLifecycle()
    val sessionRunning by viewModel.sessionRunning.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val openFailedText = stringResource(R.string.setup_open_settings_failed)
    LifecycleResumeEffect(viewModel) {
        viewModel.check()
        onPauseOrDispose { }
    }
    ReadinessContent(
        state = state,
        checkFailed = checkFailed,
        soakNotice = soakNotice,
        sessionRunning = sessionRunning,
        onBack = onBack,
        onCheck = viewModel::check,
        onFix = { target ->
            if (!SettingsIntents.open(context, target)) {
                scope.launch { snackbarHostState.showSnackbar(openFailedText) }
            }
        },
        onStartSoak = viewModel::startSoak,
        onCancelSoak = viewModel::cancelSoak,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/** The Readiness screen without its view model, for previews. */
@Composable
internal fun ReadinessContent(
    state: ReadinessUiState,
    checkFailed: Boolean,
    soakNotice: SoakNotice?,
    sessionRunning: Boolean,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onFix: (SettingsTarget) -> Unit,
    onStartSoak: () -> Unit,
    onCancelSoak: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    val titleText = stringResource(R.string.readiness_title)
    val checkAgainText = stringResource(R.string.readiness_check_again)
    val checkingText = stringResource(R.string.readiness_checking)
    val failedTitle = stringResource(R.string.readiness_failed_title)
    val failedMessage = stringResource(R.string.readiness_failed_message)
    val staleText = stringResource(R.string.readiness_failed_stale)
    val tryAgainText = stringResource(R.string.setup_try_again)
    val report = state.report
    // A running or finished soak test is what the user came back for, so it goes first.
    val soakFirst = state.soak !is SoakState.Idle
    val requiredMissing = report?.canStart == false

    SetupScreenScaffold(
        title = titleText,
        modifier = modifier,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        actions = {
            TopBarAction(
                icon = FieldTapIcons.Refresh,
                contentDescription = checkAgainText,
                onClick = onCheck,
                enabled = !state.checking,
            )
        },
    ) {
        if (report == null) {
            item(key = "status") {
                if (checkFailed && !state.checking) {
                    EmptyState(
                        title = failedTitle,
                        message = failedMessage,
                        icon = FieldTapIcons.Error,
                        tone = StatusTone.ERROR,
                        actionLabel = tryAgainText,
                        onAction = onCheck,
                        modifier = Modifier.setupContentWidth(),
                    )
                } else {
                    LoadingState(message = checkingText, modifier = Modifier.setupContentWidth())
                }
            }
        } else {
            if (state.checking) {
                item(key = "progress") { LinearProgressIndicator(modifier = Modifier.setupContentWidth()) }
            }
            item(key = "summary") { SummaryBanner(report = report, modifier = Modifier.setupContentWidth()) }
            if (checkFailed) {
                item(key = "stale") {
                    StatusBanner(
                        message = staleText,
                        tone = StatusTone.WARNING,
                        actionLabel = tryAgainText,
                        onAction = onCheck,
                        modifier = Modifier.setupContentWidth(),
                    )
                }
            }
        }
        if (soakFirst) {
            item(key = "soak") {
                SoakCard(
                    soak = state.soak,
                    sessionRunning = sessionRunning,
                    requiredMissing = requiredMissing,
                    notice = soakNotice,
                    onStart = onStartSoak,
                    onCancel = onCancelSoak,
                    modifier = Modifier.setupContentWidth(),
                )
            }
        }
        if (report != null) {
            if (report.aggressiveOem) {
                item(key = "oem") { OemGuidanceCard(manufacturer = report.manufacturer.trim(), modifier = Modifier.setupContentWidth()) }
            }
            item(key = "checks") { ChecksCard(report = report, onFix = onFix, modifier = Modifier.setupContentWidth()) }
        }
        if (!soakFirst) {
            item(key = "soak") {
                SoakCard(
                    soak = state.soak,
                    sessionRunning = sessionRunning,
                    requiredMissing = requiredMissing,
                    notice = soakNotice,
                    onStart = onStartSoak,
                    onCancel = onCancelSoak,
                    modifier = Modifier.setupContentWidth(),
                )
            }
        }
    }
}

@Composable
private fun SummaryBanner(report: ReadinessReport, modifier: Modifier) {
    val summary = ReadinessPresentation.summary(report)
    val title = when (summary) {
        ReadinessSummary.Ready -> stringResource(R.string.readiness_summary_ready)
        is ReadinessSummary.Advice ->
            pluralStringResource(R.plurals.readiness_summary_advice, summary.count, summary.count)
        is ReadinessSummary.AggressiveOem -> stringResource(R.string.readiness_summary_oem, summary.manufacturer)
        is ReadinessSummary.Blocked ->
            pluralStringResource(R.plurals.readiness_summary_blocked, summary.count, summary.count)
    }
    StatusBanner(
        title = title,
        message = stringResource(R.string.readiness_checked_at, SetupFormats.time(report.checkedUtcMs)),
        tone = summary.tone,
        modifier = modifier,
    )
}

@Composable
private fun ChecksCard(report: ReadinessReport, onFix: (SettingsTarget) -> Unit, modifier: Modifier) {
    SectionCard(title = stringResource(R.string.readiness_checks_title), modifier = modifier) {
        ReadinessPresentation.ordered(report.items).forEachIndexed { index, item ->
            if (index > 0) SectionDivider()
            val fixText = if (ReadinessPresentation.showsFix(item)) fixLabel(item.target) else null
            val onAction: (() -> Unit)? = if (fixText != null) {
                { onFix(item.target) }
            } else {
                null
            }
            ChecklistRow(
                title = checkTitle(item.check),
                tone = ReadinessPresentation.tone(item.level),
                detail = item.detail,
                statusText = levelText(item.level),
                icon = checkIcon(item.check),
                actionLabel = fixText,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun OemGuidanceCard(manufacturer: String, modifier: Modifier) {
    SectionCard(
        title = stringResource(R.string.readiness_oem_title, manufacturer),
        icon = FieldTapIcons.Battery,
        modifier = modifier,
    ) {
        SetupParagraph(text = stringResource(R.string.readiness_oem_intro, manufacturer))
        OEM_STEPS.forEachIndexed { index, step ->
            GuidanceStep(number = index + 1, text = stringResource(step))
        }
        SetupParagraph(text = stringResource(R.string.readiness_oem_names))
    }
}

private val OEM_STEPS: List<Int> = listOf(
    R.string.readiness_oem_step_battery,
    R.string.readiness_oem_step_lock,
    R.string.readiness_oem_step_recheck,
)

@Composable
private fun GuidanceStep(number: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        Text(
            text = stringResource(R.string.readiness_oem_step_number, number),
            style = FieldTapDesign.numeric.body,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(min = Sizes.Icon),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SoakCard(
    soak: SoakState,
    sessionRunning: Boolean,
    requiredMissing: Boolean,
    notice: SoakNotice?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    val blockedReason = when {
        sessionRunning -> stringResource(R.string.soak_blocked_session)
        requiredMissing -> stringResource(R.string.soak_blocked_required)
        else -> null
    }
    val noticeText = when (notice) {
        SoakNotice.SESSION_RUNNING -> stringResource(R.string.soak_blocked_session)
        SoakNotice.START_REFUSED -> stringResource(R.string.soak_start_refused)
        null -> null
    }
    SectionCard(
        title = stringResource(R.string.soak_title),
        subtitle = stringResource(R.string.soak_subtitle),
        icon = FieldTapIcons.Timer,
        modifier = modifier,
    ) {
        when (soak) {
            SoakState.Idle -> {
                SetupParagraph(text = stringResource(R.string.soak_intro))
                if (noticeText != null) StatusBanner(message = noticeText, tone = StatusTone.ERROR)
                if (blockedReason != null && blockedReason != noticeText) SetupParagraph(text = blockedReason)
                Button(
                    onClick = onStart,
                    enabled = blockedReason == null,
                    modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(text = stringResource(R.string.soak_start))
                }
            }
            is SoakState.Running -> {
                val fraction = ReadinessPresentation.soakFraction(soak)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.soak_progress, Formats.elapsed(soak.elapsedMs), Formats.elapsed(soak.durationMs)),
                    style = FieldTapDesign.numeric.medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SetupParagraph(text = stringResource(R.string.soak_running_instruction))
                OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = Sizes.MinTouchTarget)) {
                    Text(text = stringResource(R.string.soak_stop))
                }
            }
            is SoakState.Done -> {
                SoakResultRows(result = soak.result)
                if (noticeText != null) StatusBanner(message = noticeText, tone = StatusTone.ERROR)
                if (blockedReason != null && blockedReason != noticeText) SetupParagraph(text = blockedReason)
                Button(
                    onClick = onStart,
                    enabled = blockedReason == null,
                    modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(text = stringResource(R.string.soak_run_again))
                }
            }
        }
    }
}

@Composable
private fun SoakResultRows(result: SoakResult) {
    val verdict = ReadinessPresentation.soakVerdict(result)
    val verdictText = when (verdict) {
        SoakVerdict.GOOD -> stringResource(R.string.soak_verdict_good)
        SoakVerdict.PARTIAL -> stringResource(R.string.soak_verdict_partial)
        SoakVerdict.POOR -> stringResource(R.string.soak_verdict_poor)
        SoakVerdict.TOO_SHORT -> stringResource(R.string.soak_verdict_too_short)
    }
    StatusBanner(message = verdictText, tone = verdict.tone)
    if (ReadinessPresentation.screenStayedOn(result)) {
        StatusBanner(message = stringResource(R.string.soak_screen_stayed_on), tone = StatusTone.INFO)
    }
    KeyValueRow(
        key = stringResource(R.string.soak_result_logged),
        value = stringResource(R.string.soak_result_logged_value, result.secondsLogged, result.secondsElapsed),
    )
    KeyValueRow(
        key = stringResource(R.string.soak_result_running_share),
        value = stringResource(R.string.soak_result_percent, Formats.oneDecimal(result.loggedPct)),
    )
    KeyValueRow(key = stringResource(R.string.soak_result_answers), value = result.answers.toString())
    KeyValueRow(key = stringResource(R.string.soak_result_fresh), value = result.freshAnswers.toString())
    KeyValueRow(key = stringResource(R.string.soak_result_screen_off), value = result.screenOffAnswers.toString())
}

@Composable
private fun checkTitle(check: ReadinessCheck): String = stringResource(
    when (check) {
        ReadinessCheck.PRECISE_LOCATION -> R.string.readiness_check_precise_location
        ReadinessCheck.LOCATION_ENABLED -> R.string.readiness_check_location_enabled
        ReadinessCheck.NOTIFICATIONS -> R.string.readiness_check_notifications
        ReadinessCheck.PHONE_PERMISSION -> R.string.readiness_check_phone_permission
        ReadinessCheck.BATTERY_OPTIMISATION -> R.string.readiness_check_battery_optimisation
        ReadinessCheck.BACKGROUND_RESTRICTION -> R.string.readiness_check_background_restriction
        ReadinessCheck.STANDBY_BUCKET -> R.string.readiness_check_standby_bucket
        ReadinessCheck.SIM_PRESENT -> R.string.readiness_check_sim_present
        ReadinessCheck.WIFI_OFF -> R.string.readiness_check_wifi_off
    },
)

private fun checkIcon(check: ReadinessCheck): ImageVector = when (check) {
    ReadinessCheck.PRECISE_LOCATION -> FieldTapIcons.Location
    ReadinessCheck.LOCATION_ENABLED -> FieldTapIcons.GpsFixed
    ReadinessCheck.NOTIFICATIONS -> FieldTapIcons.Notifications
    ReadinessCheck.PHONE_PERMISSION -> FieldTapIcons.Phone
    ReadinessCheck.BATTERY_OPTIMISATION -> FieldTapIcons.Battery
    ReadinessCheck.BACKGROUND_RESTRICTION -> FieldTapIcons.Pause
    ReadinessCheck.STANDBY_BUCKET -> FieldTapIcons.Timer
    ReadinessCheck.SIM_PRESENT -> FieldTapIcons.Sim
    ReadinessCheck.WIFI_OFF -> FieldTapIcons.Wifi
}

@Composable
private fun levelText(level: ReadinessLevel): String = stringResource(
    when (level) {
        ReadinessLevel.OK -> R.string.readiness_level_ok
        ReadinessLevel.ADVICE -> R.string.readiness_level_advice
        ReadinessLevel.BLOCKER -> R.string.readiness_level_blocker
    },
)

@Composable
private fun fixLabel(target: SettingsTarget): String? = when (target) {
    SettingsTarget.APP_DETAILS -> stringResource(R.string.readiness_fix_app_settings)
    SettingsTarget.LOCATION_SOURCE -> stringResource(R.string.readiness_fix_location)
    SettingsTarget.APP_NOTIFICATIONS -> stringResource(R.string.readiness_fix_notifications)
    SettingsTarget.BATTERY_OPTIMISATION -> stringResource(R.string.readiness_fix_battery)
    SettingsTarget.WIFI -> stringResource(R.string.readiness_fix_wifi)
    SettingsTarget.NONE -> null
}

private fun previewReport(manufacturer: String, precise: Boolean): ReadinessReport = ReadinessPolicy.evaluate(
    ReadinessFacts(
        manufacturer = manufacturer,
        preciseLocationGranted = precise,
        locationEnabled = true,
        notificationsGranted = true,
        phonePermissionGranted = false,
        ignoringBatteryOptimisations = false,
        backgroundRestricted = false,
        standbyBucket = 10,
        simReady = true,
        wifiConnected = true,
        charging = false,
    ),
    nowUtcMs = PREVIEW_NOW_UTC_MS,
)

/** 2026-09-10T14:30:00Z. */
private const val PREVIEW_NOW_UTC_MS: Long = 1_789_050_600_000L

@FieldTapPreviews
@Composable
private fun ReadinessAdvicePreview() {
    PreviewSurface {
        ReadinessContent(
            state = ReadinessUiState(
                report = previewReport(manufacturer = "OnePlus", precise = true),
                checking = false,
                soak = SoakState.Running(elapsedMs = 252_000, durationMs = 600_000),
            ),
            checkFailed = false,
            soakNotice = null,
            sessionRunning = false,
            onBack = {},
            onCheck = {},
            onFix = {},
            onStartSoak = {},
            onCancelSoak = {},
        )
    }
}

@FieldTapPreviews
@Composable
private fun ReadinessBlockedPreview() {
    PreviewSurface {
        ReadinessContent(
            state = ReadinessUiState(
                report = previewReport(manufacturer = "Google", precise = false),
                checking = false,
                soak = SoakState.Done(
                    SoakResult(
                        durationMs = 600_000,
                        secondsElapsed = 600,
                        secondsLogged = 512,
                        answers = 64,
                        freshAnswers = 58,
                        screenOffAnswers = 60,
                    ),
                ),
            ),
            checkFailed = false,
            soakNotice = null,
            sessionRunning = false,
            onBack = {},
            onCheck = {},
            onFix = {},
            onStartSoak = {},
            onCancelSoak = {},
        )
    }
}
