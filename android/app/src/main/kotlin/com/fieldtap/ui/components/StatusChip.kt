package com.fieldtap.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
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
import com.fieldtap.ui.theme.tabular

/**
 * A short state as a **ghost chip**: transparent fill, a 1 px `outline` border, and the tone carried by a
 * leading mark and the word — "In service" (SUCCESS), "Mobile data off" (WARNING), "GPS lost" (ERROR),
 * "5G icon on" (INFO), "Paused in a privacy zone" (INFO). The filled background is reserved for the
 * recording state, so a screen of ghost chips stays calm and colour is never the only cue.
 *
 * For states shown side by side: the Live screen's service, data, 5G icon and GPS line (in a `FlowRow`
 * with [Spacing.Sm] gaps), or a check's level on the Readiness screen. It is not clickable; a state with a
 * fix is a [StatusBanner].
 *
 * @param icon defaults to [statusIcon] for [tone]; pass a subject icon (for example
 *   [FieldTapIcons.GpsOff]) when it says more, or null for a plain tone dot before the word.
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
        color = Color.Transparent,
        contentColor = family.color,
        border = BorderStroke(Sizes.HairlineWidth, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = Sizes.BadgeMinHeight + Spacing.Xs)
                .padding(start = Spacing.Sm, top = Spacing.Xxs, end = Spacing.Md, bottom = Spacing.Xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs + Spacing.Xxs),
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(Sizes.IconSmall))
            } else {
                Box(modifier = Modifier.size(Sizes.Swatch).background(family.color, CircleShape))
            }
            Text(
                text = text,
                // Tabular: a chip whose number changes every second ("GPS fix, ±4 m") keeps its width.
                style = MaterialTheme.typography.labelLarge.tabular(),
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
            StatusChip(text = "Screen off", tone = StatusTone.NEUTRAL, icon = null)
        }
    }
}
