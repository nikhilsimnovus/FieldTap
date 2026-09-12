package com.fieldtap.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Material 3 shapes, tightened for the crisp "analytics document" feel: cards are 16 dp, tiles and
 * controls 12 dp, sheets 24 dp.
 */
val FieldTapShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * Which shape each kind of element uses, so both screen workstreams round the same things the same
 * way.
 */
object ShapeRoles {
    /** Metric tiles / wells, list rows with a background, chart panels. */
    val Tile: Shape = FieldTapShapes.small

    /** Section cards, banners, the limits statement, the floating action bar. */
    val Card: Shape = FieldTapShapes.large

    /** Dialogs and the top of bottom sheets. */
    val Sheet: Shape = FieldTapShapes.extraLarge

    /**
     * Text fields and menus. It is Material's own default for text fields, so `OutlinedTextField` needs
     * no shape argument.
     */
    val Field: Shape = FieldTapShapes.extraSmall

    /** Buttons: 12 dp rounded rectangles, not pills, for the confident analytics look. */
    val Control: Shape = RoundedCornerShape(12.dp)

    /** Age badges, quality chips, ghost chips, the cadence pill, identity tags. */
    val Pill: Shape = CircleShape

    /** Signal-meter zones, bar tracks and the spectrum strip — squared, meter-like. */
    val Bar: Shape = RoundedCornerShape(4.dp)
}
