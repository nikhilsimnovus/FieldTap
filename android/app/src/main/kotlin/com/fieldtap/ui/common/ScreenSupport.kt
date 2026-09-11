package com.fieldtap.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.fieldtap.R
import com.fieldtap.format.Rat
import com.fieldtap.ui.components.SignalQualityLabels
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import kotlinx.coroutines.delay

/** How long a wait must last before a loading indicator appears (DESIGN.md: about 300 ms). */
const val LOADING_INDICATOR_DELAY_MS: Long = 300

/**
 * The side padding of a screen: [Spacing.ScreenGutter] on phones, [Spacing.ScreenGutterWide] from a
 * [Sizes.WideLayoutMinWidth] wide window (tablets, most landscape phones), the same rule as the setup screens.
 */
@Composable
fun screenGutter(): Dp =
    if (LocalWindowInfo.current.containerDpSize.width >= Sizes.WideLayoutMinWidth) Spacing.ScreenGutterWide else Spacing.ScreenGutter

/** Full width up to [Sizes.MaxContentWidth]; centre it with the parent's alignment on tablets and in landscape. */
fun Modifier.contentWidth(): Modifier = this.widthIn(max = Sizes.MaxContentWidth).fillMaxWidth()

/**
 * True once [active] has stayed true for [delayMs], and false as soon as it turns false, so a quick load
 * never flashes a spinner.
 */
@Composable
fun rememberDelayedVisibility(active: Boolean, delayMs: Long = LOADING_INDICATOR_DELAY_MS): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            delay(delayMs)
            visible = true
        } else {
            visible = false
        }
    }
    return visible && active
}

/** The level words of this screen's signal colours (the same words as the setup screens). */
@Composable
fun signalQualityLabels(): SignalQualityLabels = SignalQualityLabels(
    excellent = stringResource(R.string.quality_excellent),
    good = stringResource(R.string.quality_good),
    fair = stringResource(R.string.quality_fair),
    poor = stringResource(R.string.quality_poor),
    unknown = stringResource(R.string.quality_unknown),
)

/** "LTE", "NR", "GSM" and so on. */
@Composable
fun ratName(rat: Rat): String = stringResource(
    when (rat) {
        Rat.LTE -> R.string.rat_lte
        Rat.NR -> R.string.rat_nr
        Rat.WCDMA -> R.string.rat_wcdma
        Rat.GSM -> R.string.rat_gsm
        Rat.TDSCDMA -> R.string.rat_tdscdma
        Rat.CDMA -> R.string.rat_cdma
    },
)
