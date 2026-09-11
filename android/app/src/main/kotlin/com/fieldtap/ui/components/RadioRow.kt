package com.fieldtap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing

/**
 * One option of a single choice, for example the location precision of a shared zip: "Full precision",
 * "About 110 m", "No location". The whole row selects, at least [Sizes.SettingsRowMinHeight] tall.
 * Put the rows of one choice in `Column(Modifier.selectableGroup())`, so TalkBack reads them as a group
 * ("1 of 3").
 */
@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.SettingsRowMinHeight)
            .clip(ShapeRoles.Field)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Xxs)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
    }
}

@FieldTapPreviews
@Composable
private fun RadioRowPreview() {
    PreviewSurface {
        SectionCard(title = "Location in the shared zip") {
            Column(modifier = Modifier.selectableGroup()) {
                RadioRow(title = "Full precision", supportingText = "As recorded", selected = false, onSelect = {})
                RadioRow(title = "About 110 m", supportingText = "Coordinates rounded to 3 decimals", selected = true, onSelect = {})
                RadioRow(title = "No location", supportingText = "Blanks lat and lon, leaves out track.csv", selected = false, onSelect = {})
            }
        }
    }
}
