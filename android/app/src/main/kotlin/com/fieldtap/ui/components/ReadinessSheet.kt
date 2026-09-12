package com.fieldtap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone
import kotlinx.coroutines.launch

/**
 * One named problem found when Start was tapped, with its fix.
 *
 * @param id stable key, for example the `ReadinessCheck` or `StartRefusal` name.
 * @param title the problem in a few words: "Battery optimisation is on".
 * @param detail what it does to the session, in one sentence.
 * @param blocking true for the four that block a start (no consent, no precise location, location off,
 *   storage full); false for advice that still allows "Start anyway".
 * @param fixLabel for example "Battery settings"; [onFix] opens the setting.
 */
@Immutable
data class ReadinessProblem(
    val id: String,
    val title: String,
    val detail: String,
    val blocking: Boolean,
    val icon: ImageVector? = null,
    val fixLabel: String? = null,
    val onFix: (() -> Unit)? = null,
)

/** The rules of the pre-start sheet, kept pure so they are unit-tested. */
object ReadinessProblems {
    /** Blocking problems first; otherwise the order given (the sort is stable). */
    fun ordered(problems: List<ReadinessProblem>): List<ReadinessProblem> = problems.sortedByDescending { it.blocking }

    /** "Start anyway" is offered only when no problem blocks. */
    fun canStartAnyway(problems: List<ReadinessProblem>): Boolean = problems.none { it.blocking }
}

/**
 * The pre-start readiness sheet (ARCHITECTURE.md decision 7) in a modal bottom sheet. Show it only when
 * the checks found at least one problem; with none, start directly.
 *
 * Blocking problems come first. With any blocking problem "Start anyway" is hidden and [blockedMessage]
 * says why; otherwise the user may fix each problem or start anyway. Dismissing (swipe, scrim, back,
 * Cancel) calls [onDismissRequest]. The sheet animates away before [onStartAnyway] or [onDismissRequest]
 * runs; remove it from composition then.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadinessSheet(
    title: String,
    problems: List<ReadinessProblem>,
    startAnywayLabel: String,
    cancelLabel: String,
    onStartAnyway: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    blockedMessage: String? = null,
    blockingTag: String? = null,
    adviceTag: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hideThen: (() -> Unit) -> Unit = { action ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) action()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = ShapeRoles.Sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ReadinessSheetContent(
            title = title,
            problems = problems,
            startAnywayLabel = startAnywayLabel,
            cancelLabel = cancelLabel,
            onStartAnyway = { hideThen(onStartAnyway) },
            onCancel = { hideThen(onDismissRequest) },
            message = message,
            blockedMessage = blockedMessage,
            blockingTag = blockingTag,
            adviceTag = adviceTag,
        )
    }
}

/**
 * The sheet's layout without the sheet, for previews, tests and a full-screen fallback: title, optional
 * message, the problems as [ChecklistRow]s (blocking first, each with its fix), then Cancel and Start
 * anyway. The list scrolls while the buttons stay visible, so give it a bounded height (a sheet or a
 * whole screen), never a scrolling parent.
 *
 * @param blockingTag the level word on blocking problems ("Required"); [adviceTag] on the others
 *   ("Recommended").
 */
@Composable
fun ReadinessSheetContent(
    title: String,
    problems: List<ReadinessProblem>,
    startAnywayLabel: String,
    cancelLabel: String,
    onStartAnyway: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    blockedMessage: String? = null,
    blockingTag: String? = null,
    adviceTag: String? = null,
) {
    val canStartAnyway = ReadinessProblems.canStartAnyway(problems)
    val ordered = ReadinessProblems.ordered(problems)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Xl)
            .padding(bottom = Spacing.Lg),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.Sm),
            )
        }
        if (!canStartAnyway && blockedMessage != null) {
            StatusBanner(message = blockedMessage, tone = StatusTone.ERROR, modifier = Modifier.padding(top = Spacing.Md))
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(top = Spacing.Sm),
        ) {
            ordered.forEachIndexed { index, problem ->
                if (index > 0) SectionDivider()
                ChecklistRow(
                    title = problem.title,
                    tone = if (problem.blocking) StatusTone.ERROR else StatusTone.WARNING,
                    detail = problem.detail,
                    statusText = if (problem.blocking) blockingTag else adviceTag,
                    icon = problem.icon,
                    actionLabel = problem.fixLabel,
                    onAction = problem.onFix,
                )
            }
        }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.Lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = Sizes.MinTouchTarget)) {
                Text(text = cancelLabel)
            }
            if (canStartAnyway) {
                Button(onClick = onStartAnyway, shape = ShapeRoles.Control, modifier = Modifier.heightIn(min = Sizes.MinTouchTarget)) {
                    Text(text = startAnywayLabel)
                }
            }
        }
    }
}

@FieldTapPreviews
@Composable
private fun ReadinessSheetContentPreview() {
    PreviewSurface {
        ReadinessSheetContent(
            title = "Before you start",
            message = "These can cost you samples. Fix them, or start anyway.",
            problems = listOf(
                ReadinessProblem(
                    id = "WIFI_OFF",
                    title = "Wi-Fi is on",
                    detail = "Android refreshes cell info only every 10 s while Wi-Fi is on and the phone is not charging.",
                    blocking = false,
                    icon = FieldTapIcons.Wifi,
                    fixLabel = "Wi-Fi settings",
                    onFix = {},
                ),
                ReadinessProblem(
                    id = "BATTERY_OPTIMISATION",
                    title = "Battery optimisation is on",
                    detail = "Android may stop the session when the screen is off.",
                    blocking = false,
                    icon = FieldTapIcons.Battery,
                    fixLabel = "Battery settings",
                    onFix = {},
                ),
            ),
            startAnywayLabel = "Start anyway",
            cancelLabel = "Cancel",
            onStartAnyway = {},
            onCancel = {},
            adviceTag = "Recommended",
        )
        ReadinessSheetContent(
            title = "Can't start yet",
            problems = listOf(
                ReadinessProblem(
                    id = "LOCATION_ENABLED",
                    title = "Location is off",
                    detail = "Android gives no cell information while location is off.",
                    blocking = true,
                    icon = FieldTapIcons.Location,
                    fixLabel = "Location settings",
                    onFix = {},
                ),
            ),
            blockedMessage = "Fix the problem below to start a session.",
            startAnywayLabel = "Start anyway",
            cancelLabel = "Close",
            onStartAnyway = {},
            onCancel = {},
            blockingTag = "Required",
        )
    }
}
