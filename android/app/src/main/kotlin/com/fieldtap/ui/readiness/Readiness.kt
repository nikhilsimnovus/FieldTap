package com.fieldtap.ui.readiness

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import com.fieldtap.app.AppGraph
import com.fieldtap.app.SoakState
import com.fieldtap.core.readiness.ReadinessReport
import kotlinx.coroutines.flow.StateFlow

data class ReadinessUiState(
    val report: ReadinessReport?,
    val checking: Boolean,
    val soak: SoakState,
)

/** Owner: workstream `ui-setup`. */
class ReadinessViewModel(private val graph: AppGraph) : ViewModel() {
    val state: StateFlow<ReadinessUiState> get() = TODO("ui-setup")

    /** Runs the checks; call again on resume, since settings screens change the answers. */
    fun check(): Unit = TODO("ui-setup")

    fun startSoak(): Unit = TODO("ui-setup")

    fun cancelSoak(): Unit = TODO("ui-setup")
}

/**
 * Readiness: one row per check with its level and a button to its settings screen
 * (com.fieldtap.ui.setup.SettingsIntents), OEM guidance on OnePlus, OPPO and realme, and the optional
 * 10-minute screen-off soak test with "seconds logged / seconds elapsed".
 *
 * Owner: workstream `ui-setup`.
 */
@Composable
fun ReadinessScreen(
    viewModel: ReadinessViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TODO("ui-setup")
}
