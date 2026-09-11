package com.fieldtap.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.fieldtap.ui.theme.FieldTapColorSchemes
import com.fieldtap.ui.theme.FieldTapColors
import com.fieldtap.ui.theme.FieldTapNumeric
import com.fieldtap.ui.theme.FieldTapShapes
import com.fieldtap.ui.theme.FieldTapTypography
import com.fieldtap.ui.theme.LocalFieldTapColors
import com.fieldtap.ui.theme.LocalNumericStyles

/**
 * The app's only theme entry point: the 5gto6G colour schemes, the type scale, the shapes, and the
 * FieldTap tokens read through [com.fieldtap.ui.theme.FieldTapDesign]. Wrap each activity's content in
 * it once; component and screen previews use `com.fieldtap.ui.components.PreviewSurface`.
 *
 * - Dynamic (wallpaper) colour is off on purpose, so the brand and the signal colours are the same on
 *   every phone.
 * - [darkTheme] follows the system. Walk mode passes `true` to force the dark surface; the status and
 *   navigation bar icons follow it, and are restored when the forced theme leaves composition.
 *
 * See `ui/theme/DESIGN.md`.
 */
@Composable
fun FieldTapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    SystemBarAppearance(darkTheme = darkTheme)
    CompositionLocalProvider(
        LocalFieldTapColors provides FieldTapColors.of(darkTheme),
        LocalNumericStyles provides FieldTapNumeric,
    ) {
        MaterialTheme(
            colorScheme = FieldTapColorSchemes.of(darkTheme),
            shapes = FieldTapShapes,
            typography = FieldTapTypography,
            content = content,
        )
    }
}

/**
 * Light bar icons on the dark surface, dark icons on the light one. `enableEdgeToEdge()` already follows
 * the system setting; this keeps the icons readable when [darkTheme] is forced, and puts back what was
 * there before when it leaves composition, so a nested theme cannot leave the bars wrong.
 */
@Composable
private fun SystemBarAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, darkTheme) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val previousStatusBars = controller.isAppearanceLightStatusBars
            val previousNavigationBars = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
            onDispose {
                controller.isAppearanceLightStatusBars = previousStatusBars
                controller.isAppearanceLightNavigationBars = previousNavigationBars
            }
        }
    }
}

/** The activity behind a possibly wrapped context, or null (for example in a service). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
