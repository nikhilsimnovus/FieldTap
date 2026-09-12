package com.fieldtap.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The 5gto6G launcher-and-signature tones. These are **not** the UI accent: the app's accent is the calm
 * [FieldTapColorSchemes] `primary` (a restrained blue), used sparingly on primary actions, selection and
 * focus only. [BrandColors] is confined to the launcher icon (`res/drawable/ic_launcher_*.xml`) and the
 * [FieldTapBrandMark] signature on the disclosure and About screens — never as a UI colour. It stays a
 * blue-to-violet mark because a home-screen icon is an identity, not the app's chrome; the screens are
 * neutral. `res/values/colors.xml` keeps these identical (`BrandResourcesTest`).
 */
object BrandColors {
    /** The mark's deeper gradient blue. Launcher/signature only, never a UI accent. */
    val RadioBlue: Color = Color(0xFF2C33C7)

    /** The mark's violet, for the launcher/signature gradient. */
    val SixGViolet: Color = Color(0xFF7236BC)

    /** Launcher and signature gradient start (diagonal blue → violet). */
    val GradientStart: Color = Color(0xFF1D24C4)

    /** Launcher and signature gradient end. */
    val GradientEnd: Color = Color(0xFF5A2BD6)

    /** The sixth bar of the brand mark: the cyan spark that leaps higher. */
    val MarkAccent: Color = Color(0xFF7FE7FF)

    /** The first five bars of the brand mark. */
    val MarkBars: Color = Color(0xFFFFFFFF)
}

/**
 * The "Clearsheet" Material 3 colour schemes: a clean, familiar, broadly-acceptable utility look. Light
 * ("sheet") is a bright, daylight-legible cool near-white; dark ("true dark") is a real equal near-black.
 * A single calm accent (`primary`, a restrained blue) is used **only** on the primary button, the
 * selected state, the focus ring and the RSRP chart line; everything else is neutral or a functional
 * signal/status colour. Dynamic (wallpaper) colour is deliberately off, so the app looks the same on every
 * phone.
 *
 * `tonalElevation` is **0** on every surface (see `Elevation`): depth is a 1 px hairline plus a whisper of
 * shadow in light, and the hairline plus the lift of `surfaceContainerLow` in dark — never a tonal
 * overlay. The `surfaceContainer*` roles are therefore explicit values, so Material never tints a raised
 * surface toward the accent.
 *
 * Every load-bearing text role meets WCAG AA (4.5:1) on every surface it can sit on, `outline` meets 3:1
 * on the grounds it frames, and every signal mark meets 3:1 on the surface it rides; `ThemeContrastTest`
 * proves the numbers (see `DESIGN.md` §2.6 for the tightest guarded pairs).
 */
object FieldTapColorSchemes {
    val Light: ColorScheme = lightColorScheme(
        primary = Color(0xFF2C5CB0),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDCE6FB),
        onPrimaryContainer = Color(0xFF12315F),
        inversePrimary = Color(0xFF9EC1FF),
        secondary = Color(0xFF565A63),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE0E2E8),
        onSecondaryContainer = Color(0xFF3A3D45),
        tertiary = Color(0xFF6D53B5),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE8DEFB),
        onTertiaryContainer = Color(0xFF43356E),
        background = Color(0xFFF7F8FA),
        onBackground = Color(0xFF1B1C1E),
        surface = Color(0xFFF7F8FA),
        onSurface = Color(0xFF1B1C1E),
        surfaceVariant = Color(0xFFE3E5EA),
        onSurfaceVariant = Color(0xFF494C52),
        surfaceTint = Color(0xFF2C5CB0),
        inverseSurface = Color(0xFF2E3033),
        inverseOnSurface = Color(0xFFF2F3F5),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        outline = Color(0xFF74777E),
        outlineVariant = Color(0xFFDCDFE4),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFF2F3F6),
        surfaceContainerHigh = Color(0xFFEEF0F4),
        surfaceContainerHighest = Color(0xFFECEEF2),
        surfaceContainerLow = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFE4E6EA),
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
        primary = Color(0xFF9EC1FF),
        onPrimary = Color(0xFF0A0B0D),
        primaryContainer = Color(0xFF213A63),
        onPrimaryContainer = Color(0xFFD6E3FF),
        inversePrimary = Color(0xFF2C5CB0),
        secondary = Color(0xFFC3C6CF),
        onSecondary = Color(0xFF2C2F36),
        secondaryContainer = Color(0xFF3A3D45),
        onSecondaryContainer = Color(0xFFE0E2E8),
        tertiary = Color(0xFFC4A9FF),
        onTertiary = Color(0xFF2A1A54),
        tertiaryContainer = Color(0xFF3F3072),
        onTertiaryContainer = Color(0xFFE8DEFB),
        background = Color(0xFF121316),
        onBackground = Color(0xFFE4E6E9),
        surface = Color(0xFF121316),
        onSurface = Color(0xFFE4E6E9),
        surfaceVariant = Color(0xFF2A2D33),
        onSurfaceVariant = Color(0xFFB4B8BF),
        surfaceTint = Color(0xFF9EC1FF),
        inverseSurface = Color(0xFFE4E6E9),
        inverseOnSurface = Color(0xFF2E3033),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF8B8F98),
        outlineVariant = Color(0xFF33363C),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF33353B),
        surfaceContainer = Color(0xFF202226),
        surfaceContainerHigh = Color(0xFF24262B),
        surfaceContainerHighest = Color(0xFF2A2D33),
        surfaceContainerLow = Color(0xFF1D1F23),
        surfaceContainerLowest = Color(0xFF0D0E10),
        surfaceDim = Color(0xFF121316),
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
 * Material's "fixed" roles keep one value in light and dark, by definition. Re-derived from the accent
 * ramp. They are unused by screens (this design's surfaces are explicit) but must be non-null, and their
 * on/container pairs still meet AA (`ThemeContrastTest`).
 */
private object FixedRoles {
    val PrimaryFixed = Color(0xFFDCE6FB)
    val PrimaryFixedDim = Color(0xFF9EC1FF)
    val OnPrimaryFixed = Color(0xFF12315F)
    val OnPrimaryFixedVariant = Color(0xFF1B3B72)
    val SecondaryFixed = Color(0xFFE0E2E8)
    val SecondaryFixedDim = Color(0xFFC3C6CF)
    val OnSecondaryFixed = Color(0xFF191C22)
    val OnSecondaryFixedVariant = Color(0xFF3A3D45)
    val TertiaryFixed = Color(0xFFE8DEFB)
    val TertiaryFixedDim = Color(0xFFC4A9FF)
    val OnTertiaryFixed = Color(0xFF2A1A54)
    val OnTertiaryFixedVariant = Color(0xFF43356E)
}
