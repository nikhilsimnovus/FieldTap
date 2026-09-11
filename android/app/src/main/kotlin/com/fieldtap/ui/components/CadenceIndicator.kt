package com.fieldtap.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone
import com.fieldtap.ui.theme.tabular

/**
 * Android's cell-info refresh interval right now, with the reason: "2 s" (screen on, Wi-Fi off or
 * charging) or "10 s" with "Wi-Fi on while not charging" or "Screen off". Pocket mode is labelled
 * "10 s cadence" by the same component.
 *
 * [shortInterval] is `LiveState.shortInterval`: true tints it green (success), false amber (warning,
 * because logging works but is sparser), null neutral before the first answer ([cadenceTone]). The
 * interval text is always shown, so the tint is never the only cue.
 *
 * @param intervalText "2 s" or "10 s"; [label] for example "Cadence"; [reason] one short phrase.
 * @param onClick optional, for example to open walk-mode help; the whole indicator is then a 48 dp button.
 */
@Composable
fun CadenceIndicator(
    intervalText: String,
    shortInterval: Boolean?,
    modifier: Modifier = Modifier,
    label: String? = null,
    reason: String? = null,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val family = FieldTapDesign.colors.status(cadenceTone(shortInterval))
    val description = contentDescription ?: listOfNotNull(label, intervalText, reason).joinToString(", ")
    val semantics = Modifier.clearAndSetSemantics {
        this.contentDescription = description
        if (onClick != null) {
            role = Role.Button
            this.onClick(label = null) {
                onClick()
                true
            }
        }
    }
    val shape = if (reason == null) ShapeRoles.Pill else ShapeRoles.Tile
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .heightIn(min = if (onClick != null) Sizes.MinTouchTarget else Sizes.BadgeMinHeight + Spacing.Md)
                .padding(horizontal = Spacing.Md, vertical = Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            Icon(imageVector = FieldTapIcons.Timer, contentDescription = null, modifier = Modifier.size(Sizes.IconSmall))
            if (label != null) {
                Text(text = label, style = MaterialTheme.typography.labelLarge)
            }
            Text(text = intervalText, style = MaterialTheme.typography.titleSmall.tabular())
            if (reason != null) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.then(semantics),
            shape = shape,
            color = family.container,
            contentColor = family.onContainer,
            content = body,
        )
    } else {
        Surface(
            modifier = modifier.then(semantics),
            shape = shape,
            color = family.container,
            contentColor = family.onContainer,
            content = body,
        )
    }
}

/** The tone of the cadence: the 2 s interval SUCCESS, the 10 s interval WARNING, unknown NEUTRAL. */
fun cadenceTone(shortInterval: Boolean?): StatusTone = when (shortInterval) {
    true -> StatusTone.SUCCESS
    false -> StatusTone.WARNING
    null -> StatusTone.NEUTRAL
}

@FieldTapPreviews
@Composable
private fun CadenceIndicatorPreview() {
    PreviewSurface {
        CadenceIndicator(intervalText = "2 s", shortInterval = true, label = "Cadence")
        CadenceIndicator(intervalText = "10 s", shortInterval = false, label = "Cadence", reason = "Wi-Fi on while not charging", onClick = {})
        CadenceIndicator(intervalText = "—", shortInterval = null, label = "Cadence")
    }
}
