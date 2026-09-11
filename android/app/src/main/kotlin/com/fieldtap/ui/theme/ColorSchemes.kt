package com.fieldtap.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The 5gto6G brand tones: a radio blue that leads into a 6G violet. Screens do not use these
 * directly; they read [androidx.compose.material3.MaterialTheme.colorScheme] or [FieldTapDesign.colors].
 * The launcher icon and the splash screen use the same tones (res/values/colors.xml).
 *
 * Blue and violet were chosen because the signal-quality scale owns green, orange and red, and
 * warnings own amber, so a brand colour never reads as a measurement.
 */
object BrandColors {
    /** Primary in the light scheme. */
    val RadioBlue: Color = Color(0xFF2A4FD6)

    /** Tertiary in the light scheme: the "6G" end of the brand. */
    val SixGViolet: Color = Color(0xFF7236BC)

    /** Launcher and splash gradient start. */
    val GradientStart: Color = Color(0xFF1638C4)

    /** Launcher and splash gradient end. */
    val GradientEnd: Color = Color(0xFF6A2BD6)

    /** The sixth bar of the brand mark. */
    val MarkAccent: Color = Color(0xFF7FE7FF)

    /** The first five bars of the brand mark. */
    val MarkBars: Color = Color(0xFFFFFFFF)
}

/**
 * Material 3 colour schemes for light and dark. Dynamic (wallpaper) colour is deliberately not used,
 * so the brand and the signal colours look the same on every phone.
 *
 * Every text role meets WCAG AA (4.5:1) on every surface container it can sit on, and `outline`
 * meets 3:1; `ThemeContrastTest` checks the numbers.
 */
object FieldTapColorSchemes {
    val Light: ColorScheme = lightColorScheme(
        primary = Color(0xFF2A4FD6),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDCE1FF),
        onPrimaryContainer = Color(0xFF0A2A8C),
        inversePrimary = Color(0xFFB6C4FF),
        secondary = Color(0xFF565E7A),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDCE1FB),
        onSecondaryContainer = Color(0xFF3E4661),
        tertiary = Color(0xFF7236BC),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFEFDBFF),
        onTertiaryContainer = Color(0xFF5E22A8),
        background = Color(0xFFFAF9FF),
        onBackground = Color(0xFF1A1B22),
        surface = Color(0xFFFAF9FF),
        onSurface = Color(0xFF1A1B22),
        surfaceVariant = Color(0xFFE2E1EE),
        onSurfaceVariant = Color(0xFF454652),
        surfaceTint = Color(0xFF2A4FD6),
        inverseSurface = Color(0xFF2F3037),
        inverseOnSurface = Color(0xFFF2F0F9),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        outline = Color(0xFF757684),
        outlineVariant = Color(0xFFC5C5D4),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFAF9FF),
        surfaceContainer = Color(0xFFEEEDF6),
        surfaceContainerHigh = Color(0xFFE9E7F1),
        surfaceContainerHighest = Color(0xFFE3E1EB),
        surfaceContainerLow = Color(0xFFF4F2FC),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFDBD9E3),
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
        primary = Color(0xFFB6C4FF),
        onPrimary = Color(0xFF00278A),
        primaryContainer = Color(0xFF1D3DBA),
        onPrimaryContainer = Color(0xFFDCE1FF),
        inversePrimary = Color(0xFF2A4FD6),
        secondary = Color(0xFFC0C6E6),
        onSecondary = Color(0xFF2A3049),
        secondaryContainer = Color(0xFF40475F),
        onSecondaryContainer = Color(0xFFDCE1FB),
        tertiary = Color(0xFFDCB8FF),
        onTertiary = Color(0xFF47108A),
        tertiaryContainer = Color(0xFF6129AC),
        onTertiaryContainer = Color(0xFFEFDBFF),
        background = Color(0xFF121319),
        onBackground = Color(0xFFE3E1EB),
        surface = Color(0xFF121319),
        onSurface = Color(0xFFE3E1EB),
        surfaceVariant = Color(0xFF454652),
        onSurfaceVariant = Color(0xFFC5C5D4),
        surfaceTint = Color(0xFFB6C4FF),
        inverseSurface = Color(0xFFE3E1EB),
        inverseOnSurface = Color(0xFF2F3037),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF8F909E),
        outlineVariant = Color(0xFF454652),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF383940),
        surfaceContainer = Color(0xFF1F1F26),
        surfaceContainerHigh = Color(0xFF292A31),
        surfaceContainerHighest = Color(0xFF34343C),
        surfaceContainerLow = Color(0xFF1A1B22),
        surfaceContainerLowest = Color(0xFF0D0E14),
        surfaceDim = Color(0xFF121319),
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

/** Material's "fixed" roles keep one value in light and dark, by definition. */
private object FixedRoles {
    val PrimaryFixed = Color(0xFFDCE1FF)
    val PrimaryFixedDim = Color(0xFFB6C4FF)
    val OnPrimaryFixed = Color(0xFF00164F)
    val OnPrimaryFixedVariant = Color(0xFF1D3DBA)
    val SecondaryFixed = Color(0xFFDCE1FB)
    val SecondaryFixedDim = Color(0xFFC0C6E6)
    val OnSecondaryFixed = Color(0xFF151B32)
    val OnSecondaryFixedVariant = Color(0xFF40475F)
    val TertiaryFixed = Color(0xFFEFDBFF)
    val TertiaryFixedDim = Color(0xFFDCB8FF)
    val OnTertiaryFixed = Color(0xFF2A0054)
    val OnTertiaryFixedVariant = Color(0xFF5E22A8)
}
