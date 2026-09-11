package com.fieldtap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing

/**
 * A titled group of related content on a tonal surface: "Serving cell", "Collection", "Files",
 * "Privacy zones". Screens are a vertical stack of these, [Spacing.SectionGap] apart.
 *
 * The title is a TalkBack heading, so users can jump between sections. Content is a column with
 * [itemGap] between items: [Spacing.ItemGap], or [Spacing.Sm] for a dense card of [KeyValueRow]s given
 * `minHeight = Sizes.KeyValueRowDenseMinHeight` (Overview, Collection, Files, Serving cell).
 *
 * @param trailing an optional action at the end of the header (an icon button or a text button).
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Spacing.CardPadding),
    itemGap: Dp = Spacing.ItemGap,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ShapeRoles.Card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(itemGap),
        ) {
            if (title != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Sizes.Icon),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

/**
 * A labelled value inside a [SectionCard]: "PCI  555", "Fresh samples  54", "SHA-256  3f9a…".
 *
 * Side by side by default, value at the end in tabular figures. A long value takes all the width the key leaves and
 * wraps there; when key and value are both long, the key keeps 40% of the row ([KeyValueMath]). [stacked] puts the
 * value under the key, for long values such as a SHA-256 or a URL. [selectable] lets the user copy the
 * value. TalkBack reads "key, value" as one item, or [contentDescription].
 *
 * A value of several lines (a phone model, then its Android version) is broken by the caller with "\n", so no separator
 * is left at the end of a line.
 *
 * @param valueColor defaults to onSurface; pass a [com.fieldtap.ui.theme.StatusColors.color] or a
 *   signal level's `content` colour for a value that carries a state, and say the state in words too.
 * @param minHeight [Sizes.KeyValueRowMinHeight], or [Sizes.KeyValueRowDenseMinHeight] in a dense card.
 */
@Composable
fun KeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: ImageVector? = null,
    valueColor: Color = Color.Unspecified,
    tabular: Boolean = true,
    stacked: Boolean = false,
    selectable: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    contentDescription: String? = null,
    minHeight: Dp = Sizes.KeyValueRowMinHeight,
) {
    val valueStyle = if (tabular) FieldTapDesign.numeric.body else MaterialTheme.typography.bodyLarge
    val resolvedValueColor = valueColor.takeOrElse { MaterialTheme.colorScheme.onSurface }
    val semantics = if (contentDescription != null) {
        Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    val valueText: @Composable (Modifier, TextAlign) -> Unit = { textModifier, align ->
        val text: @Composable () -> Unit = {
            Text(text = value, style = valueStyle, color = resolvedValueColor, textAlign = align, modifier = textModifier)
        }
        if (selectable) SelectionContainer { text() } else text()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(semantics),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Sizes.IconSmall),
            )
        }
        if (stacked) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Xxs)) {
                Text(text = key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                valueText(Modifier, TextAlign.Start)
                if (supportingText != null) {
                    Text(text = supportingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            // Not two weighted children: those split the row in half, so "Google Pixel 8 · Android 16" wrapped beside "Phone".
            Layout(
                content = {
                    Column {
                        Text(text = key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (supportingText != null) {
                            Text(text = supportingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Box { valueText(Modifier, TextAlign.End) }
                },
                modifier = Modifier.weight(1f),
            ) { measurables, constraints ->
                val gapPx = Spacing.Md.roundToPx()
                val keyPx = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
                val valuePx = measurables[1].maxIntrinsicWidth(Constraints.Infinity)
                val width = if (constraints.hasBoundedWidth) {
                    constraints.maxWidth
                } else {
                    (keyPx + gapPx + valuePx).coerceAtLeast(constraints.minWidth)
                }
                val (keyWidth, valueWidth) = KeyValueMath.widths(width, gapPx, keyPx, valuePx)
                val keyPlaceable = measurables[0].measure(Constraints(maxWidth = keyWidth))
                val valuePlaceable = measurables[1].measure(Constraints(maxWidth = valueWidth))
                val height = maxOf(keyPlaceable.height, valuePlaceable.height).coerceIn(constraints.minHeight, constraints.maxHeight)
                layout(width, height) {
                    keyPlaceable.placeRelative(0, (height - keyPlaceable.height) / 2)
                    valuePlaceable.placeRelative(width - valuePlaceable.width, (height - valuePlaceable.height) / 2)
                }
            }
        }
        trailing?.invoke()
    }
}

/** Width arithmetic for a side-by-side [KeyValueRow], in pixels. */
object KeyValueMath {
    /** The share of the row a key keeps when key and value are both too long to sit side by side on one line. */
    const val KEY_SHARE: Float = 0.4f

    /**
     * The widths of the key and the value in a row [availablePx] wide with [gapPx] between them, from the widths
     * [keyPx] and [valuePx] they take on one line. The value always gets all the room the key leaves. The key keeps its
     * own width when both fit, or when that still leaves the value its one-line width; otherwise it gets the larger of
     * [KEY_SHARE] of the row and what the value leaves, and wraps. Never negative.
     */
    fun widths(availablePx: Int, gapPx: Int, keyPx: Int, valuePx: Int): Pair<Int, Int> {
        val room = (availablePx - gapPx).coerceAtLeast(0)
        val key = keyPx.coerceAtLeast(0)
        val value = valuePx.coerceAtLeast(0)
        val keyWidth = if (key + value <= room) key else minOf(key, maxOf((room * KEY_SHARE).toInt(), room - value))
        return keyWidth to room - keyWidth
    }
}

/** A hairline between rows of a [SectionCard] when the rows need separating. */
@Composable
fun SectionDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = MaterialTheme.colorScheme.outlineVariant)
}

@FieldTapPreviews
@Composable
private fun SectionCardPreview() {
    PreviewSurface {
        SectionCard(title = "Serving cell", subtitle = "NR SA · 311480", icon = FieldTapIcons.SignalBars) {
            KeyValueRow(key = "PCI", value = "555")
            KeyValueRow(key = "NR-ARFCN", value = "9000", supportingText = "Band n41")
            SectionDivider()
            KeyValueRow(key = "Service", value = "In service", tabular = false, valueColor = FieldTapDesign.colors.success.color)
            KeyValueRow(
                key = "SHA-256",
                value = "3f9a0c5e2b7d41a8c6e0f1b2d3a4c5e6f708192a3b4c5d6e7f8091a2b3c4d5e6",
                stacked = true,
                selectable = true,
            )
        }
    }
}
