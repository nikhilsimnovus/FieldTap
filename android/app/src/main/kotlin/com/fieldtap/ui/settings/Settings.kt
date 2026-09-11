package com.fieldtap.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import com.fieldtap.app.AppGraph
import com.fieldtap.core.nettest.TestSettings
import com.fieldtap.core.privacy.PrivacyZone
import com.fieldtap.core.settings.AppSettings
import kotlinx.coroutines.flow.StateFlow

data class SettingsUiState(
    val settings: AppSettings?,
    /** Validation problems of the zone being edited (`PrivacyZones.validate`). */
    val zoneProblems: List<String>,
)

/** Owner: workstream `ui-setup`. */
class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val state: StateFlow<SettingsUiState> get() = TODO("ui-setup")

    fun updateTests(tests: TestSettings): Unit = TODO("ui-setup")

    fun setTestsDefaultOn(on: Boolean): Unit = TODO("ui-setup")

    fun setWalkModeDefault(on: Boolean): Unit = TODO("ui-setup")

    /** Adds or replaces by id, after validation. */
    fun saveZone(zone: PrivacyZone): Unit = TODO("ui-setup")

    fun deleteZone(id: String): Unit = TODO("ui-setup")

    /** A zone centred on the newest fix (from `AppGraph.live`), for "add zone here". */
    fun zoneAtCurrentPosition(label: String, radiusM: Double): PrivacyZone? = TODO("ui-setup")
}

/**
 * Settings: tests opt-in default, ping target, download URL (HTTPS only), intervals, cap and per-session
 * budget; privacy zones (label, radius, "here" from the current fix, or typed coordinates), with a note
 * that zones apply to new sessions and nothing is written inside them. No map tiles.
 *
 * Owner: workstream `ui-setup`.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TODO("ui-setup")
}
