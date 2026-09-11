package com.fieldtap.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.SignalMetric
import com.fieldtap.ui.theme.SignalQuality
import com.fieldtap.ui.theme.SignalScale
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing

/**
 * The level words from the screen's string resources, so every chip, tile and row on a screen names a
 * level the same way: build one with `stringResource` and pass `labels.of(quality)`.
 */
@Immutable
data class SignalQualityLabels(
    val excellent: String,
    val good: String,
    val fair: String,
    val poor: String,
    /** For a missing value, for example "No value". */
    val unknown: String,
) {
    /** The word for [quality]; [unknown] for null. */
    fun of(quality: SignalQuality?): String = when (quality) {
        SignalQuality.EXCELLENT -> excellent
        SignalQuality.GOOD -> good
        SignalQuality.FAIR -> fair
        SignalQuality.POOR -> poor
        null -> unknown
    }
}

/**
 * A quality level as a swatch plus its word ("Good"). Use it wherever a signal colour appears, so
 * colour is never the only cue. [quality] null shows the neutral swatch (for "No value").
 *
 * @param label the level's word, see [SignalQualityLabels]; a list row may add the value ("Good -92").
 */
@Composable
fun SignalQualityChip(
    quality: SignalQuality?,
    label: String,
    modifier: Modifier = Modifier,
) {
    val level = FieldTapDesign.signal.of(quality)
    Surface(
        modifier = modifier,
        shape = ShapeRoles.Pill,
        color = FieldTapDesign.colors.neutral.container,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = Sizes.BadgeMinHeight)
                .padding(start = Spacing.Sm, end = Spacing.Sm + Spacing.Xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs + Spacing.Xxs),
        ) {
            Box(
                modifier = Modifier
                    .size(Sizes.Swatch)
                    .background(level.fill, CircleShape)
                    .border(1.5.dp, level.edge, CircleShape),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = level.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A value's place on the signal scale, as a track of the scale's four zones over `SignalScale.barRange` (RSRP -130..-50
 * dBm: POOR, FAIR, GOOD, EXCELLENT), each in its level's colour with a small gap between them. The zone the value is in
 * is drawn solid and the others pale, and a marker stands at the value. From [Sizes.SignalBarLabelsMinWidth] wide the
 * thresholds are named under the gaps ("-105", "-95", "-85"). A value beyond the range sits at the track's end. Pair it
 * with the number and a [SignalQualityChip]; the bar alone is not the reading.
 *
 * TalkBack gets a range value and [stateDescription] (for example "Good").
 *
 * @param showThresholds false leaves the threshold labels out at any width.
 */
@Composable
fun SignalBar(
    metric: SignalMetric,
    value: Int?,
    modifier: Modifier = Modifier,
    stateDescription: String? = null,
    showThresholds: Boolean = true,
) {
    val range = SignalScale.barRange(metric)
    val zones = remember(metric) { SignalScale.zones(metric, range) }
    val quality = SignalScale.quality(metric, value)
    val signal = FieldTapDesign.signal
    val level = signal.of(quality)
    val halo = MaterialTheme.colorScheme.surfaceContainerLow
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = FieldTapDesign.numeric.axis
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val thresholds = remember(metric) { SignalScale.thresholds(metric).boundaries.sorted() }
    val labelLayouts = remember(thresholds, labelStyle, density) { thresholds.map { measurer.measure(it.toString(), labelStyle) } }
    val labelHeightPx = labelLayouts.maxOfOrNull { it.size.height } ?: 0

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                // The labels take room only where they are drawn: decided from the width, before drawing.
                val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
                val labelled = showThresholds && width >= Sizes.SignalBarLabelsMinWidth.roundToPx()
                val height = Sizes.SignalMarkerHeight.roundToPx() + if (labelled) Spacing.Xxs.roundToPx() + labelHeightPx else 0
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .semantics {
                if (value != null) {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
                        range = range.first.toFloat()..range.last.toFloat(),
                    )
                }
                if (stateDescription != null) this.stateDescription = stateDescription
            },
    ) {
        val rtl = layoutDirection == LayoutDirection.Rtl
        fun xAt(v: Int): Float = SignalScale.fraction(v, range).let { f -> if (rtl) size.width * (1f - f) else size.width * f }
        val trackHeight = Sizes.SignalBarHeight.toPx()
        val markerHeight = Sizes.SignalMarkerHeight.toPx()
        val trackTop = (markerHeight - trackHeight) / 2f
        val halfGap = ZoneGap.toPx() / 2f
        val trackCorner = CornerRadius(trackHeight / 2f)

        zones.forEachIndexed { i, zone ->
            val a = xAt(zone.from)
            val b = xAt(zone.to)
            var start = minOf(a, b)
            var end = maxOf(a, b)
            // The gap goes on the edges shared with a neighbour; in right-to-left the lowest zone is on the right.
            val lowEdgeInner = i > 0
            val highEdgeInner = i < zones.lastIndex
            if (rtl) {
                if (lowEdgeInner) end -= halfGap
                if (highEdgeInner) start += halfGap
            } else {
                if (lowEdgeInner) start += halfGap
                if (highEdgeInner) end -= halfGap
            }
            val colors = signal.of(zone.quality)
            val active = value != null && zone.quality == quality
            val zoneSize = Size((end - start).coerceAtLeast(0f), trackHeight)
            drawRoundRect(
                color = if (active) colors.fill else colors.fill.copy(alpha = ZONE_ALPHA),
                topLeft = Offset(start, trackTop),
                size = zoneSize,
                cornerRadius = trackCorner,
            )
            if (active) {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = colors.edge,
                    topLeft = Offset(start + stroke / 2f, trackTop + stroke / 2f),
                    size = Size((zoneSize.width - stroke).coerceAtLeast(0f), trackHeight - stroke),
                    cornerRadius = CornerRadius((trackHeight - stroke) / 2f),
                    style = Stroke(stroke),
                )
            }
        }

        if (value != null) {
            val markerWidth = Sizes.SignalMarkerWidth.toPx()
            val haloWidth = markerWidth + 2 * MarkerHalo.toPx()
            val center = xAt(value).coerceIn(haloWidth / 2f, (size.width - haloWidth / 2f).coerceAtLeast(haloWidth / 2f))
            drawRoundRect(
                color = halo,
                topLeft = Offset(center - haloWidth / 2f, 0f),
                size = Size(haloWidth, markerHeight),
                cornerRadius = CornerRadius(haloWidth / 2f),
            )
            drawRoundRect(
                color = level.edge,
                topLeft = Offset(center - markerWidth / 2f, 0f),
                size = Size(markerWidth, markerHeight),
                cornerRadius = CornerRadius(markerWidth / 2f),
            )
        }

        if (showThresholds && size.width >= Sizes.SignalBarLabelsMinWidth.toPx()) {
            val labelTop = markerHeight + Spacing.Xxs.toPx()
            thresholds.forEachIndexed { i, threshold ->
                val layout = labelLayouts[i]
                val left = (xAt(threshold) - layout.size.width / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
                drawText(textLayoutResult = layout, color = labelColor, topLeft = Offset(left, labelTop))
            }
        }
    }
}

/** The gap between two zones of a [SignalBar]. */
private val ZoneGap: Dp = Spacing.Xxs

/** The surface-coloured edge that keeps a [SignalBar]'s marker apart from the zone under it. */
private val MarkerHalo: Dp = 1.5.dp

/** A zone the value is not in: present, never louder than the one it is in. */
private const val ZONE_ALPHA: Float = 0.25f

/**
 * Four rising bars, filled to the quality level (Excellent 4 ... Poor 1, unknown 0), for compact rows
 * such as [CellSignalRow]. Decorative unless [contentDescription] is given; the row's text carries the
 * value and level.
 */
@Composable
fun SignalBars(
    quality: SignalQuality?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val level = FieldTapDesign.signal.of(quality)
    val empty = MaterialTheme.colorScheme.outlineVariant
    val filled = quality?.bars ?: 0
    val semantics = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier.clearAndSetSemantics {}
    }
    Canvas(modifier = modifier.size(width = 20.dp, height = 16.dp).then(semantics)) {
        val gap = 1.5.dp.toPx()
        val barWidth = (size.width - gap * 3) / 4f
        val rtl = layoutDirection == LayoutDirection.Rtl
        for (i in 0 until 4) {
            val height = size.height * (i + 1) / 4f
            val index = if (rtl) 3 - i else i
            val left = index * (barWidth + gap)
            val topLeft = Offset(left, size.height - height)
            val barSize = Size(barWidth, height)
            val corner = CornerRadius(1.dp.toPx())
            if (i < filled) {
                drawRoundRect(color = level.fill, topLeft = topLeft, size = barSize, cornerRadius = corner)
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = level.edge,
                    topLeft = Offset(left + stroke / 2f, size.height - height + stroke / 2f),
                    size = Size(barWidth - stroke, height - stroke),
                    cornerRadius = corner,
                    style = Stroke(stroke),
                )
            } else {
                drawRoundRect(color = empty, topLeft = topLeft, size = barSize, cornerRadius = corner)
            }
        }
    }
}

@FieldTapPreviews
@Composable
private fun SignalIndicatorsPreview() {
    val labels = SignalQualityLabels("Excellent", "Good", "Fair", "Poor", "No value")
    PreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm), verticalAlignment = Alignment.CenterVertically) {
            SignalQualityChip(SignalQuality.EXCELLENT, labels.of(SignalQuality.EXCELLENT))
            SignalQualityChip(SignalQuality.GOOD, labels.of(SignalQuality.GOOD))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm), verticalAlignment = Alignment.CenterVertically) {
            SignalQualityChip(SignalQuality.FAIR, labels.of(SignalQuality.FAIR))
            SignalQualityChip(SignalQuality.POOR, labels.of(SignalQuality.POOR))
            SignalQualityChip(null, labels.of(null))
        }
        SignalBar(SignalMetric.RSRP, -49, stateDescription = labels.excellent)
        SignalBar(SignalMetric.RSRP, -92, stateDescription = labels.good)
        SignalBar(SignalMetric.RSRP, -101, stateDescription = labels.fair)
        SignalBar(SignalMetric.RSRP, -117, stateDescription = labels.poor)
        SignalBar(SignalMetric.SINR, null)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Md), verticalAlignment = Alignment.CenterVertically) {
            SignalBars(SignalQuality.EXCELLENT)
            SignalBars(SignalQuality.GOOD)
            SignalBars(SignalQuality.FAIR)
            SignalBars(SignalQuality.POOR)
            SignalBars(null)
        }
    }
}
