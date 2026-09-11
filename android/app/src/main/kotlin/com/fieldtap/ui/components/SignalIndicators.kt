package com.fieldtap.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
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
 * @param label the level's word, see [SignalQualityLabels].
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
 * A value on the signal scale as a horizontal bar: the filled length is the value's position in the
 * metric's display range (RSRP -140..-40 dBm, RSRQ -30..0 dB, SINR -25..40 dB), in the level's colour,
 * with ticks under the bar at the scale's three thresholds. Pair it with the number and a
 * [SignalQualityChip]; the bar alone is not the reading.
 *
 * TalkBack gets a range value and [stateDescription] (for example "Good").
 */
@Composable
fun SignalBar(
    metric: SignalMetric,
    value: Int?,
    modifier: Modifier = Modifier,
    stateDescription: String? = null,
    showThresholds: Boolean = true,
) {
    val quality = SignalScale.quality(metric, value)
    val level = FieldTapDesign.signal.of(quality)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val trackEdge = MaterialTheme.colorScheme.outlineVariant
    val tick = MaterialTheme.colorScheme.outline
    val range = SignalScale.displayRange(metric)
    val thresholds = SignalScale.thresholds(metric).boundaries
    val fraction = value?.let { SignalScale.fraction(it, range) }
    val barHeight = Sizes.SignalBarHeight
    val tickGap = Spacing.Xxs
    val tickLength = if (showThresholds) Spacing.Xs else 0.dp

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight + if (showThresholds) tickGap + tickLength else 0.dp)
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
        val h = barHeight.toPx()
        val radius = CornerRadius(h / 2f)
        fun xAt(f: Float): Float = if (rtl) size.width * (1f - f) else size.width * f

        drawRoundRect(color = track, size = Size(size.width, h), cornerRadius = radius)
        drawRoundRect(color = trackEdge, size = Size(size.width, h), cornerRadius = radius, style = Stroke(1.dp.toPx()))

        if (fraction != null) {
            val width = (size.width * fraction).coerceAtLeast(h)
            val left = if (rtl) size.width - width else 0f
            drawRoundRect(color = level.fill, topLeft = Offset(left, 0f), size = Size(width, h), cornerRadius = radius)
            val stroke = 1.5.dp.toPx()
            drawRoundRect(
                color = level.edge,
                topLeft = Offset(left + stroke / 2f, stroke / 2f),
                size = Size(width - stroke, h - stroke),
                cornerRadius = CornerRadius((h - stroke) / 2f),
                style = Stroke(stroke),
            )
        }

        if (showThresholds) {
            val top = h + tickGap.toPx()
            val strokeWidth = 1.dp.toPx()
            for (t in thresholds) {
                val x = xAt(SignalScale.fraction(t, range))
                drawLine(
                    color = tick,
                    start = Offset(x, top),
                    end = Offset(x, top + tickLength.toPx()),
                    strokeWidth = strokeWidth,
                )
            }
        }
    }
}

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
        SignalBar(SignalMetric.RSRP, -78, stateDescription = labels.excellent)
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
