package com.fieldtap.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/** Provided by [com.fieldtap.ui.FieldTapTheme]; the default only serves previews without a theme. */
internal val LocalFieldTapColors = staticCompositionLocalOf { FieldTapColors.Light }

/** Provided by [com.fieldtap.ui.FieldTapTheme]. */
internal val LocalNumericStyles = staticCompositionLocalOf { FieldTapNumeric }

/**
 * The FieldTap tokens that Material 3 has no slot for, next to `MaterialTheme`:
 *
 * ```
 * MaterialTheme.colorScheme.primary       // Material roles
 * MaterialTheme.typography.titleMedium    // Material type scale
 * FieldTapDesign.colors.warning.container // status colours
 * FieldTapDesign.signal.of(quality).fill  // the signal scale
 * FieldTapDesign.numeric.large            // tabular numbers
 * Spacing.Lg, Sizes.MinTouchTarget, ShapeRoles.Card
 * ```
 */
object FieldTapDesign {
    val colors: FieldTapColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFieldTapColors.current

    val signal: SignalColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFieldTapColors.current.signal

    val numeric: NumericStyles
        @Composable
        @ReadOnlyComposable
        get() = LocalNumericStyles.current

    /** Whether the user has removed animations; pass to the [Motion] specs. */
    val reducedMotion: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalReducedMotion.current
}
