package com.fieldtap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone

/**
 * What a screen shows when it has nothing to show, or could not load it: an icon, a title that says
 * what is missing, one sentence on what to do, and at most two actions.
 *
 * - Empty: "No sessions yet" / "Start a session on the Live screen." / "Go to Live" (NEUTRAL).
 * - Waiting: "Waiting for the first cell measurement" (INFO).
 * - Error: "Could not read this session" / the reason / "Delete" (ERROR).
 *
 * Centre it in the free space of the screen. The title is a TalkBack heading.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector = FieldTapIcons.Info,
    tone: StatusTone = StatusTone.NEUTRAL,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val family = FieldTapDesign.colors.status(tone)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Xl, vertical = Spacing.Xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        Surface(shape = CircleShape, color = family.container, contentColor = family.onContainer) {
            Box(modifier = Modifier.size(Sizes.EmptyStateBadge), contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(Sizes.IconHero))
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = Spacing.Sm)
                .widthIn(max = Sizes.MaxTextWidth)
                .semantics { heading() },
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = Sizes.MaxTextWidth),
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier
                    .padding(top = Spacing.Sm)
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(text = actionLabel)
            }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            TextButton(onClick = onSecondaryAction, modifier = Modifier.heightIn(min = Sizes.MinTouchTarget)) {
                Text(text = secondaryActionLabel)
            }
        }
    }
}

/**
 * Loading: a progress spinner and an optional line saying what is loading ("Reading sessions…").
 * Show it only when the wait can exceed about 300 ms; for shorter waits keep the previous content.
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.Xxl)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Lg),
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = Sizes.MaxTextWidth),
            )
        }
    }
}

@FieldTapPreviews
@Composable
private fun EmptyStatePreview() {
    PreviewSurface {
        EmptyState(
            title = "No sessions yet",
            message = "Start a session on the Live screen. Sessions stay on this phone until you share them.",
            icon = FieldTapIcons.Sessions,
            actionLabel = "Go to Live",
            onAction = {},
        )
        EmptyState(
            title = "Could not read this session",
            message = "session.json is damaged. The measurement files may still be intact.",
            icon = FieldTapIcons.Error,
            tone = StatusTone.ERROR,
            actionLabel = "Delete session",
            onAction = {},
            secondaryActionLabel = "Back",
            onSecondaryAction = {},
        )
        LoadingState(message = "Reading sessions…")
    }
}
