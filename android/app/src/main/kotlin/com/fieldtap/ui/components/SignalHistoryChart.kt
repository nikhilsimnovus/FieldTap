package com.fieldtap.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fieldtap.core.live.ChartPoint
import com.fieldtap.core.live.LiveStateReducer
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.SignalMetric
import com.fieldtap.ui.theme.SignalScale
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing

/** The words of a [SignalHistoryChart], all from string resources. */
@Immutable
data class SignalChartLabels(
    /** "RSRP". */
    val rsrpTitle: String,
    /** "dBm". */
    val rsrpUnit: String,
    /** "SINR". */
    val sinrTitle: String,
    /** "dB". */
    val sinrUnit: String,
    /** Under the left end of the time axis: "5 min ago". */
    val windowStart: String,
    /** Under the right end: "Now". */
    val windowEnd: String,
    /** Inside an empty panel: "No fresh samples yet". */
    val noData: String,
    /** In place of a panel whose values the serving cell does not report: "Not reported by this cell". */
    val notReported: String = noData,
)

/**
 * Five minutes of serving RSRP and SINR, as two panels on one time axis (RSRP -140..-40 dBm, SINR
 * -25..40 dB, the report's axes). Dashed reference lines sit at the signal scale's thresholds, with
 * the report's -105 dBm line (and 0 dB for SINR) emphasised. Lines break at sampling gaps.
 *
 * TalkBack reads [summary] as the whole chart, for example "RSRP over the last 5 minutes: latest -92
 * dBm, lowest -104, highest -85. SINR: latest 12 dB, lowest 3, highest 18." Build it from
 * [ChartMath.stats].
 *
 * @param gapThresholdMs pass `ChartMath.gapThresholdMs(live.shortInterval)`.
 */
@Composable
fun SignalHistoryChart(
    rsrp: List<ChartPoint>,
    sinr: List<ChartPoint>,
    nowElapsedMs: Long,
    labels: SignalChartLabels,
    summary: String,
    modifier: Modifier = Modifier,
    windowMs: Long = LiveStateReducer.WINDOW_MS,
    gapThresholdMs: Long = ChartMath.DEFAULT_GAP_THRESHOLD_MS,
    sinrReported: Boolean = true,
) {
    val colors = FieldTapDesign.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = summary },
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        ChartPanelHeader(labels.rsrpTitle, labels.rsrpUnit, ChartMath.stats(rsrp, nowElapsedMs, windowMs), colors.chartRsrp)
        TimeSeriesChart(
            points = rsrp,
            nowElapsedMs = nowElapsedMs,
            range = SignalScale.RSRP_DISPLAY_RANGE,
            lineColor = colors.chartRsrp,
            referenceLines = SignalScale.RSRP_THRESHOLDS.boundaries,
            keyReference = SignalScale.keyReference(SignalMetric.RSRP),
            windowMs = windowMs,
            gapThresholdMs = gapThresholdMs,
            noDataText = labels.noData,
        )
        Spacer(modifier = Modifier.height(Spacing.Xs))
        if (sinrReported) {
            ChartPanelHeader(labels.sinrTitle, labels.sinrUnit, ChartMath.stats(sinr, nowElapsedMs, windowMs), colors.chartSinr)
            TimeSeriesChart(
                points = sinr,
                nowElapsedMs = nowElapsedMs,
                range = SignalScale.SINR_DISPLAY_RANGE,
                lineColor = colors.chartSinr,
                referenceLines = SignalScale.SINR_THRESHOLDS.boundaries,
                keyReference = SignalScale.keyReference(SignalMetric.SINR),
                windowMs = windowMs,
                gapThresholdMs = gapThresholdMs,
                noDataText = labels.noData,
            )
        } else {
            // An empty panel would say "not yet" for a value this cell never reports: one line says so instead.
            ChartPanelHeader(labels.sinrTitle, labels.sinrUnit, stats = null, lineColor = colors.chartSinr, trailing = labels.notReported)
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(labels.windowStart, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.weight(1f))
                Text(labels.windowEnd, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChartPanelHeader(title: String, unit: String, stats: SeriesStats?, lineColor: Color, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        Box(
            modifier = Modifier
                .size(width = 14.dp, height = 3.dp)
                .background(lineColor, ShapeRoles.Bar),
        )
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.weight(1f))
        if (stats != null) {
            Text(
                text = "${stats.latest} $unit",
                style = FieldTapDesign.numeric.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else if (trailing != null) {
            Text(text = trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One chart panel: a line of [points] against time over [windowMs] ending at [nowElapsedMs], on a
 * vertical [range], with dashed [referenceLines] labelled by their values and [keyReference] drawn
 * stronger. Single points between gaps are dots; the newest point has a marker. Draws in LTR in every
 * locale, like the report. Decorative for TalkBack: the caller describes it (see [SignalHistoryChart]).
 */
@Composable
fun TimeSeriesChart(
    points: List<ChartPoint>,
    nowElapsedMs: Long,
    range: IntRange,
    lineColor: Color,
    modifier: Modifier = Modifier,
    referenceLines: List<Int> = emptyList(),
    keyReference: Int? = null,
    windowMs: Long = LiveStateReducer.WINDOW_MS,
    gapThresholdMs: Long = ChartMath.DEFAULT_GAP_THRESHOLD_MS,
    height: Dp = Sizes.ChartPanelHeight,
    noDataText: String? = null,
) {
    val colors = FieldTapDesign.colors
    val panel = MaterialTheme.colorScheme.surfaceContainerLowest
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisStyle = FieldTapDesign.numeric.axis
    val noDataStyle = MaterialTheme.typography.bodySmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val lines = referenceLines.filter { it in range }
    val labelLayouts = remember(lines, axisStyle, density) { lines.map { measurer.measure(it.toString(), axisStyle) } }
    val noDataLayout = remember(noDataText, noDataStyle, density) { noDataText?.let { measurer.measure(it, noDataStyle) } }
    val segments = remember(points, nowElapsedMs, windowMs, gapThresholdMs) {
        ChartMath.segments(points, nowElapsedMs, windowMs, gapThresholdMs)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        drawRoundRect(color = panel, cornerRadius = CornerRadius(PanelCornerRadius.toPx()))
        val labelWidth = labelLayouts.maxOfOrNull { it.size.width }?.toFloat() ?: 0f
        val left = Spacing.Sm.toPx() + labelWidth + if (labelWidth > 0f) Spacing.Xs.toPx() else 0f
        val right = size.width - Spacing.Sm.toPx()
        val top = Spacing.Sm.toPx()
        val bottom = size.height - Spacing.Sm.toPx()
        val plotWidth = (right - left).coerceAtLeast(1f)
        val plotHeight = (bottom - top).coerceAtLeast(1f)
        fun x(elapsedMs: Long) = left + ChartMath.xFraction(elapsedMs, nowElapsedMs, windowMs) * plotWidth
        fun y(value: Int) = bottom - ChartMath.yFraction(value, range) * plotHeight

        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        lines.forEachIndexed { i, value ->
            val key = value == keyReference
            val yy = y(value)
            drawLine(
                color = if (key) colors.chartReference else colors.chartGrid,
                start = Offset(left, yy),
                end = Offset(right, yy),
                strokeWidth = if (key) 1.5.dp.toPx() else 1.dp.toPx(),
                pathEffect = dash,
            )
            val layout = labelLayouts[i]
            drawText(
                textLayoutResult = layout,
                color = labelColor,
                topLeft = Offset(Spacing.Sm.toPx() + labelWidth - layout.size.width, yy - layout.size.height / 2f),
            )
        }

        if (segments.isEmpty()) {
            if (noDataLayout != null) {
                drawText(
                    textLayoutResult = noDataLayout,
                    color = labelColor,
                    topLeft = Offset(
                        left + (plotWidth - noDataLayout.size.width) / 2f,
                        top + (plotHeight - noDataLayout.size.height) / 2f,
                    ),
                )
            }
            return@Canvas
        }

        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (segment in segments) {
            if (segment.size == 1) {
                drawCircle(color = lineColor, radius = 2.5.dp.toPx(), center = Offset(x(segment[0].elapsedMs), y(segment[0].value)))
                continue
            }
            val path = Path()
            segment.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(x(point.elapsedMs), y(point.value)) else path.lineTo(x(point.elapsedMs), y(point.value))
            }
            drawPath(path = path, color = lineColor, style = stroke)
        }
        val newest = segments.last().last()
        val center = Offset(x(newest.elapsedMs), y(newest.value))
        drawCircle(color = panel, radius = 5.5.dp.toPx(), center = center)
        drawCircle(color = lineColor, radius = 3.5.dp.toPx(), center = center)
    }
}

/** The chart panel's corner, matching [ShapeRoles.Tile]. */
private val PanelCornerRadius: Dp = 14.dp

@FieldTapPreviews
@Composable
private fun SignalHistoryChartPreview() {
    val now = 300_000L
    val rsrp = buildList {
        for (i in 0..60) add(ChartPoint(elapsedMs = i * 2_000L, value = -95 + ((i * 7) % 17) - 8))
        for (i in 90..150) add(ChartPoint(elapsedMs = i * 2_000L, value = -88 + ((i * 5) % 13) - 6))
    }
    val sinr = rsrp.map { ChartPoint(it.elapsedMs, (it.value + 110) / 2) }
    PreviewSurface {
        SectionCard(title = "Last 5 minutes") {
            SignalHistoryChart(
                rsrp = rsrp,
                sinr = sinr,
                nowElapsedMs = now,
                labels = SignalChartLabels("RSRP", "dBm", "SINR", "dB", "5 min ago", "Now", "No fresh samples yet"),
                summary = "RSRP over the last 5 minutes: latest -88 dBm.",
                gapThresholdMs = ChartMath.gapThresholdMs(shortInterval = true),
            )
        }
        SectionCard(title = "Empty") {
            TimeSeriesChart(
                points = emptyList(),
                nowElapsedMs = now,
                range = SignalScale.RSRP_DISPLAY_RANGE,
                lineColor = FieldTapDesign.colors.chartRsrp,
                referenceLines = SignalScale.RSRP_THRESHOLDS.boundaries,
                keyReference = -105,
                noDataText = "No fresh samples yet",
            )
        }
    }
}
