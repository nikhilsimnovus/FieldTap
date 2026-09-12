package com.fieldtap.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import com.fieldtap.ui.theme.Elevation
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.Sizes

/**
 * Depth in the "Clearsheet" model, shared by every card, tile, well and the floating action bar so they
 * lift the same way in both themes. `tonalElevation` is always 0; the look is hairline-led and near-flat.
 *
 * - **Light:** a 1 px [cardHairline] **plus** a whisper of [Elevation.Card] / [Elevation.Raised] shadow,
 *   because the canvas→card luminance step is tiny — a card is never defined by shadow alone (daylight).
 * - **Dark:** no shadow (shadows do not read on a true-dark canvas); the [cardHairline] and the lift of
 *   `surfaceContainerLow` over the canvas carry the edge.
 * - **Wells** (chart panels, metric tiles): [cardHairline] and no shadow in either theme.
 */
@Composable
@ReadOnlyComposable
fun cardShadowElevation(raised: Boolean = false): Dp =
    if (FieldTapDesign.colors.isDark) Elevation.Flat else if (raised) Elevation.Raised else Elevation.Card

/** The 1 px hairline every card, well and chart frame carries, in `outlineVariant`. */
@Composable
@ReadOnlyComposable
fun cardHairline(): BorderStroke = BorderStroke(Sizes.HairlineWidth, MaterialTheme.colorScheme.outlineVariant)
