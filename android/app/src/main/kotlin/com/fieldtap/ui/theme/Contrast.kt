package com.fieldtap.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow

/**
 * WCAG 2.x contrast. Used by the theme tests to prove the palette, and available to screens that
 * must pick a readable colour at run time.
 */
object Contrast {
    /** Normal text and icons that carry meaning. */
    const val AA_TEXT: Double = 4.5

    /** Large text (18 sp, or 14 sp bold) and graphical marks such as bars, outlines and chart lines. */
    const val AA_GRAPHICS: Double = 3.0

    /** Relative luminance of an opaque colour, 0 (black) to 1 (white). */
    fun relativeLuminance(color: Color): Double {
        val argb = color.toArgb()
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * Contrast ratio of [foreground] on [background], 1 to 21. A translucent foreground is composited
     * over the background first; the background is treated as opaque.
     */
    fun ratio(foreground: Color, background: Color): Double {
        val opaqueBackground = background.copy(alpha = 1f)
        val fg = if (foreground.alpha < 1f) foreground.compositeOver(opaqueBackground) else foreground
        val lf = relativeLuminance(fg)
        val lb = relativeLuminance(opaqueBackground)
        val hi = maxOf(lf, lb)
        val lo = minOf(lf, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
