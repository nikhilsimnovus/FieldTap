package com.fieldtap.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The 5gto6G brand tones: a confident **Cobalt** that leads into a 6G violet. Screens do not use these
 * directly; they read [androidx.compose.material3.MaterialTheme.colorScheme] or [FieldTapDesign.colors].
 * The launcher icon and the splash screen use the same tones (res/values/colors.xml, kept identical by
 * `BrandResourcesTest`).
 *
 * Cobalt is a single, opinionated accent; violet is demoted to the SINR chart line only, because the
 * signal-quality scale owns green, orange and red and warnings own amber, so a brand colour never reads
 * as a measurement.
 */
object BrandColors {
    /** Primary in the light scheme — Cobalt. */
    val RadioBlue: Color = Color(0xFF2C33C7)

    /** Tertiary in the light scheme: the "6G" violet, used on the SINR line only. */
    val SixGViolet: Color = Color(0xFF7236BC)

    /** Launcher and splash gradient start (diagonal Cobalt → violet). */
    val GradientStart: Color = Color(0xFF1D24C4)

    /** Launcher and splash gradient end. */
    val GradientEnd: Color = Color(0xFF5A2BD6)

    /** The sixth bar of the brand mark: the cyan spark that leaps higher. */
    val MarkAccent: Color = Color(0xFF7FE7FF)

    /** The first five bars of the brand mark. */
    val MarkBars: Color = Color(0xFFFFFFFF)
}

/**
 * The "Fieldbook" Material 3 colour schemes for light ("paper") and dark ("ink"). Dynamic (wallpaper)
 * colour is deliberately not used, so the brand and the signal colours look the same on every phone.
 *
 * `tonalElevation` is **0** on every surface (see `Elevation`): depth is shadow + a 1 px hairline in
 * light, and a hairline + a top highlight in dark — never a tonal overlay. The `surfaceContainer*` roles
 * are therefore explicit values, so Material never tints a raised surface toward the primary.
 *
 * Every load-bearing text role meets WCAG AA (4.5:1) on every surface it can sit on, `outline` meets 3:1
 * on the paper/card/well grounds it frames, and every signal mark meets 3:1 on the chip it rides;
 * `ThemeContrastTest` proves the numbers.
 */
object FieldTapColorSchemes {
    val Light: ColorScheme = lightColorScheme(
        primary = Color(0xFF2C33C7),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE0E2FF),
        onPrimaryContainer = Color(0xFF10156A),
        inversePrimary = Color(0xFFA7B2FF),
        secondary = Color(0xFF545768),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDEE0F0),
        onSecondaryContainer = Color(0xFF3D4050),
        tertiary = Color(0xFF7236BC),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFEFDBFF),
        onTertiaryContainer = Color(0xFF5E22A8),
        background = Color(0xFFF6F6F4),
        onBackground = Color(0xFF17181C),
        surface = Color(0xFFF6F6F4),
        onSurface = Color(0xFF17181C),
        surfaceVariant = Color(0xFFE4E4DF),
        onSurfaceVariant = Color(0xFF585A63),
        surfaceTint = Color(0xFF2C33C7),
        inverseSurface = Color(0xFF2F3037),
        inverseOnSurface = Color(0xFFF3F3F0),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        outline = Color(0xFF8A8C94),
        outlineVariant = Color(0xFFE2E2DC),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFEDEDEA),
        surfaceContainerHigh = Color(0xFFE7E7E3),
        surfaceContainerHighest = Color(0xFFE4E4DF),
        surfaceContainerLow = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFE2E2DD),
        primaryFixed = FixedRoles.PrimaryFixed,
        primaryFixedDim = FixedRoles.PrimaryFixedDim,
        onPrimaryFixed = FixedRoles.OnPrimaryFixed,
        onPrimaryFixedVariant = FixedRoles.OnPrimaryFixedVariant,
        secondaryFixed = FixedRoles.SecondaryFixed,
        secondaryFixedDim = FixedRoles.SecondaryFixedDim,
        onSecondaryFixed = FixedRoles.OnSecondaryFixed,
        onSecondaryFixedVariant = FixedRoles.OnSecondaryFixedVariant,
        tertiaryFixed = FixedRoles.TertiaryFixed,
        tertiaryFixedDim = FixedRoles.TertiaryFixedDim,
        onTertiaryFixed = FixedRoles.OnTertiaryFixed,
        onTertiaryFixedVariant = FixedRoles.OnTertiaryFixedVariant,
    )

    val Dark: ColorScheme = darkColorScheme(
        primary = Color(0xFFA7B2FF),
        onPrimary = Color(0xFF0A0B0D),
        primaryContainer = Color(0xFF1F2597),
        onPrimaryContainer = Color(0xFFDFE1FF),
        inversePrimary = Color(0xFF2C33C7),
        secondary = Color(0xFFBFC2D6),
        onSecondary = Color(0xFF2B2E42),
        secondaryContainer = Color(0xFF3D4056),
        onSecondaryContainer = Color(0xFFDEE0F0),
        tertiary = Color(0xFFD9B7FF),
        onTertiary = Color(0xFF2A0054),
        tertiaryContainer = Color(0xFF5E22A8),
        onTertiaryContainer = Color(0xFFEFDBFF),
        background = Color(0xFF0A0B0D),
        onBackground = Color(0xFFECEDEF),
        surface = Color(0xFF0A0B0D),
        onSurface = Color(0xFFECEDEF),
        surfaceVariant = Color(0xFF2A2C33),
        onSurfaceVariant = Color(0xFFA5A8B1),
        surfaceTint = Color(0xFFA7B2FF),
        inverseSurface = Color(0xFFECEDEF),
        inverseOnSurface = Color(0xFF2F3037),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF70727B),
        outlineVariant = Color(0xFF2A2C33),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF33353C),
        surfaceContainer = Color(0xFF1C1E23),
        surfaceContainerHigh = Color(0xFF24262C),
        surfaceContainerHighest = Color(0xFF2B2E35),
        surfaceContainerLow = Color(0xFF16181C),
        surfaceContainerLowest = Color(0xFF0E0F12),
        surfaceDim = Color(0xFF0A0B0D),
        primaryFixed = FixedRoles.PrimaryFixed,
        primaryFixedDim = FixedRoles.PrimaryFixedDim,
        onPrimaryFixed = FixedRoles.OnPrimaryFixed,
        onPrimaryFixedVariant = FixedRoles.OnPrimaryFixedVariant,
        secondaryFixed = FixedRoles.SecondaryFixed,
        secondaryFixedDim = FixedRoles.SecondaryFixedDim,
        onSecondaryFixed = FixedRoles.OnSecondaryFixed,
        onSecondaryFixedVariant = FixedRoles.OnSecondaryFixedVariant,
        tertiaryFixed = FixedRoles.TertiaryFixed,
        tertiaryFixedDim = FixedRoles.TertiaryFixedDim,
        onTertiaryFixed = FixedRoles.OnTertiaryFixed,
        onTertiaryFixedVariant = FixedRoles.OnTertiaryFixedVariant,
    )

    /** The scheme for [dark]. */
    fun of(dark: Boolean): ColorScheme = if (dark) Dark else Light
}

/**
 * Material's "fixed" roles keep one value in light and dark, by definition. Re-derived from the Cobalt
 * ramp. They are unused by screens (this design's surfaces are explicit) but must be non-null.
 */
private object FixedRoles {
    val PrimaryFixed = Color(0xFFE0E2FF)
    val PrimaryFixedDim = Color(0xFFA7B2FF)
    val OnPrimaryFixed = Color(0xFF00105B)
    val OnPrimaryFixedVariant = Color(0xFF1B23A6)
    val SecondaryFixed = Color(0xFFDEE0F0)
    val SecondaryFixedDim = Color(0xFFC2C5D8)
    val OnSecondaryFixed = Color(0xFF10131F)
    val OnSecondaryFixedVariant = Color(0xFF3D4050)
    val TertiaryFixed = Color(0xFFEFDBFF)
    val TertiaryFixedDim = Color(0xFFD9B7FF)
    val OnTertiaryFixed = Color(0xFF2A0054)
    val OnTertiaryFixedVariant = Color(0xFF5E22A8)
}
