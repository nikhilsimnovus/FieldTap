package com.fieldtap.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** OpenType feature for tabular (fixed-width) figures, so live numbers do not jiggle as they change. */
const val TABULAR_FIGURES: String = "tnum"

/** This style with tabular figures. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TABULAR_FIGURES)

private val Base = Typography()

/**
 * The Material 3 type scale in the system font (`FontFamily.Default`; no bundled face, which the product
 * constraints forbid and which would break the user's font scale). The editorial "Fieldbook" character
 * comes from scale, weight and tracking: **Bold** display styles (splash and empty moments) and **Bold**
 * headlines and `titleLarge` for punch, SemiBold `titleMedium`/`titleSmall`, and Normal body/label. Sizes
 * are Material's, in sp, so they follow the user's font scale.
 */
val FieldTapTypography: Typography = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold),
    displayMedium = Base.displayMedium.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold),
    displaySmall = Base.displaySmall.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold),
    headlineLarge = Base.headlineLarge.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold),
    headlineMedium = Base.headlineMedium.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold),
    titleLarge = Base.titleLarge.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontFamily = FontFamily.Default),
    bodyMedium = Base.bodyMedium.copy(fontFamily = FontFamily.Default),
    bodySmall = Base.bodySmall.copy(fontFamily = FontFamily.Default),
    labelLarge = Base.labelLarge.copy(fontFamily = FontFamily.Default),
    labelMedium = Base.labelMedium.copy(fontFamily = FontFamily.Default),
    labelSmall = Base.labelSmall.copy(fontFamily = FontFamily.Default),
)

/**
 * The editorial **eyebrow**: a small, uppercase, letter-spaced overline above every metric and every
 * section header, replacing heavy title rows. The colour and the UPPERCASE transform are applied by the
 * [com.fieldtap.ui.components.Eyebrow] component (which also carries `heading()` semantics when used as a
 * section header), so this style holds only the size, weight and tracking. Derived from `labelSmall`.
 */
val Eyebrow: TextStyle = FieldTapTypography.labelSmall.copy(
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.09.em,
)

/**
 * Styles for numbers, all tabular. Read them with `FieldTapDesign.numeric`. Exactly one [display] or
 * [hero] figure appears per screen.
 *
 * | Style | Use |
 * | --- | --- |
 * | [display] | The Live serving-RSRP hero. Rendered by the screen with `TextAutoSize` (min 36 sp) so it shrinks, never clips, at font scale 1.3, in landscape and in short windows. |
 * | [hero] | Session-detail median RSRP: the "hero within a card". |
 * | [large] | Metric-tile and stat-well values. |
 * | [medium] | Compact tiles, the elapsed timer, stats. |
 * | [body] | Row and list values. |
 * | [bodySmall] | Neighbour rows, supporting lines. |
 * | [label] | Age badges, cadence, chips. |
 * | [axis] | Chart axis labels. |
 */
@Immutable
data class NumericStyles(
    val display: TextStyle,
    val hero: TextStyle,
    val large: TextStyle,
    val medium: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val axis: TextStyle,
)

val FieldTapNumeric: NumericStyles = NumericStyles(
    display = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 56.sp,
        lineHeight = 60.sp,
        letterSpacing = (-0.02).em,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    hero = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.02).em,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    large = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    medium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    body = FieldTapTypography.bodyLarge.copy(fontFeatureSettings = TABULAR_FIGURES),
    bodySmall = FieldTapTypography.bodyMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
    label = FieldTapTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR_FIGURES),
    axis = FieldTapTypography.labelSmall.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = TABULAR_FIGURES),
)
