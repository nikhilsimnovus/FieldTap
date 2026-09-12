package com.fieldtap.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.fieldtap.core.live.AgeBadge
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.LocalReducedMotion
import com.fieldtap.ui.theme.Motion
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone

/**
 * How old a live value is, as a pill: "2.1 s old".
 *
 * [badge] comes from `LiveState.badge` (`LiveStateReducer.badge`: at most 2.5 s FRESH, at most 11 s
 * AGING, older STALE), so the screen never decides thresholds. FRESH is neutral; AGING turns amber
 * with a timer icon; STALE turns red with a warning icon ([ageTone]). The icon and the number mean
 * colour is never the only cue.
 *
 * @param text the whole visible label, from a string resource, for example
 *   `stringResource(R.string.age_old, Formats.ageSeconds(ageMs))`.
 * @param contentDescription what TalkBack reads instead, for example "2.1 seconds old"; null reads [text].
 */
@Composable
fun AgeIndicator(
    text: String,
    badge: AgeBadge,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val family = FieldTapDesign.colors.status(ageTone(badge))
    val reduced = LocalReducedMotion.current
    val container by animateColorAsState(family.container, Motion.effect(reduced), label = "ageContainer")
    val content by animateColorAsState(family.onContainer, Motion.effect(reduced), label = "ageContent")
    val icon: ImageVector? = when (badge) {
        AgeBadge.NONE, AgeBadge.FRESH -> null
        AgeBadge.AGING -> FieldTapIcons.Timer
        AgeBadge.STALE -> FieldTapIcons.Warning
    }
    val semantics = if (contentDescription != null) {
        Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    Surface(
        modifier = modifier.then(semantics),
        shape = ShapeRoles.Pill,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = Sizes.BadgeMinHeight)
                .padding(horizontal = Spacing.Sm, vertical = Spacing.Xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(Sizes.IconTiny))
            }
            Text(
                text = text,
                style = FieldTapDesign.numeric.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The tone of an age badge: NONE and FRESH neutral, AGING warning (amber), STALE error (red). */
fun ageTone(badge: AgeBadge): StatusTone = when (badge) {
    AgeBadge.NONE, AgeBadge.FRESH -> StatusTone.NEUTRAL
    AgeBadge.AGING -> StatusTone.WARNING
    AgeBadge.STALE -> StatusTone.ERROR
}

@FieldTapPreviews
@Composable
private fun AgeIndicatorPreview() {
    PreviewSurface {
        AgeIndicator(text = "0.8 s old", badge = AgeBadge.FRESH)
        AgeIndicator(text = "6.4 s old", badge = AgeBadge.AGING)
        AgeIndicator(text = "38 s old", badge = AgeBadge.STALE)
        AgeIndicator(text = "No sample yet", badge = AgeBadge.NONE)
    }
}
