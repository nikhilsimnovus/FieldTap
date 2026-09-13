package com.fieldtap.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fieldtap.R

/** OpenType feature for tabular (fixed-width) figures, so live numbers do not jiggle as they change. */
const val TABULAR_FIGURES: String = "tnum"

/** This style with tabular figures. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TABULAR_FIGURES)

/**
 * One weight of the bundled **Hanken Grotesk** variable face (`res/font/hanken_grotesk_variable.ttf`, SIL
 * Open Font License — the licence text ships at `assets/fonts/HankenGrotesk-OFL.txt`). The single variable
 * `ttf` is pinned to each weight through `FontVariation` (minSdk 31 ≫ API 26, so the `wght` axis is honoured
 * on every device the app runs on), so the whole ramp is one 130 KB file, not one file per weight.
 */
private fun hanken(weight: Int): Font = Font(
    resId = R.font.hanken_grotesk_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/**
 * The Momentum default face: **Hanken Grotesk**, a friendly modern geometric sans (SIL OFL, redistributable),
 * bundled as a variable font and made the app's default [FontFamily]. The ramp carries Regular (400) through
 * ExtraBold (800), which is all Momentum uses: 800 for display/headline punch, 700 for titles and numbers,
 * 600 for labels, 400 for body. Sizes stay in sp, so they follow the user's font scale, and every numeric
 * style keeps tabular figures (see [FieldTapNumeric]) so live values never jitter.
 *
 * If bundling ever breaks the build, swap this for `FontFamily.Default`: the Momentum feel comes mainly from
 * shape, colour, spacing and depth, which the tokens control regardless of the face.
 */
val HankenGrotesk: FontFamily = FontFamily(
    hanken(400),
    hanken(500),
    hanken(600),
    hanken(700),
    hanken(800),
)

private val Base = Typography()

/**
 * The Material 3 type scale in **Hanken Grotesk**. The weight comes from the face, Momentum-style:
 * ExtraBold (800) display and headline styles and `titleLarge`, Bold (700) `titleMedium`/`titleSmall`,
 * Medium/Regular body, SemiBold (600) labels. Sizes are Material's, in sp, so they follow the user's font
 * scale.
 */
val FieldTapTypography: Typography = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
    displayMedium = Base.displayMedium.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
    displaySmall = Base.displaySmall.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em),
    headlineLarge = Base.headlineLarge.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em),
    headlineMedium = Base.headlineMedium.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold),
    titleLarge = Base.titleLarge.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em),
    titleMedium = Base.titleMedium.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold),
    titleSmall = Base.titleSmall.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold),
    bodyLarge = Base.bodyLarge.copy(fontFamily = HankenGrotesk),
    bodyMedium = Base.bodyMedium.copy(fontFamily = HankenGrotesk),
    bodySmall = Base.bodySmall.copy(fontFamily = HankenGrotesk),
    labelLarge = Base.labelLarge.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold),
    labelSmall = Base.labelSmall.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium),
)

/**
 * The **Momentum mini-label** (the eyebrow): a small, uppercase, letter-spaced overline above a metric,
 * tile or section — the signature small-caps label of the mockup ("NR SS-RSRP", "BAND / ARFCN",
 * "OPERATOR"). Rendered by the [com.fieldtap.ui.components.Eyebrow] component, which paints it in
 * `onSurfaceVariant`, uppercases the text and carries `heading()` semantics when it leads a section. The
 * uppercasing and the 0.08 em tracking are the Momentum look; screens pass sentence-case strings.
 */
val SectionLabel: TextStyle = FieldTapTypography.labelMedium.copy(
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.08.em,
)

/**
 * The former name of the section-label style, kept so the [com.fieldtap.ui.components.Eyebrow] contract is
 * unchanged; it is exactly [SectionLabel] (uppercase, tracked mini-label).
 */
val Eyebrow: TextStyle = SectionLabel

/**
 * Styles for numbers, all tabular and all Hanken Grotesk. Read them with `FieldTapDesign.numeric`. Exactly
 * one [display] or [hero] figure appears per screen.
 *
 * | Style | Use |
 * | --- | --- |
 * | [display] | The Live serving-RSRP hero (donut centre / open block). Rendered with `TextAutoSize` (min 36 sp) so it shrinks, never clips, at font scale 1.3, in landscape and in short windows. |
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
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 56.sp,
        lineHeight = 60.sp,
        letterSpacing = (-0.03).em,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    hero = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.03).em,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    large = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.02).em,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    medium = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.01).em,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    body = FieldTapTypography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR_FIGURES),
    bodySmall = FieldTapTypography.bodyMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
    label = FieldTapTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR_FIGURES),
    axis = FieldTapTypography.labelSmall.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = TABULAR_FIGURES),
)
