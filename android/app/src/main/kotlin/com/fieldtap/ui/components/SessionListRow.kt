package com.fieldtap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusColors
import com.fieldtap.ui.theme.tabular

/** What a [SessionListRow]'s leading badge says about the session. */
enum class SessionRowStatus {
    /** Stopped by the user or by an app stop (storage full, permission revoked). */
    COMPLETED,

    /** The running session. */
    RECORDING,

    /** Closed by launch recovery after Android stopped the app. */
    INTERRUPTED,

    /** session.json could not be read; the row can still be opened and deleted. */
    UNREADABLE,
}

/**
 * One session in the Sessions list: name, start time, what else matters (handset, PLMNs), duration and
 * size, with a status badge. The whole row is one 72 dp touch target that opens the session.
 *
 * Wording of [statusText] comes from the screen: "Recording", "Interrupted by Android: low memory",
 * "Stopped: storage full", "Could not read session.json". The badge icon differs per status, so the
 * status is not told by colour alone.
 *
 * @param startedText local date and time, for example "10 Sep 2026, 14:30".
 * @param durationText [com.fieldtap.ui.theme.Formats.elapsed], for example "12:34".
 * @param sizeText [com.fieldtap.ui.theme.Formats.decimalBytes], for example "4.2 MB".
 */
@Composable
fun SessionListRow(
    title: String,
    startedText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: SessionRowStatus = SessionRowStatus.COMPLETED,
    statusText: String? = null,
    detailText: String? = null,
    durationText: String? = null,
    sizeText: String? = null,
    contentDescription: String? = null,
) {
    val colors = FieldTapDesign.colors
    val (family: StatusColors, icon: ImageVector) = when (status) {
        SessionRowStatus.COMPLETED -> colors.neutral to FieldTapIcons.File
        SessionRowStatus.RECORDING -> colors.recording to FieldTapIcons.Play
        SessionRowStatus.INTERRUPTED -> colors.warning to FieldTapIcons.Warning
        SessionRowStatus.UNREADABLE -> colors.error to FieldTapIcons.Error
    }
    val semantics = if (contentDescription != null) {
        Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .then(semantics),
        shape = ShapeRoles.Tile,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = Sizes.ListRowMinHeight)
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Lg),
        ) {
            Surface(shape = CircleShape, color = family.container, contentColor = family.onContainer) {
                Box(modifier = Modifier.size(Sizes.IconContainer), contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(Sizes.IconSmall + Spacing.Xxs))
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.Xxs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = startedText,
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (statusText != null) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (status == SessionRowStatus.COMPLETED) MaterialTheme.colorScheme.onSurfaceVariant else family.color,
                    )
                }
                if (detailText != null) {
                    Text(
                        text = detailText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (durationText != null || sizeText != null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.Xxs)) {
                    if (durationText != null) {
                        Text(
                            text = durationText,
                            style = MaterialTheme.typography.titleSmall.tabular(),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                        )
                    }
                    if (sizeText != null) {
                        Text(
                            text = sizeText,
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
            Icon(
                imageVector = FieldTapIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Sizes.IconSmall + Spacing.Xxs),
            )
        }
    }
}

@FieldTapPreviews
@Composable
private fun SessionListRowPreview() {
    PreviewSurface {
        SessionListRow(
            title = "Mall walk (north path)",
            startedText = "Today, 14:30",
            status = SessionRowStatus.RECORDING,
            statusText = "Recording",
            durationText = "12:34",
            onClick = {},
        )
        SessionListRow(
            title = "Office to station",
            startedText = "10 Sep 2026, 09:12",
            detailText = "Pixel 8 · 311480, 310260",
            durationText = "41:07",
            sizeText = "4.2 MB",
            onClick = {},
        )
        SessionListRow(
            title = "Basement car park",
            startedText = "9 Sep 2026, 18:02",
            status = SessionRowStatus.INTERRUPTED,
            statusText = "Interrupted by Android: low memory",
            durationText = "6:10",
            sizeText = "812 kB",
            onClick = {},
        )
        SessionListRow(
            title = "20260908-101500_Test",
            startedText = "8 Sep 2026, 10:15",
            status = SessionRowStatus.UNREADABLE,
            statusText = "Could not read session.json",
            sizeText = "96 kB",
            onClick = {},
        )
    }
}
