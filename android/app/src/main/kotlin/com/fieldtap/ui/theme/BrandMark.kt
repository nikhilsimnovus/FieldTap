package com.fieldtap.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.fieldtap.ui.components.FieldTapPreviews
import com.fieldtap.ui.components.PreviewSurface

/**
 * The 5gto6G mark: five rising signal bars and a sixth that leaps higher in the accent colour, on the
 * blue-to-violet brand gradient. It is the launcher icon's artwork (res/drawable/ic_launcher_*.xml)
 * drawn in Compose, for the disclosure and About screens.
 *
 * Square; give it a size with the modifier (for example `Modifier.size(Sizes.EmptyStateBadge)`).
 * Decorative unless [contentDescription] is given.
 */
@Composable
fun FieldTapBrandMark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val semantics = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }
    Canvas(modifier = modifier.then(semantics)) { drawBrandMark() }
}

/**
 * Bar geometry in the adaptive icon's 108-unit canvas, identical to res/drawable/ic_launcher_foreground.xml.
 * Every corner lies inside the 66-unit safe circle; `BrandResourcesTest` checks it.
 */
internal object BrandMarkGeometry {
    const val BAR_COUNT: Int = 6
    const val FIRST_BAR_X: Float = 29f
    const val BAR_PITCH: Float = 8.8f
    const val BAR_WIDTH: Float = 6f
    const val BASELINE_Y: Float = 75f
    const val CORNER: Float = 1.5f
    val BAR_HEIGHTS: FloatArray = floatArrayOf(8f, 14f, 20f, 26f, 32f, 42f)

    /** The adaptive icon shows units 18..90 of 108. */
    const val VISIBLE_ORIGIN: Float = 18f
    const val VISIBLE_SIZE: Float = 72f

    /** Centre and radius of the safe zone every launcher mask keeps. */
    const val CENTRE: Float = 54f
    const val SAFE_RADIUS: Float = 33f
}

internal fun DrawScope.drawBrandMark() {
    val side = size.minDimension
    val left = (size.width - side) / 2f
    val top = (size.height - side) / 2f
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(BrandColors.GradientStart, BrandColors.GradientEnd),
            start = Offset(left, top),
            end = Offset(left + side, top + side),
        ),
        topLeft = Offset(left, top),
        size = Size(side, side),
        cornerRadius = CornerRadius(side * 0.26f),
    )
    val unit = side / BrandMarkGeometry.VISIBLE_SIZE
    for (i in 0 until BrandMarkGeometry.BAR_COUNT) {
        val height = BrandMarkGeometry.BAR_HEIGHTS[i]
        val x = BrandMarkGeometry.FIRST_BAR_X + i * BrandMarkGeometry.BAR_PITCH - BrandMarkGeometry.VISIBLE_ORIGIN
        val y = BrandMarkGeometry.BASELINE_Y - height - BrandMarkGeometry.VISIBLE_ORIGIN
        drawRoundRect(
            color = if (i == BrandMarkGeometry.BAR_COUNT - 1) BrandColors.MarkAccent else BrandColors.MarkBars,
            topLeft = Offset(left + x * unit, top + y * unit),
            size = Size(BrandMarkGeometry.BAR_WIDTH * unit, height * unit),
            cornerRadius = CornerRadius(BrandMarkGeometry.CORNER * unit),
        )
    }
}

@FieldTapPreviews
@Composable
private fun FieldTapBrandMarkPreview() {
    PreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Lg), verticalAlignment = Alignment.CenterVertically) {
            FieldTapBrandMark(modifier = Modifier.size(Sizes.IconContainer))
            FieldTapBrandMark(modifier = Modifier.size(Sizes.EmptyStateBadge), contentDescription = "5gto6G FieldTap")
        }
    }
}
