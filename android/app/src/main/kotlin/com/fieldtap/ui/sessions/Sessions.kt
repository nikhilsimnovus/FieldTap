package com.fieldtap.ui.sessions

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import com.fieldtap.app.AppGraph
import com.fieldtap.app.SessionDetail
import com.fieldtap.app.SessionSummary
import com.fieldtap.core.export.ExportResult
import com.fieldtap.core.session.StorageStatus
import com.fieldtap.format.LocationPrecision
import kotlinx.coroutines.flow.StateFlow

data class SessionsUiState(
    val loading: Boolean,
    val sessions: List<SessionSummary>,
    val storage: StorageStatus?,
)

/** Owner: workstream `ui-session`. */
class SessionsViewModel(private val graph: AppGraph) : ViewModel() {
    val state: StateFlow<SessionsUiState> get() = TODO("ui-session")

    fun refresh(): Unit = TODO("ui-session")
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
    TODO("ui-session")
}

sealed interface ExportState {
    data object Idle : ExportState

    data object Building : ExportState

    data class Ready(val result: ExportResult) : ExportState

    data class Failed(val reason: String) : ExportState
}

data class SessionDetailUiState(
    val detail: SessionDetail?,
    val export: ExportState,
    val deleted: Boolean,
)

/** Owner: workstream `ui-session`. */
class SessionDetailViewModel(private val graph: AppGraph, private val dirName: String) : ViewModel() {
    val state: StateFlow<SessionDetailUiState> get() = TODO("ui-session")

    fun export(precision: LocationPrecision): Unit = TODO("ui-session")

    fun delete(): Unit = TODO("ui-session")
}

/**
 * Session detail: stats from session.json (duration, fresh samples, repeats dropped, median interval,
 * share at 2 s, screen/Wi-Fi/charging shares, gaps with reasons, zone pauses, stopped by), file sizes
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
    TODO("ui-session")
}
