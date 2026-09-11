package com.fieldtap.ui.probe

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import com.fieldtap.app.AppGraph
import com.fieldtap.core.probe.ProbeReport
import java.io.File
import kotlinx.coroutines.flow.StateFlow

data class ProbeUiState(
    val running: Boolean,
    val progress: String?,
    val report: ProbeReport?,
    val exported: File?,
)

/** Owner: workstream `ui-setup`. */
class ProbeViewModel(private val graph: AppGraph) : ViewModel() {
    val state: StateFlow<ProbeUiState> get() = TODO("ui-setup")

    fun run(): Unit = TODO("ui-setup")

    fun export(): Unit = TODO("ui-setup")
}

/**
 * Capability probe: Run (30 s, screen must stay on), the report as readable rows (neighbours, band lists,
 * timestamps advance, SINR range, refused listeners and permissions), and Export JSON shared through
 * com.fieldtap.ui.common.FileSharer.
 *
 * Owner: workstream `ui-setup`.
 */
@Composable
fun ProbeScreen(
    viewModel: ProbeViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TODO("ui-setup")
}
