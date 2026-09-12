package com.fieldtap.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone

/** The icon that goes with a [StatusTone], so tone is never shown by colour alone. */
fun statusIcon(tone: StatusTone): ImageVector = when (tone) {
    StatusTone.NEUTRAL, StatusTone.INFO -> FieldTapIcons.Info
    StatusTone.SUCCESS -> FieldTapIcons.CheckCircle
    StatusTone.WARNING -> FieldTapIcons.Warning
    StatusTone.ERROR -> FieldTapIcons.Error
}

/**
 * An inline message about the state of measuring, with an optional fix action. Examples: "Wi-Fi is
 * on: Android refreshes cell info only every 10 s" with "Wi-Fi settings" (WARNING); "Session
 * interrupted by Android: low memory" with "View" (ERROR); "Logging paused in a privacy zone" (INFO).
 *
 * Put banners at the top of the content they concern, full width, one at a time per cause. Use a
 * dialog only when the user must decide before anything else can happen.
 *
 * WARNING and ERROR banners are a polite live region by default, so TalkBack announces them when they
 * appear. The close button shows only when both [onDismiss] and [dismissContentDescription] are given.
 */
@Composable
fun StatusBanner(
    message: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector = statusIcon(tone),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    dismissContentDescription: String? = null,
    onDismiss: (() -> Unit)? = null,
    announce: Boolean = tone == StatusTone.WARNING || tone == StatusTone.ERROR,
) {
    val family = FieldTapDesign.colors.status(tone)
    val dismiss = if (dismissContentDescription != null) onDismiss else null
    val action = if (actionLabel != null) onAction else null
    val showDismiss = dismiss != null
    val showAction = action != null
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (announce) liveRegion = LiveRegionMode.Polite },
        shape = ShapeRoles.Card,
        color = family.container,
        contentColor = family.onContainer,
        border = BorderStroke(Sizes.HairlineWidth, family.color),
    ) {
        Column(
            modifier = Modifier.padding(
                start = Spacing.Lg,
                top = Spacing.Md,
                end = if (showDismiss) Spacing.Xs else Spacing.Lg,
                bottom = if (showAction) Spacing.Xs else Spacing.Md,
            ),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(Sizes.Icon),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = Spacing.Xxs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xxs),
                ) {
                    if (title != null) {
                        Text(text = title, style = MaterialTheme.typography.titleSmall)
                    }
                    Text(text = message, style = MaterialTheme.typography.bodyMedium)
                }
                if (dismiss != null) {
                    IconButton(onClick = dismiss) {
                        Icon(imageVector = FieldTapIcons.Close, contentDescription = dismissContentDescription)
                    }
                }
            }
            if (action != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = action,
                        colors = ButtonDefaults.textButtonColors(contentColor = family.onContainer),
                    ) {
                        Text(text = actionLabel.orEmpty(), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@FieldTapPreviews
@Composable
private fun StatusBannerPreview() {
    PreviewSurface {
        StatusBanner(
            message = "Wi-Fi is on: Android refreshes cell info only every 10 s. Turn Wi-Fi off or plug in for 2 s.",
            tone = StatusTone.WARNING,
            actionLabel = "Wi-Fi settings",
            onAction = {},
        )
        StatusBanner(
            title = "Session interrupted",
            message = "Android stopped the app (low memory). The session was closed at its last heartbeat.",
            tone = StatusTone.ERROR,
            actionLabel = "View session",
            onAction = {},
            dismissContentDescription = "Dismiss",
            onDismiss = {},
        )
        StatusBanner(message = "Logging paused in a privacy zone.", tone = StatusTone.INFO)
        StatusBanner(message = "Ready to start.", tone = StatusTone.SUCCESS)
    }
}
