package com.fieldtap.ui.sessions

import android.content.ActivityNotFoundException
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fieldtap.R
import com.fieldtap.app.AppGraph
import com.fieldtap.app.SessionDetail
import com.fieldtap.app.SessionSummary
import com.fieldtap.core.export.ExportResult
import com.fieldtap.core.session.StoragePolicy
import com.fieldtap.core.session.StorageStatus
import com.fieldtap.format.GapMeta
import com.fieldtap.format.LocationPrecision
import com.fieldtap.format.SessionFile
import com.fieldtap.format.SessionMeta
import com.fieldtap.ui.FieldTapTheme
import com.fieldtap.ui.common.DisplayTime
import com.fieldtap.ui.common.FileSharer
import com.fieldtap.ui.common.StopKind
import com.fieldtap.ui.common.StopReasons
import com.fieldtap.ui.common.contentWidth
import com.fieldtap.ui.common.rememberDelayedVisibility
import com.fieldtap.ui.common.screenGutter
import com.fieldtap.ui.common.stopReasonText
import com.fieldtap.ui.components.EmptyState
import com.fieldtap.ui.components.FieldTapPreviews
import com.fieldtap.ui.components.FieldTapTopBar
import com.fieldtap.ui.components.rememberTopBarScroll
import com.fieldtap.ui.components.KeyValueRow
import com.fieldtap.ui.components.LoadingState
import com.fieldtap.ui.components.MetricEmphasis
import com.fieldtap.ui.components.MetricGrid
import com.fieldtap.ui.components.MetricTile
import com.fieldtap.ui.components.RadioRow
import com.fieldtap.ui.components.SectionCard
import com.fieldtap.ui.components.SessionListRow
import com.fieldtap.ui.components.SessionRowStatus
import com.fieldtap.ui.components.StatusBanner
import com.fieldtap.ui.components.TopBarAction
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.Formats
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionsUiState(
    val loading: Boolean,
    val sessions: List<SessionSummary>,
    val storage: StorageStatus?,
    /** The last refresh failed; [sessions] and [storage] are what was read before it. */
    val loadFailed: Boolean = false,
)

/**
 * The Sessions list. [refresh] reads the list and storage off the main thread (inside the repository); the
 * screen calls it whenever it resumes, and the view model calls it when a session starts or ends.
 *
 * Owner: workstream `ui-session`.
 */
class SessionsViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutableState = MutableStateFlow(SessionsUiState(loading = true, sessions = emptyList(), storage = null))
    private var refreshJob: Job? = null

    val state: StateFlow<SessionsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            graph.sessionControl.status
                .map { SessionsPresentation.phaseKey(it) }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh() }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        mutableState.update { it.copy(loading = true) }
        refreshJob = viewModelScope.launch {
            try {
                val sessions = graph.sessions.list()
                val storage = try {
                    graph.sessions.storage()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                mutableState.update { previous ->
                    SessionsUiState(loading = false, sessions = sessions, storage = storage ?: previous.storage, loadFailed = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }
}

/**
 * Sessions list, newest first: name, start time (local display), duration, stopped by (with
 * "interrupted by Android: low memory" wording for exit reasons), handset, PLMNs, size. Unreadable
 * sessions are listed and can be deleted. Storage used against the cap.
 *
 * Owner: workstream `ui-session`.
 */
@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    onOpenSession: (dirName: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    SessionsContent(
        state = state,
        onOpenSession = onOpenSession,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        modifier = modifier,
    )
}

sealed interface ExportState {
    data object Idle : ExportState

    data object Building : ExportState

    data class Ready(val result: ExportResult) : ExportState

    /** [reason] is one of the [ExportFailure] tokens. */
    data class Failed(val reason: String) : ExportState
}

data class SessionDetailUiState(
    val detail: SessionDetail?,
    val export: ExportState,
    val deleted: Boolean,
    /** The detail is being read; [detail] is null until the first read ends. */
    val loading: Boolean = false,
    /** The last read failed with an error (not "no such session", which is [detail] null). */
    val loadFailed: Boolean = false,
    val deleting: Boolean = false,
    /** Why the last delete did not happen, until dismissed. */
    val deleteError: DeleteError? = null,
)

/**
 * One session: its detail, the export zip and delete.
 *
 * - [export] builds `<cacheDir>/exports/<dirName>.zip` at a precision; one build at a time, and never for
 *   the running session.
 * - [delete] never deletes the running session: it is refused here before the repository (which refuses it
 *   too) is asked.
 * - The detail reloads when a session starts or ends, so a session that stops while shown becomes
 *   shareable.
 *
 * Owner: workstream `ui-session`.
 */
class SessionDetailViewModel(private val graph: AppGraph, private val dirName: String) : ViewModel() {
    private val mutableState = MutableStateFlow(
        SessionDetailUiState(detail = null, export = ExportState.Idle, deleted = false, loading = true),
    )
    private var loadJob: Job? = null

    val state: StateFlow<SessionDetailUiState> = mutableState.asStateFlow()

    init {
        reload()
        viewModelScope.launch {
            graph.sessionControl.status
                .map { SessionsPresentation.phaseKey(it) }
                .distinctUntilChanged()
                .drop(1)
                .collect { reload() }
        }
    }

    /** Reads the session again. */
    fun reload() {
        if (mutableState.value.deleted) return
        loadJob?.cancel()
        mutableState.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            try {
                val detail = graph.sessions.detail(dirName)
                mutableState.update { it.copy(detail = detail, loading = false, loadFailed = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun export(precision: LocationPrecision) {
        val current = mutableState.value
        if (current.export is ExportState.Building || current.deleting || current.deleted) return
        if (SessionsPresentation.isRunning(dirName, current.detail, graph.sessionControl.status.value)) {
            mutableState.update { it.copy(export = ExportState.Failed(ExportFailure.SESSION_OPEN)) }
            return
        }
        mutableState.update { it.copy(export = ExportState.Building) }
        viewModelScope.launch {
            val next = try {
                ExportState.Ready(graph.sessions.export(dirName, precision))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExportState.Failed(ExportFailure.of(e))
            }
            mutableState.update { it.copy(export = next) }
        }
    }

    /** A zip built at another precision, or a failure, no longer applies once the choice changes. */
    fun selectPrecision(precision: LocationPrecision) {
        mutableState.update { current ->
            when (val export = current.export) {
                is ExportState.Ready -> if (export.result.precision == precision) current else current.copy(export = ExportState.Idle)
                is ExportState.Failed -> current.copy(export = ExportState.Idle)
                ExportState.Idle, ExportState.Building -> current
            }
        }
    }

    fun delete() {
        val current = mutableState.value
        if (current.deleting || current.deleted || current.export is ExportState.Building) return
        if (SessionsPresentation.isRunning(dirName, current.detail, graph.sessionControl.status.value)) {
            mutableState.update { it.copy(deleteError = DeleteError.RECORDING) }
            return
        }
        mutableState.update { it.copy(deleting = true, deleteError = null) }
        viewModelScope.launch {
            val deleted = try {
                graph.sessions.delete(dirName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            mutableState.update {
                if (deleted) it.copy(deleting = false, deleted = true) else it.copy(deleting = false, deleteError = DeleteError.FAILED)
            }
        }
    }

    fun dismissDeleteError() {
        mutableState.update { it.copy(deleteError = null) }
    }
}

/**
 * Session detail: headline tiles from session.json (duration, fresh samples, median fresh interval, gap count),
 * then its other stats (repeats dropped, share at 2 s, screen/Wi-Fi/charging shares, gaps with reasons, zone
 * pauses, stopped by), file sizes
 * and row counts; Share zip with a precision choice (full, about 110 m, none), showing the SHA-256;
 * Delete with confirmation. No upload, no report on the phone.
 *
 * Owner: workstream `ui-session`.
 */
@Composable
fun SessionDetailScreen(
    viewModel: SessionDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }
    SessionDetailContent(
        state = state,
        actions = DetailActions(
            onBack = onBack,
            onRetry = viewModel::reload,
            onExport = viewModel::export,
            onSelectPrecision = viewModel::selectPrecision,
            onDelete = viewModel::delete,
            onDismissDeleteError = viewModel::dismissDeleteError,
        ),
        modifier = modifier,
    )
}

private data class DetailActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onExport: (LocationPrecision) -> Unit,
    val onSelectPrecision: (LocationPrecision) -> Unit,
    val onDelete: () -> Unit,
    val onDismissDeleteError: () -> Unit,
)

/** Shown for a value session.json does not have. */
private const val UNKNOWN_VALUE: String = "—"

@Composable
private fun SessionsContent(
    state: SessionsUiState,
    onOpenSession: (String) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showLoading = rememberDelayedVisibility(state.loading && state.sessions.isEmpty())
    val topBarScroll = rememberTopBarScroll()
    Scaffold(
        modifier = modifier.nestedScroll(topBarScroll.connection),
        topBar = {
            FieldTapTopBar(
                scroll = topBarScroll,
                title = stringResource(R.string.sessions_title),
                onNavigateUp = onBack,
                navigateUpContentDescription = stringResource(R.string.action_back),
                actions = {
                    TopBarAction(
                        icon = FieldTapIcons.Refresh,
                        contentDescription = stringResource(R.string.sessions_refresh),
                        onClick = onRefresh,
                        enabled = !state.loading,
                    )
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.sessions.isEmpty() && state.loading -> if (showLoading) {
                    CenteredContent { LoadingState(message = stringResource(R.string.sessions_loading)) }
                }
                state.sessions.isEmpty() && state.loadFailed -> CenteredContent {
                    EmptyState(
                        title = stringResource(R.string.sessions_error_title),
                        message = stringResource(R.string.sessions_error_message),
                        icon = FieldTapIcons.Error,
                        tone = StatusTone.ERROR,
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = onRefresh,
                    )
                }
                state.sessions.isEmpty() -> CenteredContent {
                    EmptyState(
                        title = stringResource(R.string.sessions_empty_title),
                        message = stringResource(R.string.sessions_empty_message),
                        icon = FieldTapIcons.Sessions,
                        actionLabel = stringResource(R.string.sessions_empty_action),
                        onAction = onBack,
                    )
                }
                else -> SessionsList(state = state, onOpenSession = onOpenSession)
            }
        }
    }
}

@Composable
private fun SessionsList(state: SessionsUiState, onOpenSession: (String) -> Unit) {
    val storage = state.storage
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = screenGutter(), vertical = Spacing.Lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.loadFailed) {
            item(key = "stale") {
                StatusBanner(
                    message = stringResource(R.string.sessions_stale_banner),
                    tone = StatusTone.WARNING,
                    modifier = Modifier.contentWidth(),
                )
            }
        }
        if (storage != null && !storage.canStart) {
            item(key = "storage-full") {
                StatusBanner(
                    message = stringResource(R.string.sessions_storage_full),
                    tone = StatusTone.ERROR,
                    icon = FieldTapIcons.Storage,
                    modifier = Modifier.contentWidth(),
                )
            }
        }
        if (storage != null) {
            item(key = "storage") { StorageCard(storage = storage, modifier = Modifier.contentWidth()) }
        }
        items(state.sessions, key = { it.dirName }) { summary ->
            SessionRow(summary = summary, onOpen = { onOpenSession(summary.dirName) }, modifier = Modifier.contentWidth())
        }
    }
}

@Composable
private fun StorageCard(storage: StorageStatus, modifier: Modifier = Modifier) {
    val fraction = SessionsPresentation.storageFraction(storage)
    SectionCard(
        title = stringResource(R.string.sessions_storage_title),
        icon = FieldTapIcons.Storage,
        modifier = modifier,
    ) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
            color = if (storage.canStart) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        KeyValueRow(
            key = stringResource(R.string.sessions_row_used),
            value = stringResource(
                R.string.sessions_used_value,
                Formats.decimalBytes(storage.usedBytes),
                Formats.decimalBytes(storage.policy.capBytes),
            ),
        )
        KeyValueRow(key = stringResource(R.string.sessions_row_free), value = Formats.decimalBytes(storage.freeBytes))
    }
}

@Composable
private fun SessionRow(summary: SessionSummary, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val status = StopReasons.rowStatus(summary)
    val statusText = when (status) {
        SessionRowStatus.RECORDING -> stringResource(R.string.stop_recording)
        SessionRowStatus.UNREADABLE -> stringResource(R.string.sessions_unreadable)
        SessionRowStatus.INTERRUPTED -> stopReasonText(StopReasons.describe(summary.stoppedBy))
        SessionRowStatus.COMPLETED -> {
            val description = StopReasons.describe(summary.stoppedBy)
            if (description.kind == StopKind.APP_STOP) stopReasonText(description) else null
        }
    }
    val separator = stringResource(R.string.value_separator)
    val detailText = listOfNotNull(
        summary.handsetModel,
        summary.plmns.takeIf { it.isNotEmpty() }?.joinToString(", "),
    ).joinToString(separator).ifEmpty { null }
    SessionListRow(
        title = summary.name ?: summary.dirName,
        startedText = summary.startedUtcMs?.let { DisplayTime.dateTime(it) } ?: summary.dirName,
        onClick = onOpen,
        modifier = modifier,
        status = status,
        statusText = statusText,
        detailText = detailText,
        durationText = StopReasons.durationMs(summary.startedUtcMs, summary.stoppedUtcMs)?.let { Formats.elapsed(it) },
        sizeText = Formats.decimalBytes(summary.sizeBytes),
    )
}

@Composable
private fun SessionDetailContent(state: SessionDetailUiState, actions: DetailActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var precision by rememberSaveable { mutableStateOf(LocationPrecision.FULL) }
    val detail = state.detail
    val meta = detail?.meta
    val recording = detail?.summary?.recording == true
    val title = meta?.name ?: detail?.summary?.name ?: stringResource(R.string.detail_title)
    val shareSubject = stringResource(R.string.detail_share_subject, title)
    val shareFailed = stringResource(R.string.detail_share_failed)

    val deleteErrorText = state.deleteError?.let {
        stringResource(if (it == DeleteError.RECORDING) R.string.detail_delete_recording else R.string.detail_delete_failed)
    }
    LaunchedEffect(state.deleteError) {
        if (deleteErrorText != null) {
            try {
                snackbarHostState.showSnackbar(deleteErrorText)
            } finally {
                actions.onDismissDeleteError()
            }
        }
    }

    val topBarScroll = rememberTopBarScroll()
    Scaffold(
        modifier = modifier.nestedScroll(topBarScroll.connection),
        topBar = {
            FieldTapTopBar(
                scroll = topBarScroll,
                title = title,
                onNavigateUp = actions.onBack,
                navigateUpContentDescription = stringResource(R.string.action_back),
                actions = {
                    if (detail != null && !recording) {
                        TopBarAction(
                            icon = FieldTapIcons.Delete,
                            contentDescription = stringResource(R.string.detail_delete),
                            onClick = { confirmDelete = true },
                            enabled = !state.deleting && state.export != ExportState.Building,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val showLoading = rememberDelayedVisibility(state.loading && detail == null)
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                detail == null && state.loading -> if (showLoading) {
                    CenteredContent { LoadingState(message = stringResource(R.string.detail_loading)) }
                }
                detail == null && state.loadFailed -> CenteredContent {
                    EmptyState(
                        title = stringResource(R.string.detail_error_title),
                        message = stringResource(R.string.detail_error_message),
                        icon = FieldTapIcons.Error,
                        tone = StatusTone.ERROR,
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = actions.onRetry,
                        secondaryActionLabel = stringResource(R.string.action_back),
                        onSecondaryAction = actions.onBack,
                    )
                }
                detail == null -> CenteredContent {
                    EmptyState(
                        title = stringResource(R.string.detail_not_found_title),
                        message = stringResource(R.string.detail_not_found_message),
                        icon = FieldTapIcons.Sessions,
                        actionLabel = stringResource(R.string.action_back),
                        onAction = actions.onBack,
                    )
                }
                meta == null -> CenteredContent {
                    EmptyState(
                        title = stringResource(R.string.detail_error_title),
                        message = stringResource(R.string.detail_unreadable_message),
                        icon = FieldTapIcons.Error,
                        tone = StatusTone.ERROR,
                        actionLabel = if (recording) null else stringResource(R.string.detail_delete),
                        onAction = { confirmDelete = true },
                        secondaryActionLabel = stringResource(R.string.action_back),
                        onSecondaryAction = actions.onBack,
                    )
                }
                else -> DetailList(
                    detail = detail,
                    meta = meta,
                    export = state.export,
                    precision = precision,
                    onPrecision = { choice ->
                        precision = choice
                        actions.onSelectPrecision(choice)
                    },
                    onBuild = { actions.onExport(precision) },
                    onShare = { result, text ->
                        val shared = try {
                            FileSharer.share(
                                context = context,
                                file = result.zip,
                                mimeType = FileSharer.ZIP_MIME_TYPE,
                                subject = shareSubject,
                                text = text,
                            )
                            true
                        } catch (e: ActivityNotFoundException) {
                            false
                        } catch (e: IllegalArgumentException) {
                            false
                        }
                        if (!shared) scope.launch { snackbarHostState.showSnackbar(shareFailed) }
                    },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        actions.onDelete()
                    },
                ) { Text(text = stringResource(R.string.detail_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(text = stringResource(R.string.action_cancel)) }
            },
            icon = { Icon(imageVector = FieldTapIcons.Delete, contentDescription = null) },
            title = { Text(text = stringResource(R.string.detail_delete_title)) },
            text = { Text(text = stringResource(R.string.detail_delete_text)) },
        )
    }
}

@Composable
private fun DetailList(
    detail: SessionDetail,
    meta: SessionMeta,
    export: ExportState,
    precision: LocationPrecision,
    onPrecision: (LocationPrecision) -> Unit,
    onBuild: () -> Unit,
    onShare: (ExportResult, String) -> Unit,
) {
    val recording = detail.summary.recording
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = screenGutter(), vertical = Spacing.Lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.SectionGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (recording) {
            item(key = "recording") {
                StatusBanner(
                    message = stringResource(R.string.detail_recording_banner),
                    tone = StatusTone.INFO,
                    icon = FieldTapIcons.Play,
                    modifier = Modifier.contentWidth(),
                )
            }
        }
        // A marker the session accepted and dropped left no row to show it: said here, wherever Live was when it happened.
        if (detail.markersDropped > 0) {
            item(key = "markers-dropped") {
                StatusBanner(
                    message = pluralStringResource(R.plurals.detail_markers_dropped, detail.markersDropped, detail.markersDropped),
                    tone = StatusTone.WARNING,
                    icon = FieldTapIcons.Flag,
                    modifier = Modifier.contentWidth(),
                )
            }
        }
        item(key = "headline") { HeadlineStats(meta = meta, modifier = Modifier.contentWidth()) }
        item(key = "overview") { OverviewCard(detail = detail, meta = meta, modifier = Modifier.contentWidth()) }
        item(key = "collection") { CollectionCard(meta = meta, modifier = Modifier.contentWidth()) }
        item(key = "gaps") { GapsCard(gaps = meta.collection.gaps, modifier = Modifier.contentWidth()) }
        if (!recording) {
            item(key = "share") {
                ShareCard(
                    sessionName = meta.name,
                    export = export,
                    precision = precision,
                    onPrecision = onPrecision,
                    onBuild = onBuild,
                    onShare = onShare,
                    modifier = Modifier.contentWidth(),
                )
            }
        }
        item(key = "files") { FilesCard(detail = detail, modifier = Modifier.contentWidth()) }
    }
}

/** The numbers a session is judged by first. The top bar already names it, so no card repeats the name. */
@Composable
private fun HeadlineStats(meta: SessionMeta, modifier: Modifier = Modifier) {
    val collection = meta.collection
    MetricGrid(modifier = modifier.fillMaxWidth(), maxColumns = HEADLINE_COLUMNS) {
        MetricTile(
            label = stringResource(R.string.detail_row_duration),
            value = StopReasons.durationMs(meta.startedUtcMs, meta.stoppedUtcMs)?.let { Formats.elapsed(it) },
            emphasis = MetricEmphasis.COMPACT,
        )
        MetricTile(
            label = stringResource(R.string.detail_row_fresh),
            value = collection.freshSamples.toString(),
            emphasis = MetricEmphasis.COMPACT,
        )
        MetricTile(
            label = stringResource(R.string.detail_row_median),
            value = collection.medianFreshIntervalMs?.let { stringResource(R.string.seconds_value, DisplayTime.seconds(it)) },
            emphasis = MetricEmphasis.COMPACT,
        )
        MetricTile(
            label = stringResource(R.string.detail_section_gaps),
            value = collection.gaps.size.toString(),
            emphasis = MetricEmphasis.COMPACT,
        )
    }
}

/** Four headline tiles: two columns on a phone, one row on a tablet or in landscape. */
private const val HEADLINE_COLUMNS: Int = 4

@Composable
private fun OverviewCard(detail: SessionDetail, meta: SessionMeta, modifier: Modifier = Modifier) {
    val separator = stringResource(R.string.value_separator)
    SectionCard(title = stringResource(R.string.detail_section_overview), icon = FieldTapIcons.File, modifier = modifier) {
        meta.note?.let { KeyValueRow(key = stringResource(R.string.detail_row_note), value = it, tabular = false) }
        meta.location?.let { KeyValueRow(key = stringResource(R.string.detail_row_place), value = it, tabular = false) }
        KeyValueRow(key = stringResource(R.string.detail_row_started), value = DisplayTime.dateTime(meta.startedUtcMs))
        KeyValueRow(
            key = stringResource(R.string.detail_row_stopped),
            value = meta.stoppedUtcMs?.let { DisplayTime.dateTime(it) } ?: stringResource(R.string.stop_recording),
        )
        KeyValueRow(
            key = stringResource(R.string.detail_row_stopped_by),
            value = stopReasonText(StopReasons.describe(meta.summary.stoppedBy, detail.summary.recording)),
            tabular = false,
        )
        val handset = listOfNotNull(
            listOfNotNull(meta.handset.manufacturer, meta.handset.model).joinToString(" ").ifEmpty { null },
            meta.handset.androidVersion?.let { "Android $it" },
        ).joinToString(separator)
        KeyValueRow(key = stringResource(R.string.detail_row_handset), value = handset.ifEmpty { UNKNOWN_VALUE }, tabular = false)
        KeyValueRow(
            key = stringResource(R.string.detail_row_app),
            value = stringResource(R.string.detail_app_version_value, meta.transport.appVersion, meta.transport.versionCode),
        )
        if (meta.summary.plmns.isNotEmpty()) {
            KeyValueRow(
                key = stringResource(R.string.detail_row_networks),
                value = meta.summary.plmns.entries.map { (plmn, rows) ->
                    pluralStringResource(R.plurals.detail_plmn_rows, rows, plmn, rows)
                }.joinToString("\n"),
                stacked = true,
            )
        }
        KeyValueRow(
            key = stringResource(R.string.detail_row_precision),
            value = stringResource(precisionNameRes(meta.privacy.locationPrecision)),
            tabular = false,
        )
        KeyValueRow(
            key = stringResource(R.string.detail_row_consent),
            value = SessionsPresentation.consentVersionText(meta.privacy.consentVersion),
        )
    }
}

@Composable
private fun CollectionCard(meta: SessionMeta, modifier: Modifier = Modifier) {
    val collection = meta.collection
    SectionCard(title = stringResource(R.string.detail_section_collection), icon = FieldTapIcons.Timer, modifier = modifier) {
        KeyValueRow(key = stringResource(R.string.detail_row_repeats), value = collection.repeatsDropped.toString())
        PercentRow(R.string.detail_row_short_interval, collection.shortIntervalPct)
        PercentRow(R.string.detail_row_screen_on, collection.screenOnPct)
        PercentRow(R.string.detail_row_wifi, collection.wifiConnectedPct)
        PercentRow(R.string.detail_row_charging, collection.chargingPct)
        KeyValueRow(key = stringResource(R.string.detail_row_zone_pauses), value = meta.privacy.zonePauses.toString())
    }
}

@Composable
private fun PercentRow(@StringRes key: Int, value: Double?) {
    KeyValueRow(
        key = stringResource(key),
        value = DisplayTime.percent(value)?.let { stringResource(R.string.percent_value, it) } ?: UNKNOWN_VALUE,
    )
}

@Composable
private fun GapsCard(gaps: List<GapMeta>, modifier: Modifier = Modifier) {
    SectionCard(title = stringResource(R.string.detail_section_gaps), modifier = modifier) {
        if (gaps.isEmpty()) {
            Text(
                text = stringResource(R.string.detail_gaps_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val listed = SessionsPresentation.listedGaps(gaps)
            for (gap in listed) {
                KeyValueRow(
                    key = DisplayTime.timeWithSeconds(gap.startUtcMs),
                    value = stringResource(R.string.detail_gap_value, Formats.oneDecimal(gap.seconds), gapReasonText(gap.reason)),
                )
            }
            val more = gaps.size - listed.size
            if (more > 0) {
                Text(
                    text = pluralStringResource(R.plurals.detail_gaps_more, more, more),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ShareCard(
    sessionName: String,
    export: ExportState,
    precision: LocationPrecision,
    onPrecision: (LocationPrecision) -> Unit,
    onBuild: () -> Unit,
    onShare: (ExportResult, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val building = export == ExportState.Building
    SectionCard(
        title = stringResource(R.string.detail_section_share),
        subtitle = stringResource(R.string.detail_share_intro),
        icon = FieldTapIcons.Share,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.selectableGroup()) {
            for (option in LocationPrecision.entries) {
                RadioRow(
                    title = stringResource(precisionNameRes(option)),
                    selected = option == precision,
                    onSelect = { onPrecision(option) },
                    supportingText = stringResource(precisionSupportingRes(option)),
                    enabled = !building,
                )
            }
        }
        when (export) {
            ExportState.Idle -> BuildButton(onBuild)
            ExportState.Building -> Row(
                modifier = Modifier
                    .heightIn(min = Sizes.MinTouchTarget)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(Sizes.InlineProgress))
                Text(text = stringResource(R.string.detail_building_zip), style = MaterialTheme.typography.bodyMedium)
            }
            is ExportState.Ready -> {
                val result = export.result
                if (result.precision != precision) {
                    BuildButton(onBuild)
                } else {
                    val shareText = stringResource(
                        R.string.detail_share_text,
                        sessionName,
                        stringResource(precisionNameRes(result.precision)),
                        result.sha256,
                    )
                    KeyValueRow(key = stringResource(R.string.detail_row_zip), value = result.zip.name, tabular = false)
                    KeyValueRow(key = stringResource(R.string.detail_row_zip_size), value = Formats.decimalBytes(result.bytes))
                    KeyValueRow(key = stringResource(R.string.detail_row_sha256), value = result.sha256, stacked = true, selectable = true)
                    Button(
                        onClick = { onShare(result, shareText) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Sizes.MinTouchTarget),
                    ) {
                        Icon(imageVector = FieldTapIcons.Share, contentDescription = null, modifier = Modifier.size(Sizes.IconSmall))
                        Spacer(modifier = Modifier.width(Spacing.Sm))
                        Text(text = stringResource(R.string.detail_share_zip))
                    }
                }
            }
            is ExportState.Failed -> {
                StatusBanner(message = stringResource(exportFailureRes(export.reason)), tone = StatusTone.ERROR)
                OutlinedButton(
                    onClick = onBuild,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) { Text(text = stringResource(R.string.action_retry)) }
            }
        }
        Text(
            text = stringResource(R.string.detail_share_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BuildButton(onBuild: () -> Unit) {
    Button(
        onClick = onBuild,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.MinTouchTarget),
    ) { Text(text = stringResource(R.string.detail_build_zip)) }
}

@Composable
private fun FilesCard(detail: SessionDetail, modifier: Modifier = Modifier) {
    SectionCard(title = stringResource(R.string.detail_section_files), icon = FieldTapIcons.File, modifier = modifier) {
        for (file in SessionFile.BUNDLE) {
            val size = detail.fileSizes[file] ?: continue
            val rows = detail.rowCounts[file]
            KeyValueRow(
                key = file.fileName,
                value = if (rows != null) {
                    pluralStringResource(R.plurals.detail_file_rows, rows, rows, Formats.decimalBytes(size))
                } else {
                    Formats.decimalBytes(size)
                },
            )
        }
    }
}

/** Centres [content] in the free space and scrolls it when it does not fit (landscape, large fonts). */
@Composable
private fun CenteredContent(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun gapReasonText(token: String): String = when (SessionsPresentation.gapReason(token)) {
    GapReason.APP_PAUSED -> stringResource(R.string.gap_app_paused)
    GapReason.NO_SERVICE -> stringResource(R.string.gap_no_service)
    GapReason.SCREEN_OFF -> stringResource(R.string.gap_screen_off)
    GapReason.UNKNOWN -> stringResource(R.string.gap_unknown)
    GapReason.OTHER -> token
}

@StringRes
private fun precisionNameRes(precision: LocationPrecision): Int = when (precision) {
    LocationPrecision.FULL -> R.string.precision_full
    LocationPrecision.APPROX_110M -> R.string.precision_approx
    LocationPrecision.NONE -> R.string.precision_none
}

@StringRes
private fun precisionSupportingRes(precision: LocationPrecision): Int = when (precision) {
    LocationPrecision.FULL -> R.string.precision_full_supporting
    LocationPrecision.APPROX_110M -> R.string.precision_approx_supporting
    LocationPrecision.NONE -> R.string.precision_none_supporting
}

@StringRes
private fun exportFailureRes(reason: String): Int = when (reason) {
    ExportFailure.SESSION_OPEN -> R.string.detail_export_session_open
    ExportFailure.MISSING_SESSION_JSON -> R.string.detail_export_missing_json
    ExportFailure.TOO_LARGE -> R.string.detail_export_too_large
    ExportFailure.IO -> R.string.detail_export_io
    else -> R.string.detail_export_unknown
}

@FieldTapPreviews
@Composable
private fun SessionsContentPreview() {
    val started = 1_789_050_600_000L
    FieldTapTheme {
        SessionsContent(
            state = SessionsUiState(
                loading = false,
                sessions = listOf(
                    SessionSummary("20260910-143000_Walk-14-30", "Walk 14:30", started, null, "recording", true, true, "Pixel 8", listOf("311480"), 120, 812_000),
                    SessionSummary("20260910-091200_Office-to-station", "Office to station", started - 19_000_000, started - 16_500_000, "user", false, true, "Pixel 8", listOf("311480", "310260"), 1_204, 4_200_000),
                    SessionSummary("20260909-180200_Car-park", "Car park", started - 74_000_000, started - 73_630_000, "low_memory", false, true, "OnePlus 12", emptyList(), 180, 96_000),
                    SessionSummary("20260908-101500_Test", null, started - 160_000_000, null, null, false, false, null, emptyList(), null, 12_000),
                ),
                storage = StorageStatus(usedBytes = 5_112_000, freeBytes = 38_000_000_000, policy = StoragePolicy()),
            ),
            onOpenSession = {},
            onBack = {},
            onRefresh = {},
        )
    }
}

@FieldTapPreviews
@Composable
private fun SessionsEmptyPreview() {
    FieldTapTheme {
        SessionsContent(
            state = SessionsUiState(loading = false, sessions = emptyList(), storage = null),
            onOpenSession = {},
            onBack = {},
            onRefresh = {},
        )
    }
}
