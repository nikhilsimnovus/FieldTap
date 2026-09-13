package com.fieldtap.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Material 3 shapes for the **Momentum** feel: generously rounded. Section cards are 24 dp, tiles and
 * wells 18 dp, controls 16 dp, sheets 28 dp, small controls and fields 14 dp. Rounder than the old
 * analytics-document scale — the calm scroll of soft rounded cards is a signature of the look.
 */
val FieldTapShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Which shape each kind of element uses, so both screen workstreams round the same things the same
 * way.
 */
object ShapeRoles {
    /** Metric tiles / wells, list rows with a background, chart panels. */
    val Tile: Shape = FieldTapShapes.medium

    /** Section cards, banners, the limits statement, the floating action bar, the hero card. */
    val Card: Shape = FieldTapShapes.large

    /** Dialogs and the top of bottom sheets. */
    val Sheet: Shape = FieldTapShapes.extraLarge

    /**
     * Text fields and menus. It is Material's own default for text fields, so `OutlinedTextField` needs
     * no shape argument.
     */
    val Field: Shape = FieldTapShapes.small

    /** Buttons: 16 dp friendly rounded rectangles, the big primary button included. */
    val Control: Shape = RoundedCornerShape(16.dp)

    /** Age badges, quality pills, ghost chips, the cadence pill, identity tags. */
    val Pill: Shape = CircleShape

    /** Signal-meter zones, bar tracks and the spectrum strip — squared, meter-like. */
    val Bar: Shape = RoundedCornerShape(4.dp)
}
