package com.fieldtap.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import com.fieldtap.app.AppGraph
import com.fieldtap.core.privacy.ConsentText
import kotlinx.coroutines.flow.StateFlow

/** Owner: workstream `ui-setup`. */
class OnboardingViewModel(private val graph: AppGraph) : ViewModel() {
    /** `Consent.CURRENT`, shown verbatim. */
    val consentText: ConsentText get() = TODO("ui-setup")

    /** True once the stored consent matches the current text. */
    val consentCurrent: StateFlow<Boolean> get() = TODO("ui-setup")

    /** Stores a `ConsentRecord` for the current version with the wall clock. */
    fun accept(): Unit = TODO("ui-setup")
}

/**
 * Full-screen disclosure, shown before any location prompt: the consent text verbatim, the limits
 * statement, Accept and Not now. Declining leaves the app usable for About only; no prompt is shown.
 *
 * Owner: workstream `ui-setup`.
 */
@Composable
fun DisclosureScreen(
    viewModel: OnboardingViewModel,
    onAccepted: () -> Unit,
    onDeclined: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TODO("ui-setup")
}

/**
 * Permissions, in order, each with one sentence of why: precise location (fine and coarse together;
 * "approximate" is explained as returning no cell info), notifications (Stop and Mark in the
 * notification), and optionally phone (push updates). Refusals link to app settings. Uses
 * `rememberLauncherForActivityResult(RequestMultiplePermissions())`.
 *
 * Owner: workstream `ui-setup`.
 */
@Composable
fun PermissionsScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TODO("ui-setup")
}
