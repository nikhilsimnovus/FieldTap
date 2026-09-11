package com.fieldtap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextOverflow
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone

/**
 * A short state in a pill with its tone's icon: "In service" (SUCCESS), "Mobile data off" (WARNING),
 * "GPS lost" (ERROR), "5G icon on" (INFO), "Paused in a privacy zone" (INFO).
 *
 * For states shown side by side: the Live screen's service, data, 5G icon and GPS line (in a `FlowRow`
 * with [Spacing.Sm] gaps), or a check's level on the Readiness screen. The icon and the words carry the
 * meaning, so the tint is never the only cue. It is not clickable; a state with a fix is a
 * [StatusBanner].
 *
 * @param icon defaults to [statusIcon] for [tone]; pass a subject icon (for example
 *   [FieldTapIcons.GpsOff]) when it says more, or null for text only.
 */
@Composable
fun StatusChip(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = statusIcon(tone),
    contentDescription: String? = null,
) {
    val family = FieldTapDesign.colors.status(tone)
    val semantics = if (contentDescription != null) {
        Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    Surface(
        modifier = modifier.then(semantics),
        shape = ShapeRoles.Pill,
        color = family.container,
        contentColor = family.onContainer,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = Sizes.BadgeMinHeight + Spacing.Xs)
                .padding(
                    start = if (icon != null) Spacing.Sm else Spacing.Md,
                    top = Spacing.Xxs,
                    end = Spacing.Md,
                    bottom = Spacing.Xxs,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs + Spacing.Xxs),
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(Sizes.IconSmall))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@FieldTapPreviews
@Composable
private fun StatusChipPreview() {
    PreviewSurface {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            StatusChip(text = "In service", tone = StatusTone.SUCCESS)
            StatusChip(text = "Mobile data: LTE", tone = StatusTone.NEUTRAL, icon = FieldTapIcons.Transfer)
            StatusChip(text = "5G icon on", tone = StatusTone.INFO, icon = FieldTapIcons.SignalBars)
            StatusChip(text = "GPS lost", tone = StatusTone.ERROR, icon = FieldTapIcons.GpsOff)
            StatusChip(text = "Emergency calls only", tone = StatusTone.WARNING)
        }
    }
}
