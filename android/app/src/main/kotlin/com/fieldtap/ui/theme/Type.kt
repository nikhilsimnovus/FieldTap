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
 * The Material 3 type scale in the system font (Roboto or the maker's font), with semibold
 * headlines and titles for a clear hierarchy. Sizes are Material's, in sp, so they follow the
 * user's font scale.
 */
val FieldTapTypography: Typography = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = FontFamily.Default),
    displayMedium = Base.displayMedium.copy(fontFamily = FontFamily.Default),
    displaySmall = Base.displaySmall.copy(fontFamily = FontFamily.Default),
    headlineLarge = Base.headlineLarge.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold),
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
 * Styles for numbers that change while you watch them. All use tabular figures. Read them with
 * `FieldTapDesign.numeric`.
 *
 * | Style | Use |
 * | --- | --- |
 * | [hero] | The one number that matters most on a screen: serving RSRP on Live. |
 * | [large] | Metric tile values. |
 * | [medium] | Compact tiles, the elapsed time on the session button, stat values. |
 * | [body] | Values in key-value rows and lists. |
 * | [bodySmall] | Secondary values: neighbour rows, supporting lines. |
 * | [label] | Age badges, cadence, chips. |
 * | [axis] | Chart axis labels. |
 */
@Immutable
data class NumericStyles(
    val hero: TextStyle,
    val large: TextStyle,
    val medium: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val axis: TextStyle,
)

val FieldTapNumeric: NumericStyles = NumericStyles(
    hero = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        letterSpacing = (-0.01).em,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    large = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    medium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    body = FieldTapTypography.bodyLarge.copy(fontFeatureSettings = TABULAR_FIGURES),
    bodySmall = FieldTapTypography.bodyMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
    label = FieldTapTypography.labelMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
    axis = FieldTapTypography.labelSmall.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = TABULAR_FIGURES),
)
