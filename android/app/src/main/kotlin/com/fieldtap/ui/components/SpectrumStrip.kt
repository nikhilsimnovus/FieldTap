package com.fieldtap.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.SignalQuality
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing

/**
 * The session-at-a-glance "route verdict": one proportional stacked bar of the four route colours, best
 * first, showing the share of a walk's samples that fell in each quality band. Segments are edge-stroked
 * so the pale green and orange read on a light card, and the bar clips to [ShapeRoles.Bar].
 *
 * The four fractions are shares of the whole, each in 0..1; they may sum to less than 1 (the remainder,
 * unclassified or gap samples, shows as the empty track). Colour is never the only cue — pass a
 * [contentDescription] built from the screen's strings ("Excellent 42%, good 31%, fair 18%, poor 9%") and
 * place a legend of [SignalQualityChip]s beneath the strip.
 */
@Composable
fun SpectrumStrip(
    excellent: Float,
    good: Float,
    fair: Float,
    poor: Float,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val signal = FieldTapDesign.signal
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val order = listOf(
        SignalQuality.EXCELLENT to excellent,
        SignalQuality.GOOD to good,
        SignalQuality.FAIR to fair,
        SignalQuality.POOR to poor,
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(Sizes.SpectrumStripHeight)
            .clip(ShapeRoles.Bar)
            .clearAndSetSemantics { this.contentDescription = contentDescription },
    ) {
        drawRect(color = track, size = size)
        val rtl = layoutDirection == LayoutDirection.Rtl
        val stroke = 1.dp.toPx()
        var offset = 0f
        for ((quality, share) in order) {
            val width = (size.width * share.coerceIn(0f, 1f))
            if (width <= 0f) continue
            val start = if (rtl) size.width - offset - width else offset
            val colors = signal.of(quality)
            drawRect(color = colors.fill, topLeft = Offset(start, 0f), size = Size(width, size.height))
            drawRect(
                color = colors.edge,
                topLeft = Offset(start + stroke / 2f, stroke / 2f),
                size = Size((width - stroke).coerceAtLeast(0f), size.height - stroke),
                style = Stroke(stroke),
            )
            offset += width
        }
    }
}

@FieldTapPreviews
@Composable
private fun SpectrumStripPreview() {
    val labels = SignalQualityLabels("Excellent", "Good", "Fair", "Poor", "No value")
    PreviewSurface {
        SpectrumStrip(
            excellent = 0.42f,
            good = 0.31f,
            fair = 0.18f,
            poor = 0.09f,
            contentDescription = "Excellent 42%, good 31%, fair 18%, poor 9%",
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            SignalQualityChip(SignalQuality.EXCELLENT, "${labels.excellent} 42%")
            SignalQualityChip(SignalQuality.GOOD, "${labels.good} 31%")
            SignalQualityChip(SignalQuality.FAIR, "${labels.fair} 18%")
            SignalQualityChip(SignalQuality.POOR, "${labels.poor} 9%")
        }
    }
}
