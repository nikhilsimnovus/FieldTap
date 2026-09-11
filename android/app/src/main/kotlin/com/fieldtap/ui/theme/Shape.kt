package com.fieldtap.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Material 3 shapes, slightly rounder than the baseline for a softer, modern card language. */
val FieldTapShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Which shape each kind of element uses, so both screen workstreams round the same things the same
 * way.
 */
object ShapeRoles {
    /** Metric tiles, list rows with a background, chart panels. */
    val Tile: Shape = FieldTapShapes.medium

    /** Section cards, banners, the limits statement. */
    val Card: Shape = FieldTapShapes.large

    /** Dialogs and the top of bottom sheets. */
    val Sheet: Shape = FieldTapShapes.extraLarge

    /**
     * Text fields, menus, and the ripple of full-width rows. It is Material's own default for text
     * fields, so `OutlinedTextField` needs no shape argument.
     */
    val Field: Shape = FieldTapShapes.extraSmall

    /** Age badges, quality chips, the cadence pill, buttons. */
    val Pill: Shape = CircleShape

    /** Signal-bar tracks and fills. */
    val Bar: Shape = RoundedCornerShape(percent = 50)
}
