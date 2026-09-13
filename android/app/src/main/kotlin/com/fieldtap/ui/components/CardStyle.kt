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
 * Depth in the Momentum model, shared by every card, tile, well and the floating action bar so they lift
 * the same way in both themes. `tonalElevation` is always 0; the look is shadow-led in light and lift-led
 * in dark.
 *
 * - **Light:** a real but soft [Elevation.Card] / [Elevation.Raised] shadow lifts the pure-white card off
 *   the cool ground, plus a faint [cardHairline] for crisp definition — never flat.
 * - **Dark:** no shadow (shadows do not read on a true-dark canvas); the [cardHairline] and the lift of the
 *   card's colour over the near-black ground carry the edge.
 * - **Wells** (chart panels, inner recesses): [cardHairline] and no shadow in either theme.
 */
@Composable
@ReadOnlyComposable
fun cardShadowElevation(raised: Boolean = false): Dp =
    if (FieldTapDesign.colors.isDark) Elevation.Flat else if (raised) Elevation.Raised else Elevation.Card

/**
 * A gentler lift than [cardShadowElevation] for metric tiles and small cards, so a tile nested inside a
 * section card reads as raised without competing with it. Flat in dark, [Elevation.Tile] in light.
 */
@Composable
@ReadOnlyComposable
fun tileShadowElevation(): Dp =
    if (FieldTapDesign.colors.isDark) Elevation.Flat else Elevation.Tile

/** The 1 px hairline every card, well and chart frame carries, in `outlineVariant`. */
@Composable
@ReadOnlyComposable
fun cardHairline(): BorderStroke = BorderStroke(Sizes.HairlineWidth, MaterialTheme.colorScheme.outlineVariant)
