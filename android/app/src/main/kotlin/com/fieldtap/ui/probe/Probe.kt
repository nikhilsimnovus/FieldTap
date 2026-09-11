package com.fieldtap.ui.probe

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fieldtap.R
import com.fieldtap.app.AppGraph
import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.RadioListener
import com.fieldtap.core.probe.CellInfoProbe
import com.fieldtap.core.probe.ProbeNotes
import com.fieldtap.core.probe.ProbeRecorder
import com.fieldtap.core.probe.ProbeReport
import com.fieldtap.core.probe.ServiceStateProbe
import com.fieldtap.format.HandsetMeta
import com.fieldtap.platform.Permissions
import com.fieldtap.ui.common.FileSharer
import com.fieldtap.ui.components.ChecklistRow
import com.fieldtap.ui.components.FieldTapPreviews
import com.fieldtap.ui.components.KeyValueRow
import com.fieldtap.ui.components.PreviewSurface
import com.fieldtap.ui.components.SectionCard
import com.fieldtap.ui.components.SectionDivider
import com.fieldtap.ui.components.StatusBanner
import com.fieldtap.ui.components.statusIcon
import com.fieldtap.ui.setup.ButtonProgress
import com.fieldtap.ui.setup.SetupFormats
import com.fieldtap.ui.setup.SetupParagraph
import com.fieldtap.ui.setup.SetupScreenScaffold
import com.fieldtap.ui.setup.setupContentWidth
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone
import com.fieldtap.ui.theme.tabular
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the Probe screen shows.
 *
 * @param running a probe run is listening.
 * @param progress the runner's progress line, for example "Listening: 12 s of 30 s, 12 answers".
 * @param report the finished report, or null before the first run finished (or while a new run listens).
 * @param exported the JSON file of [report] once exported.
 */
data class ProbeUiState(
    val running: Boolean,
    val progress: String?,
    val report: ProbeReport?,
    val exported: File?,
)

/** Why the Probe screen shows a banner. */
enum class ProbeProblem {
    /** The run ended with an error; no report. */
    RUN_FAILED,

    /** The run was cancelled because the screen left the foreground; no report. */
    INTERRUPTED,

    /** The report could not be written for sharing. */
    EXPORT_FAILED,
}

/**
 * The capability probe run and its export.
 *
 * - [run] listens for [DURATION_MS] through `CapabilityProbe.run`; progress lines go to [state]. A new run replaces
 *   the old report. [stop] (the user) and [interrupt] (the screen left) cancel it, which unregisters every listener;
 *   updates from a cancelled run are ignored, even when a new run has started.
 * - [export] writes the report through `CapabilityProbe.export`, sets [ProbeUiState.exported] and emits the file on
 *   [shareRequests]; a failure sets [problem] to [ProbeProblem.EXPORT_FAILED].
 *
 * Owner: workstream `ui-setup`.
 */
class ProbeViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutableState = MutableStateFlow(ProbeUiState(running = false, progress = null, report = null, exported = null))
    private val mutableProblem = MutableStateFlow<ProbeProblem?>(null)
    private val mutableExporting = MutableStateFlow(false)
    private val shares = Channel<File>(Channel.BUFFERED)
    private var runJob: Job? = null
    private var exportJob: Job? = null
    private var runId = 0L

    val state: StateFlow<ProbeUiState> = mutableState.asStateFlow()

    /** Why a banner shows, or null. */
    val problem: StateFlow<ProbeProblem?> = mutableProblem.asStateFlow()

    /** An export is being written. */
    val exporting: StateFlow<Boolean> = mutableExporting.asStateFlow()

    /** Each exported file, once, for the screen to share. */
    val shareRequests: Flow<File> = shares.receiveAsFlow()

    /** Starts a 30 s run unless one is listening. The screen must stay on and visible while it runs. */
    fun run() {
        if (runJob?.isActive == true) return
        exportJob?.cancel()
        val id = ++runId
        mutableProblem.value = null
        mutableExporting.value = false
        mutableState.value = ProbeUiState(running = true, progress = null, report = null, exported = null)
        runJob = viewModelScope.launch {
            try {
                val report = graph.probe.run(DURATION_MS) { text ->
                    if (id == runId) mutableState.update { it.copy(progress = text) }
                }
                if (id == runId) mutableState.update { it.copy(running = false, progress = null, report = report) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (id == runId) failRun()
            } catch (e: RuntimeException) {
                if (id == runId) failRun()
            }
        }
    }

    /** Cancels a run at the user's request. */
    fun stop() {
        cancelRun(problem = null)
    }

    /** Cancels a run because the screen left the foreground, and says so. Does nothing when no run listens. */
    fun interrupt() {
        cancelRun(problem = ProbeProblem.INTERRUPTED)
    }

    /** Writes the report as JSON and asks the screen to share it. */
    fun export() {
        val current = mutableState.value
        val report = current.report ?: return
        if (current.running || exportJob?.isActive == true) return
        if (mutableProblem.value == ProbeProblem.EXPORT_FAILED) mutableProblem.value = null
        mutableExporting.value = true
        exportJob = viewModelScope.launch {
            try {
                val file = graph.probe.export(report)
                mutableState.update { state -> if (state.report === report) state.copy(exported = file) else state }
                shares.send(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                mutableProblem.value = ProbeProblem.EXPORT_FAILED
            } catch (e: RuntimeException) {
                mutableProblem.value = ProbeProblem.EXPORT_FAILED
            } finally {
                mutableExporting.value = false
            }
        }
    }

    private fun cancelRun(problem: ProbeProblem?) {
        val job = runJob ?: return
        if (!job.isActive) return
        runId++
        job.cancel()
        mutableProblem.value = problem
        mutableState.update { it.copy(running = false, progress = null) }
    }

    private fun failRun() {
        mutableProblem.value = ProbeProblem.RUN_FAILED
        mutableState.update { it.copy(running = false, progress = null) }
    }

    companion object {
        /** How long a run listens. */
        const val DURATION_MS: Long = 30_000
    }
}

/**
 * Capability probe: Run (30 s, screen must stay on), the report as readable rows (neighbours, band lists,
 * timestamps advance, SINR range, refused listeners and permissions), and Export JSON shared through
 * com.fieldtap.ui.common.FileSharer.
 *
 * While a run listens the screen is kept on (`View.keepScreenOn`, no wake lock). Leaving the screen cancels the
 * run, except for a configuration change such as a rotation.
 *
 * Owner: workstream `ui-setup`.
 */
@Composable
fun ProbeScreen(
    viewModel: ProbeViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val view = LocalView.current
    val snackbarHostState = remember { SnackbarHostState() }
    val shareSubject = stringResource(R.string.probe_share_subject)
    val shareFailedText = stringResource(R.string.setup_share_failed)
    val currentContext by rememberUpdatedState(context)

    if (state.running) {
        DisposableEffect(view) {
            val previous = view.keepScreenOn
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = previous }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) viewModel.interrupt()
    }
    LaunchedEffect(viewModel) {
        viewModel.shareRequests.collect { file ->
            val shared = try {
                FileSharer.share(currentContext, file, JSON_MIME_TYPE, shareSubject, null)
                true
            } catch (e: RuntimeException) {
                // No share target, or a file the FileProvider does not serve: say so rather than crash.
                false
            }
            if (!shared) snackbarHostState.showSnackbar(shareFailedText)
        }
    }
    ProbeContent(
        state = state,
        problem = problem,
        exporting = exporting,
        onBack = onBack,
        onRun = viewModel::run,
        onStop = viewModel::stop,
        onExport = viewModel::export,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

private const val JSON_MIME_TYPE = "application/json"

/** The Probe screen without its view model, for previews. */
@Composable
internal fun ProbeContent(
    state: ProbeUiState,
    problem: ProbeProblem?,
    exporting: Boolean,
    onBack: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    val titleText = stringResource(R.string.probe_title)
    val report = state.report
    SetupScreenScaffold(title = titleText, modifier = modifier, onBack = onBack, snackbarHostState = snackbarHostState) {
        if (problem != null) {
            item(key = "problem") {
                ProblemBanner(problem = problem, onRun = onRun, onExport = onExport, modifier = Modifier.setupContentWidth())
            }
        }
        when {
            state.running -> item(key = "running") {
                RunningCard(progress = state.progress, onStop = onStop, modifier = Modifier.setupContentWidth())
            }
            report == null -> item(key = "intro") { IntroCard(onRun = onRun, modifier = Modifier.setupContentWidth()) }
            else -> {
                item(key = "summary") {
                    SummaryCard(
                        report = report,
                        exported = state.exported,
                        exporting = exporting,
                        onExport = onExport,
                        onRun = onRun,
                        modifier = Modifier.setupContentWidth(),
                    )
                }
                item(key = "findings") { FindingsCard(notes = report.notes, modifier = Modifier.setupContentWidth()) }
                item(key = "cell-info") { CellInfoCard(probe = report.cellInfo, modifier = Modifier.setupContentWidth()) }
                item(key = "service") {
                    ServiceCard(
                        probe = report.serviceState,
                        overrides = report.displayOverridesSeen,
                        modifier = Modifier.setupContentWidth(),
                    )
                }
                item(key = "listeners") { ListenersCard(listeners = report.listeners, modifier = Modifier.setupContentWidth()) }
                item(key = "permissions") { PermissionsCard(permissions = report.permissions, modifier = Modifier.setupContentWidth()) }
            }
        }
    }
}

@Composable
private fun ProblemBanner(problem: ProbeProblem, onRun: () -> Unit, onExport: () -> Unit, modifier: Modifier) {
    when (problem) {
        ProbeProblem.RUN_FAILED -> StatusBanner(
            message = stringResource(R.string.probe_failed),
            tone = StatusTone.ERROR,
            actionLabel = stringResource(R.string.setup_try_again),
            onAction = onRun,
            modifier = modifier,
        )
        ProbeProblem.INTERRUPTED -> StatusBanner(
            message = stringResource(R.string.probe_interrupted),
            tone = StatusTone.INFO,
            actionLabel = stringResource(R.string.probe_run_again),
            onAction = onRun,
            modifier = modifier,
        )
        ProbeProblem.EXPORT_FAILED -> StatusBanner(
            message = stringResource(R.string.probe_export_failed),
            tone = StatusTone.ERROR,
            actionLabel = stringResource(R.string.setup_try_again),
            onAction = onExport,
            modifier = modifier,
        )
    }
}

@Composable
private fun IntroCard(onRun: () -> Unit, modifier: Modifier) {
    SectionCard(title = stringResource(R.string.probe_intro_title), icon = FieldTapIcons.Search, modifier = modifier) {
        SetupParagraph(text = stringResource(R.string.probe_intro))
        SetupParagraph(text = stringResource(R.string.probe_keep_open))
        Button(onClick = onRun, modifier = Modifier.heightIn(min = Sizes.MinTouchTarget)) {
            Text(text = stringResource(R.string.probe_run))
        }
    }
}

@Composable
private fun RunningCard(progress: String?, onStop: () -> Unit, modifier: Modifier) {
    SectionCard(title = stringResource(R.string.probe_running_title), icon = FieldTapIcons.Search, modifier = modifier) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = progress ?: stringResource(R.string.probe_starting),
            style = MaterialTheme.typography.bodyLarge.tabular(),
            color = MaterialTheme.colorScheme.onSurface,
        )
        SetupParagraph(text = stringResource(R.string.probe_keep_open))
        OutlinedButton(onClick = onStop, modifier = Modifier.heightIn(min = Sizes.MinTouchTarget)) {
            Text(text = stringResource(R.string.probe_stop))
        }
    }
}

@Composable
private fun SummaryCard(
    report: ProbeReport,
    exported: File?,
    exporting: Boolean,
    onExport: () -> Unit,
    onRun: () -> Unit,
    modifier: Modifier,
) {
    val noValue = stringResource(R.string.setup_no_value)
    val phoneTitle = stringResource(R.string.probe_section_phone)
    val handset = report.handset
    // Titled by the model the report is about; "Phone" when Android named neither maker nor model.
    val model = listOfNotNull(handset.manufacturer, handset.model).joinToString(" ").ifBlank { phoneTitle }
    SectionCard(
        title = model,
        subtitle = SetupFormats.dateTime(report.createdUtcMs),
        icon = FieldTapIcons.Phone,
        modifier = modifier,
    ) {
        KeyValueRow(
            key = stringResource(R.string.probe_key_android),
            value = stringResource(R.string.probe_value_android, handset.androidVersion ?: noValue, report.sdkInt),
        )
        KeyValueRow(key = stringResource(R.string.probe_key_network_type), value = handset.networkType ?: noValue)
        KeyValueRow(key = stringResource(R.string.probe_key_operator), value = operatorText(handset) ?: noValue)
        KeyValueRow(
            key = stringResource(R.string.probe_key_app),
            value = stringResource(R.string.probe_value_app, report.appVersion, report.versionCode),
        )
        KeyValueRow(
            key = stringResource(R.string.probe_key_listened),
            value = stringResource(R.string.probe_value_seconds, ProbePresentation.seconds(report.durationMs)),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            Button(
                onClick = onExport,
                enabled = !exporting,
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                if (exporting) {
                    ButtonProgress()
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                }
                Text(text = stringResource(R.string.probe_export))
            }
            OutlinedButton(onClick = onRun, modifier = Modifier.heightIn(min = Sizes.MinTouchTarget)) {
                Text(text = stringResource(R.string.probe_run_again))
            }
        }
        if (exported != null) {
            SetupParagraph(text = stringResource(R.string.probe_exported, exported.name))
        }
    }
}

@Composable
private fun operatorText(handset: HandsetMeta): String? {
    val name = handset.operatorName?.takeIf { it.isNotBlank() }
    val code = handset.operatorMccmnc?.takeIf { it.isNotBlank() }
    return when {
        name != null && code != null -> stringResource(R.string.probe_value_operator, name, code)
        else -> name ?: code
    }
}

@Composable
private fun FindingsCard(notes: List<String>, modifier: Modifier) {
    SectionCard(title = stringResource(R.string.probe_section_findings), icon = FieldTapIcons.Info, modifier = modifier) {
        if (notes.isEmpty()) {
            ChecklistRow(title = stringResource(R.string.probe_no_findings), tone = StatusTone.SUCCESS)
        } else {
            notes.forEachIndexed { index, note ->
                if (index > 0) SectionDivider()
                FindingRow(text = note)
            }
        }
    }
}

@Composable
private fun FindingRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = statusIcon(StatusTone.INFO),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Sizes.IconSmall),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CellInfoCard(probe: CellInfoProbe, modifier: Modifier) {
    val noValue = stringResource(R.string.setup_no_value)
    val colors = FieldTapDesign.colors
    SectionCard(title = stringResource(R.string.probe_section_cell_info), icon = FieldTapIcons.SignalBars, modifier = modifier) {
        KeyValueRow(key = stringResource(R.string.probe_key_requests), value = probe.requests.toString())
        KeyValueRow(key = stringResource(R.string.probe_key_answers), value = probe.answers.toString())
        KeyValueRow(
            key = stringResource(R.string.probe_key_errors),
            value = probe.errors.toString(),
            valueColor = if (probe.errors > 0) colors.warning.color else Color.Unspecified,
        )
        KeyValueRow(key = stringResource(R.string.probe_key_max_cells), value = probe.maxCellsPerAnswer.toString())
        KeyValueRow(key = stringResource(R.string.probe_key_neighbours), value = seenText(probe.neighboursSeen), tabular = false)
        KeyValueRow(
            key = stringResource(R.string.probe_key_serving_rats),
            value = ProbePresentation.joined(probe.servingRats) ?: stringResource(R.string.probe_value_none),
            tabular = false,
        )
        KeyValueRow(key = stringResource(R.string.probe_key_band_lists), value = reportedText(probe.bandListsPresent), tabular = false)
        KeyValueRow(
            key = stringResource(R.string.probe_key_connection_status),
            value = reportedText(probe.connectionStatusReported),
            tabular = false,
        )
        KeyValueRow(key = stringResource(R.string.probe_key_nsa), value = seenText(probe.nsaSecondarySeen), tabular = false)
        val timestamps = ProbePresentation.timestamps(probe.timestampsAdvance)
        KeyValueRow(
            key = stringResource(R.string.probe_key_timestamps),
            value = when (timestamps) {
                ProbeAnswer.YES -> stringResource(R.string.setup_yes)
                ProbeAnswer.NO -> stringResource(R.string.setup_no)
                ProbeAnswer.NOT_ENOUGH -> stringResource(R.string.probe_value_not_enough)
            },
            valueColor = if (timestamps == ProbeAnswer.NO) colors.error.color else Color.Unspecified,
            tabular = false,
        )
        KeyValueRow(
            key = stringResource(R.string.probe_key_min_interval),
            value = probe.minFreshIntervalMs?.let { stringResource(R.string.probe_value_seconds, ProbePresentation.seconds(it)) } ?: noValue,
        )
        val rsrp = ProbePresentation.range(probe.rsrpMin, probe.rsrpMax)
        KeyValueRow(
            key = stringResource(R.string.probe_key_rsrp_range),
            value = if (rsrp != null) stringResource(R.string.probe_value_range_dbm, rsrp.first, rsrp.last) else noValue,
        )
        val sinr = ProbePresentation.range(probe.sinrMin, probe.sinrMax)
        KeyValueRow(
            key = stringResource(R.string.probe_key_sinr_range),
            value = if (sinr != null) stringResource(R.string.probe_value_range_db, sinr.first, sinr.last) else noValue,
        )
    }
}

@Composable
private fun ServiceCard(probe: ServiceStateProbe, overrides: List<String>, modifier: Modifier) {
    val none = stringResource(R.string.probe_value_none)
    SectionCard(title = stringResource(R.string.probe_section_service), icon = FieldTapIcons.Sim, modifier = modifier) {
        KeyValueRow(key = stringResource(R.string.probe_key_updates), value = probe.snapshots.toString())
        KeyValueRow(
            key = stringResource(R.string.probe_key_operator_code),
            value = reportedText(probe.operatorNumericPresent),
            tabular = false,
        )
        KeyValueRow(key = stringResource(R.string.probe_key_emergency_only), value = seenText(probe.emergencyOnlySeen), tabular = false)
        KeyValueRow(key = stringResource(R.string.probe_key_states), value = ProbePresentation.joined(probe.states) ?: none, tabular = false)
        KeyValueRow(
            key = stringResource(R.string.probe_key_display_overrides),
            value = ProbePresentation.joined(overrides) ?: none,
            tabular = false,
        )
    }
}

@Composable
private fun ListenersCard(listeners: Map<RadioListener, ListenerOutcome>, modifier: Modifier) {
    val colors = FieldTapDesign.colors
    SectionCard(title = stringResource(R.string.probe_section_listeners), icon = FieldTapIcons.Transfer, modifier = modifier) {
        ProbePresentation.listenerRows(listeners).forEach { (listener, outcome) ->
            val word = ProbePresentation.listenerWord(listener, outcome)
            KeyValueRow(
                key = ProbeNotes.apiName(listener),
                value = listenerText(word),
                valueColor = colors.status(word.tone).color,
                tabular = false,
            )
        }
    }
}

@Composable
private fun PermissionsCard(permissions: Map<String, Boolean>, modifier: Modifier) {
    val colors = FieldTapDesign.colors
    SectionCard(title = stringResource(R.string.probe_section_permissions), icon = FieldTapIcons.Shield, modifier = modifier) {
        permissions.forEach { (name, granted) ->
            KeyValueRow(
                key = permissionText(name),
                value = stringResource(if (granted) R.string.probe_permission_allowed else R.string.probe_permission_not_allowed),
                valueColor = if (granted) colors.success.color else colors.warning.color,
                tabular = false,
            )
        }
    }
}

@Composable
private fun seenText(seen: Boolean): String =
    stringResource(if (seen) R.string.probe_value_seen else R.string.probe_value_not_seen)

@Composable
private fun reportedText(reported: Boolean): String =
    stringResource(if (reported) R.string.probe_value_reported else R.string.probe_value_not_reported)

@Composable
private fun listenerText(word: ListenerWord): String = stringResource(
    when (word) {
        ListenerWord.REGISTERED -> R.string.probe_listener_registered
        ListenerWord.MISSING_PERMISSION -> R.string.probe_listener_missing_permission
        ListenerWord.REFUSED -> R.string.probe_listener_refused
        ListenerWord.REFUSED_EXPECTED -> R.string.probe_listener_refused_expected
        ListenerWord.NOT_REGISTERED -> R.string.probe_listener_not_registered
        ListenerWord.FAILED -> R.string.probe_listener_failed
    },
)

@Composable
private fun permissionText(name: String): String = when (ProbePresentation.permissionLabel(name)) {
    ProbePermissionLabel.PRECISE_LOCATION -> stringResource(R.string.probe_permission_precise_location)
    ProbePermissionLabel.APPROXIMATE_LOCATION -> stringResource(R.string.probe_permission_approximate_location)
    ProbePermissionLabel.NOTIFICATIONS -> stringResource(R.string.probe_permission_notifications)
    ProbePermissionLabel.PHONE -> stringResource(R.string.probe_permission_phone)
    ProbePermissionLabel.OTHER -> name
}

private fun previewReport(): ProbeReport = ProbeRecorder().report(
    createdUtcMs = 1_789_050_600_000L,
    durationMs = 30_000,
    appVersion = "0.1.0",
    versionCode = 1,
    sdkInt = 36,
    handset = HandsetMeta(
        manufacturer = "Google",
        model = "Pixel 9",
        androidVersion = "16",
        operatorName = "T-Mobile",
        operatorMccmnc = "310260",
        networkType = "NR",
    ),
    permissions = linkedMapOf(
        Permissions.FINE_LOCATION to true,
        Permissions.COARSE_LOCATION to true,
        Permissions.POST_NOTIFICATIONS to true,
        Permissions.READ_PHONE_STATE to false,
    ),
)

@FieldTapPreviews
@Composable
private fun ProbeReportPreview() {
    PreviewSurface {
        ProbeContent(
            state = ProbeUiState(running = false, progress = null, report = previewReport(), exported = null),
            problem = null,
            exporting = false,
            onBack = {},
            onRun = {},
            onStop = {},
            onExport = {},
        )
    }
}

@FieldTapPreviews
@Composable
private fun ProbeRunningPreview() {
    PreviewSurface {
        ProbeContent(
            state = ProbeUiState(running = true, progress = "Listening: 12 s of 30 s, 12 answers", report = null, exported = null),
            problem = ProbeProblem.INTERRUPTED,
            exporting = false,
            onBack = {},
            onRun = {},
            onStop = {},
            onExport = {},
        )
    }
}
