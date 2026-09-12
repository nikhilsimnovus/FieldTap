package com.fieldtap.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.SignalQuality
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone
import com.fieldtap.ui.theme.tabular

/** Material's opacity for disabled content, shared by the rows in this package. */
internal const val DISABLED_ALPHA: Float = 0.38f

/** An outlined button with a trailing icon: the mirror of `ButtonDefaults.ButtonWithIconContentPadding`. */
private val TrailingIconButtonPadding =
    PaddingValues(start = Spacing.Xl, top = Spacing.Sm, end = Spacing.Lg, bottom = Spacing.Sm)

/**
 * A setting that is on or off: "Instant cell updates", "Run ping and download tests", "Walk mode".
 *
 * The whole row toggles, at least [Sizes.SettingsRowMinHeight] tall, and TalkBack reads it as one
 * switch with its title and supporting text. Put it in a [SectionCard]. When turning it on needs a
 * permission, the screen asks first and then updates [checked].
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val titleColor = MaterialTheme.colorScheme.onSurface.dimmedUnless(enabled)
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant.dimmedUnless(enabled)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.SettingsRowMinHeight)
            .clip(ShapeRoles.Field)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Lg),
    ) {
        if (icon != null) {
            RowLeadingIcon(icon = icon, tint = secondaryColor)
        }
        RowTexts(
            title = title,
            supportingText = supportingText,
            titleColor = titleColor,
            supportingColor = secondaryColor,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A row that opens another screen or a system setting: "Privacy zones", "Readiness check", "About",
 * "Battery settings". Leading icon, title, optional supporting text and current value, then a chevron,
 * or the open-in-new icon when [opensExternally] (system settings, another app).
 */
@Composable
fun NavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: ImageVector? = null,
    valueText: String? = null,
    opensExternally: Boolean = false,
    enabled: Boolean = true,
) {
    val titleColor = MaterialTheme.colorScheme.onSurface.dimmedUnless(enabled)
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant.dimmedUnless(enabled)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.SettingsRowMinHeight)
            .clip(ShapeRoles.Field)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Lg),
    ) {
        if (icon != null) {
            RowLeadingIcon(icon = icon, tint = secondaryColor)
        }
        RowTexts(
            title = title,
            supportingText = supportingText,
            titleColor = titleColor,
            supportingColor = secondaryColor,
            modifier = Modifier.weight(1f),
        )
        if (valueText != null) {
            Text(
                text = valueText,
                style = FieldTapDesign.numeric.bodySmall,
                color = secondaryColor,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = Sizes.TileMinWidth),
            )
        }
        Icon(
            imageVector = if (opensExternally) FieldTapIcons.OpenInNew else FieldTapIcons.ChevronRight,
            contentDescription = null,
            tint = secondaryColor,
            modifier = Modifier.size(Sizes.IconSmall + Spacing.Xxs),
        )
    }
}

/**
 * One check and where it stands, with its fix: an item on the Readiness screen, a problem in the
 * pre-start sheet, a probe finding.
 *
 * [tone] gives the icon and colour: SUCCESS for OK, WARNING for advice, ERROR for a blocker (from
 * `ReadinessLevel` OK, ADVICE, BLOCKER). [statusText] names the level in words ("OK", "Recommended",
 * "Required"), so the tone is never the only cue. TalkBack reads title, level and detail as one item
 * and the fix button on its own.
 *
 * @param actionLabel with [onAction], an outlined button under the detail, for example "Battery
 *   settings". [actionOpensExternally] adds the open-in-new icon, for system settings.
 */
@Composable
fun ChecklistRow(
    title: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    detail: String? = null,
    statusText: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionOpensExternally: Boolean = true,
) {
    val family = FieldTapDesign.colors.status(tone)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Lg),
    ) {
        Surface(shape = CircleShape, color = family.container, contentColor = family.onContainer) {
            Box(modifier = Modifier.size(Sizes.IconContainer), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon ?: statusIcon(tone),
                    contentDescription = null,
                    modifier = Modifier.size(Sizes.IconSmall + Spacing.Xxs),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    if (statusText != null) {
                        Surface(shape = ShapeRoles.Pill, color = family.container, contentColor = family.onContainer) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = Spacing.Sm, vertical = Spacing.Xxs),
                            )
                        }
                    }
                }
                if (detail != null) {
                    Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (actionLabel != null && onAction != null) {
                OutlinedButton(
                    onClick = onAction,
                    shape = ShapeRoles.Control,
                    modifier = Modifier
                        .padding(top = Spacing.Xs)
                        .heightIn(min = Sizes.MinTouchTarget),
                    contentPadding = if (actionOpensExternally) TrailingIconButtonPadding else ButtonDefaults.ContentPadding,
                ) {
                    Text(text = actionLabel)
                    if (actionOpensExternally) {
                        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                        Icon(
                            imageVector = FieldTapIcons.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One cell and its signal, for the Live screen's neighbours and NSA NR leg: bars, the cell's identity
 * ([title], for example "PCI 212 · EARFCN 1300") and context ([supportingText], "LTE · band 3"), then
 * the value with its unit and the level word, so colour is never alone. TalkBack reads it as one item.
 *
 * @param valueText the measured value ("-101"), or null when unknown, shown as [placeholder].
 * @param qualityLabel the level word for [quality], see [SignalQualityLabels].
 */
@Composable
fun CellSignalRow(
    title: String,
    valueText: String?,
    unit: String,
    quality: SignalQuality?,
    qualityLabel: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    placeholder: String = "—",
    contentDescription: String? = null,
) {
    val level = FieldTapDesign.signal.of(quality)
    val description = contentDescription ?: listOfNotNull(
        title,
        supportingText,
        if (valueText != null) "$valueText $unit" else placeholder,
        qualityLabel,
    ).joinToString(", ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.SettingsRowMinHeight)
            .clearAndSetSemantics { this.contentDescription = description }
            .padding(vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        SignalBars(quality = quality)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Xxs)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.tabular(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.Xxs)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = valueText ?: placeholder,
                    style = FieldTapDesign.numeric.body,
                    color = if (valueText == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .alignByBaseline()
                        .padding(start = Spacing.Xs),
                )
            }
            Text(text = qualityLabel, style = MaterialTheme.typography.labelMedium, color = level.content, maxLines = 1)
        }
    }
}

@Composable
private fun RowLeadingIcon(icon: ImageVector, tint: Color) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sizes.Icon))
}

@Composable
private fun RowTexts(
    title: String,
    supportingText: String?,
    titleColor: Color,
    supportingColor: Color,
    modifier: Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Xxs)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
        if (supportingText != null) {
            Text(text = supportingText, style = MaterialTheme.typography.bodyMedium, color = supportingColor)
        }
    }
}

private fun Color.dimmedUnless(enabled: Boolean): Color = if (enabled) this else copy(alpha = DISABLED_ALPHA)

@FieldTapPreviews
@Composable
private fun RowsPreview() {
    PreviewSurface {
        SectionCard(title = "Measurement") {
            ToggleRow(
                title = "Instant cell updates",
                supportingText = "Lets Android push cell changes as they happen. Needs the Phone permission.",
                checked = true,
                onCheckedChange = {},
                icon = FieldTapIcons.Phone,
            )
            ToggleRow(title = "Run ping and download tests", checked = false, onCheckedChange = {}, icon = FieldTapIcons.Transfer)
            NavigationRow(title = "Privacy zones", supportingText = "Logging pauses inside them", valueText = "2", icon = FieldTapIcons.Shield, onClick = {})
            NavigationRow(title = "Battery settings", icon = FieldTapIcons.Battery, opensExternally = true, onClick = {})
        }
        SectionCard(title = "Readiness") {
            ChecklistRow(title = "Precise location", tone = StatusTone.SUCCESS, statusText = "OK", icon = FieldTapIcons.Location)
            ChecklistRow(
                title = "Battery optimisation is on",
                tone = StatusTone.WARNING,
                statusText = "Recommended",
                detail = "Android may stop the session when the screen is off.",
                actionLabel = "Battery settings",
                onAction = {},
            )
        }
        SectionCard(title = "Neighbours") {
            CellSignalRow(title = "PCI 212 · EARFCN 1300", supportingText = "LTE · band 3", valueText = "-97", unit = "dBm", quality = SignalQuality.FAIR, qualityLabel = "Fair")
            CellSignalRow(title = "PCI 88 · EARFCN 6300", supportingText = "LTE · band 20", valueText = null, unit = "dBm", quality = null, qualityLabel = "No value")
        }
    }
}
