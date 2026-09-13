package com.fieldtap.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldtap.core.live.AgeBadge
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.LocalReducedMotion
import com.fieldtap.ui.theme.Motion
import com.fieldtap.ui.theme.SignalMetric
import com.fieldtap.ui.theme.SignalQuality
import com.fieldtap.ui.theme.SignalScale
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing

/**
 * The Momentum signature: the serving value as a **donut/ring gauge**. A full-circle track
 * (`surfaceContainerHighest`) carries a rounded arc, in the quality's report colour ([SignalColors]
 * `fill` over `edge`), swept from twelve o'clock clockwise to the value's position in the metric's display
 * range. The measured value sits big and centred in tabular figures with its unit beneath, so the number is
 * always the true reading — the ring is context, never the source of the value.
 *
 * The arc **glides** to a new position ([Motion.spatial], instant under reduced motion); the number never
 * animates its value. [value] null (or [stale] with no reading) draws the track alone, muted, and the
 * centre shows [placeholder]; the caller names the level in words alongside (see [SignalDonutHero]).
 *
 * TalkBack reads [contentDescription] (build it from the label, value, unit and quality) and a range value;
 * pass a description so the ring is one clear phrase rather than a bare number.
 *
 * @param metric the value's scale; RSRP by default, so the arc spans −140..−40 dBm like the report.
 * @param diameter [Sizes.DonutHero] on Live, [Sizes.DonutCompact] for a denser summary.
 */
@Composable
fun SignalDonut(
    value: Int?,
    quality: SignalQuality?,
    unit: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    metric: SignalMetric = SignalMetric.RSRP,
    stale: Boolean = false,
    diameter: Dp = Sizes.DonutHero,
    placeholder: String = "—",
) {
    val level = FieldTapDesign.signal.of(quality)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val dimmed = value == null || stale
    val reduced = LocalReducedMotion.current
    val range = SignalScale.displayRange(metric)
    val targetFraction = value?.let { SignalScale.fraction(it, range) } ?: 0f
    val sweepFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = Motion.spatial(reduced),
        label = "donutSweep",
    )
    val valueColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .size(diameter)
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                if (value != null) {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
                        range = range.first.toFloat()..range.last.toFloat(),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            val strokePx = size.minDimension * RING_THICKNESS
            val cap = StrokeCap.Round
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(inset, inset)
            // Full track.
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx),
            )
            if (value != null) {
                val sweep = (sweepFraction * 360f).coerceIn(0f, 360f)
                // A round-capped arc of zero sweep still draws a dot; only draw once there is a real arc.
                if (sweep > 0.5f) {
                    drawArc(
                        color = if (dimmed) level.fill.copy(alpha = MUTED_ARC_ALPHA) else level.fill,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = cap),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value?.toString() ?: placeholder,
                style = FieldTapDesign.numeric.hero,
                color = valueColor,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = DONUT_MIN_VALUE_SP.sp,
                    maxFontSize = FieldTapDesign.numeric.hero.fontSize,
                    stepSize = 1.sp,
                ),
                modifier = Modifier.width(diameter * INNER_TEXT_FRACTION),
            )
            Text(
                text = unit,
                style = FieldTapDesign.numeric.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * The Momentum **hero card**: a white (dark: elevated) rounded card pairing the serving-RSRP [SignalDonut]
 * with, on the right, the metric's mini-label ([Eyebrow]), a soft [SignalQualityChip] quality pill and the
 * sample's age ([AgeIndicator]). It is the top of the Live screen — the one big reading a glance is about.
 *
 * The card carries no semantics of its own; each part reads itself (the donut names the value and level,
 * the age pill the freshness), so TalkBack hears "NR SS-RSRP, −68 dBm, Excellent" then "1.3 s old".
 *
 * @param label the metric's name, e.g. "NR SS-RSRP" (shown as an uppercase mini-label).
 * @param unit "dBm"; [qualityLabel] the level word ("Excellent", see [SignalQualityLabels]);
 *   [ageText] "Serving cell · 1.3 s old" or similar.
 */
@Composable
fun SignalDonutHero(
    label: String,
    value: Int?,
    quality: SignalQuality?,
    unit: String,
    qualityLabel: String,
    donutContentDescription: String,
    modifier: Modifier = Modifier,
    metric: SignalMetric = SignalMetric.RSRP,
    stale: Boolean = false,
    ageText: String? = null,
    ageBadge: AgeBadge = AgeBadge.NONE,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = com.fieldtap.ui.theme.ShapeRoles.Card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = cardShadowElevation(),
        border = cardHairline(),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.CardPadding),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalDonut(
                value = value,
                quality = quality,
                unit = unit,
                contentDescription = donutContentDescription,
                metric = metric,
                stale = stale,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                Eyebrow(text = label)
                SignalQualityChip(quality = quality, label = qualityLabel)
                if (ageText != null) {
                    AgeIndicator(text = ageText, badge = ageBadge)
                }
            }
        }
    }
}

/** The ring's thickness as a fraction of the donut's diameter (matches the mockup's 13 px on 118 px). */
private const val RING_THICKNESS: Float = 0.11f

/** A donut with no live reading draws its arc this faint (or none at all when the value is unknown). */
private const val MUTED_ARC_ALPHA: Float = 0.30f

/** The centred value never grows past this share of the diameter, so it stays inside the ring's hole. */
private const val INNER_TEXT_FRACTION: Float = 0.66f

/** The smallest the centred value shrinks to (font scale 1.3, a four-character reading) before clipping. */
private const val DONUT_MIN_VALUE_SP: Int = 20

@FieldTapPreviews
@Composable
private fun SignalDonutPreview() {
    val labels = SignalQualityLabels("Excellent", "Good", "Fair", "Poor", "No value")
    PreviewSurface {
        SignalDonutHero(
            label = "NR SS-RSRP",
            value = -68,
            quality = SignalQuality.EXCELLENT,
            unit = "dBm",
            qualityLabel = labels.excellent,
            donutContentDescription = "NR SS-RSRP, -68 dBm, Excellent",
            ageText = "Serving cell · 1.3 s old",
            ageBadge = AgeBadge.FRESH,
        )
        SignalDonutHero(
            label = "NR SS-RSRP",
            value = -101,
            quality = SignalQuality.FAIR,
            unit = "dBm",
            qualityLabel = labels.fair,
            donutContentDescription = "NR SS-RSRP, -101 dBm, Fair",
            ageText = "Serving cell · 7.9 s old",
            ageBadge = AgeBadge.AGING,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Lg), verticalAlignment = Alignment.CenterVertically) {
            SignalDonut(
                value = -117,
                quality = SignalQuality.POOR,
                unit = "dBm",
                contentDescription = "NR SS-RSRP, -117 dBm, Poor",
                diameter = Sizes.DonutCompact,
            )
            SignalDonut(
                value = null,
                quality = null,
                unit = "dBm",
                contentDescription = "NR SS-RSRP, no value yet",
                diameter = Sizes.DonutCompact,
            )
        }
    }
}
