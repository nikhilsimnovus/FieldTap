package com.fieldtap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fieldtap.core.live.AgeBadge
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.SignalMetric
import com.fieldtap.ui.theme.SignalQuality
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import kotlin.math.roundToInt

/** How loud a [MetricTile]'s value is. */
enum class MetricEmphasis {
    /** The one number a screen is about (serving RSRP on Live). Give it the full width. */
    HERO,

    /** Most tiles. */
    STANDARD,

    /** Dense grids and secondary values. */
    COMPACT,
}

/**
 * One live value: label, number, unit, and optionally its signal quality and age.
 *
 * - The number uses tabular figures and shrinks to fit rather than clipping (font scale 1.3, narrow
 *   tiles, landscape).
 * - [value] null shows [placeholder] (an em dash) in the secondary colour: unknown is never shown as 0.
 * - [badge] STALE greys the value and turns the age red, so a stale number is never mistaken for a
 *   live one.
 * - TalkBack reads one phrase: [contentDescription], or label, value and unit, quality, age and
 *   supporting text.
 *
 * @param label for example "RSRP"; [unit] "dBm"; [qualityLabel] "Good" (see [SignalQualityLabels]);
 *   [ageText] "2.1 s old". All from string resources.
 * @param footer optional content under the value, for example a [SignalBar] on the hero tile. TalkBack
 *   does not read it separately, so the tile's words must already say what it shows.
 */
@Composable
fun MetricTile(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    unit: String? = null,
    emphasis: MetricEmphasis = MetricEmphasis.STANDARD,
    quality: SignalQuality? = null,
    qualityLabel: String? = null,
    ageText: String? = null,
    badge: AgeBadge = AgeBadge.NONE,
    supportingText: String? = null,
    placeholder: String = "—",
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val numeric = FieldTapDesign.numeric
    val baseStyle = when (emphasis) {
        MetricEmphasis.HERO -> numeric.hero
        MetricEmphasis.STANDARD -> numeric.large
        MetricEmphasis.COMPACT -> numeric.medium
    }
    val valueStyle = baseStyle.copy(lineHeight = 1.2.em)
    val dimmed = value == null || badge == AgeBadge.STALE
    val valueColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val description = contentDescription ?: listOfNotNull(
        label,
        if (value != null && unit != null) "$value $unit" else value ?: placeholder,
        qualityLabel,
        ageText,
        supportingText,
    ).joinToString(", ")
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
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(Spacing.CardPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (qualityLabel != null) {
                    SignalQualityChip(quality = quality, label = qualityLabel)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value ?: placeholder,
                    style = valueStyle,
                    color = valueColor,
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = MIN_VALUE_FONT_SIZE_SP.sp, maxFontSize = valueStyle.fontSize, stepSize = 1.sp),
                    modifier = Modifier
                        .alignByBaseline()
                        .weight(1f, fill = false),
                )
                if (unit != null) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .alignByBaseline()
                            .padding(start = Spacing.Xs),
                    )
                }
            }
            footer?.invoke()
            if (ageText != null || supportingText != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (ageText != null) {
                        AgeIndicator(text = ageText, badge = badge)
                    }
                    if (supportingText != null) {
                        Text(
                            text = supportingText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    val container = MaterialTheme.colorScheme.surfaceContainerLow
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.then(semantics), shape = ShapeRoles.Tile, color = container, content = body)
    } else {
        Surface(modifier = modifier.then(semantics), shape = ShapeRoles.Tile, color = container, content = body)
    }
}

/**
 * A secondary live value under a [MetricEmphasis.HERO] tile, sharing a row with another: label, number and unit, and its
 * quality as a swatch and a word. A missing value shows [placeholder] with no unit, and [qualityLabel] says why, for
 * example "Not reported". [stale] dims the number as a stale hero does. TalkBack reads one sentence: the label, the value
 * with its unit or the placeholder, then the quality.
 */
@Composable
fun SecondaryMetricTile(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    unit: String? = null,
    quality: SignalQuality? = null,
    qualityLabel: String? = null,
    stale: Boolean = false,
    placeholder: String = "—",
) {
    val valueStyle = FieldTapDesign.numeric.medium.copy(lineHeight = 1.2.em)
    val description = listOfNotNull(
        label,
        if (value != null && unit != null) "$value $unit" else value ?: placeholder,
        qualityLabel,
    ).joinToString(", ")
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        shape = ShapeRoles.Tile,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.Xxs),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value ?: placeholder,
                    style = valueStyle,
                    color = if (value == null || stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = MIN_VALUE_FONT_SIZE_SP.sp, maxFontSize = valueStyle.fontSize, stepSize = 1.sp),
                    modifier = Modifier
                        .alignByBaseline()
                        .weight(1f, fill = false),
                )
                if (value != null && unit != null) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .alignByBaseline()
                            .padding(start = Spacing.Xs),
                    )
                }
            }
            if (qualityLabel != null) {
                SignalQualityChip(quality = if (value == null) null else quality, label = qualityLabel)
            }
        }
    }
}

/** The smallest size a tile's value shrinks to before it would clip. */
private const val MIN_VALUE_FONT_SIZE_SP: Int = 14

/**
 * Lays tiles out in equal-width columns that adapt to the width and the font scale: as many columns
 * as fit cells of [minCellWidth] (times the font scale when above 1), at most [maxColumns]. Tiles in a
 * row share the tallest tile's height. Children are placed in order, start to end.
 */
@Composable
fun MetricGrid(
    modifier: Modifier = Modifier,
    minCellWidth: Dp = Sizes.TileMinWidth,
    maxColumns: Int = 3,
    spacing: Dp = Spacing.Md,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        if (measurables.isEmpty()) {
            return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        }
        val gap = spacing.roundToPx()
        val minCell = (minCellWidth.toPx() * fontScale.coerceAtLeast(1f)).roundToInt()
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            val n = minOf(maxColumns, measurables.size).coerceAtLeast(1)
            minCell * n + gap * (n - 1)
        }
        val columns = GridMath.columns(width, minCell, gap, maxColumns, measurables.size)
        val cellWidth = GridMath.cellWidth(width, columns, gap)
        val rows = measurables.chunked(columns).map { row ->
            val height = row.maxOf { it.maxIntrinsicHeight(cellWidth) }
            row.map { it.measure(Constraints.fixed(cellWidth, height)) }
        }
        val totalHeight = rows.sumOf { it.first().height } + gap * (rows.size - 1)
        layout(width, totalHeight.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            for (row in rows) {
                var x = 0
                for (placeable in row) {
                    placeable.placeRelative(x, y)
                    x += cellWidth + gap
                }
                y += row.first().height + gap
            }
        }
    }
}

/** Column arithmetic for [MetricGrid], in pixels. */
object GridMath {
    /** How many columns of at least [minCellPx] fit in [availablePx], between 1 and [maxColumns] and [itemCount]. */
    fun columns(availablePx: Int, minCellPx: Int, gapPx: Int, maxColumns: Int, itemCount: Int): Int {
        val fit = if (minCellPx + gapPx <= 0) maxColumns else (availablePx + gapPx) / (minCellPx + gapPx)
        return fit.coerceAtMost(maxColumns).coerceAtMost(itemCount.coerceAtLeast(1)).coerceAtLeast(1)
    }

    /** Width of one cell when [columns] share [availablePx] with [gapPx] between them; never negative. */
    fun cellWidth(availablePx: Int, columns: Int, gapPx: Int): Int =
        ((availablePx - gapPx * (columns - 1)) / columns.coerceAtLeast(1)).coerceAtLeast(0)
}

@FieldTapPreviews
@Composable
private fun MetricTilePreview() {
    PreviewSurface {
        MetricTile(
            label = "RSRP",
            value = "-92",
            unit = "dBm",
            emphasis = MetricEmphasis.HERO,
            quality = SignalQuality.GOOD,
            qualityLabel = "Good",
            ageText = "2.1 s old",
            badge = AgeBadge.FRESH,
            supportingText = "NR · PCI 555 · n41",
            modifier = Modifier.fillMaxWidth(),
        ) {
            SignalBar(metric = SignalMetric.RSRP, value = -92)
        }
        MetricGrid {
            MetricTile(label = "RSRQ", value = "-11", unit = "dB", quality = SignalQuality.GOOD, qualityLabel = "Good")
            MetricTile(label = "SINR", value = "4", unit = "dB", quality = SignalQuality.FAIR, qualityLabel = "Fair", ageText = "7.9 s old", badge = AgeBadge.AGING)
            MetricTile(label = "SINR", value = "18", unit = "dB", ageText = "41 s old", badge = AgeBadge.STALE, emphasis = MetricEmphasis.COMPACT)
            MetricTile(label = "Band", value = null, emphasis = MetricEmphasis.COMPACT)
        }
    }
}
