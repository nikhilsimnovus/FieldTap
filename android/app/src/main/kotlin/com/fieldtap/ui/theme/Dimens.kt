package com.fieldtap.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing on a 4 dp grid. Use these instead of literal dp values, so both screen workstreams keep
 * the same rhythm.
 *
 * Screens: [ScreenGutter] at the sides, [SectionGap] between cards, [ItemGap] between items in a card,
 * [EyebrowGap] between an eyebrow overline and the value or first row it labels.
 */
object Spacing {
    val Xxs: Dp = 2.dp
    val Xs: Dp = 4.dp
    val Sm: Dp = 8.dp
    val Md: Dp = 12.dp
    val Lg: Dp = 16.dp
    val Xl: Dp = 24.dp
    val Xxl: Dp = 32.dp
    val Xxxl: Dp = 48.dp

    /** Horizontal screen padding on phones (more editorial air than the old 16). */
    val ScreenGutter: Dp = 20.dp

    /** Horizontal screen padding at 600 dp and wider. */
    val ScreenGutterWide: Dp = 32.dp

    /** Inside cards and tiles. */
    val CardPadding: Dp = 20.dp

    /** Between cards and sections. */
    val SectionGap: Dp = 20.dp

    /** Between items inside a card. */
    val ItemGap: Dp = 12.dp

    /** Between an eyebrow overline and the value or first row it leads. */
    val EyebrowGap: Dp = 6.dp
}

/** Fixed sizes. */
object Sizes {
    /** Every tappable element is at least this in both directions. */
    val MinTouchTarget: Dp = 48.dp

    /** Icons inside badges and chips. */
    val IconTiny: Dp = 14.dp
    val IconSmall: Dp = 18.dp
    val Icon: Dp = 24.dp
    val IconLarge: Dp = 32.dp

    /** The icon inside an empty state's badge. */
    val IconHero: Dp = 40.dp

    /** The tonal circle behind a leading icon in rows and rationale blocks. */
    val IconContainer: Dp = 40.dp

    /** The tonal circle behind an empty state's icon. */
    val EmptyStateBadge: Dp = 88.dp

    /** The colour swatch in a quality chip, and the tone dot in a ghost chip. */
    val Swatch: Dp = 10.dp

    /** The pulsing dot next to "Recording". */
    val RecordingDot: Dp = 12.dp

    /** A spinner inside a button or a row. */
    val InlineProgress: Dp = 20.dp

    /** A metric tile is never narrower than this (times the font scale, when above 1). */
    val TileMinWidth: Dp = 148.dp

    /** A compact tile of a headline grid: two columns on a phone at font scale 1.3, four in landscape. */
    val TileCompactMinWidth: Dp = 120.dp

    val PrimaryButtonHeight: Dp = 64.dp
    val ChartPanelHeight: Dp = 128.dp

    /** The RSRP panel when the SINR panel folds to one line, so the trend takes the room SINR leaves. */
    val ChartPanelTallHeight: Dp = 160.dp

    /** A chart panel beside the session buttons in a short, wide window (a phone in landscape). */
    val ChartPanelCompactHeight: Dp = 96.dp

    /** The signal-bar track height (the compact four-bar idiom on rows). */
    val SignalBarHeight: Dp = 8.dp

    /** The value's mark on a compact signal bar, drawn over the bar's zones. */
    val SignalMarkerWidth: Dp = 4.dp
    val SignalMarkerHeight: Dp = 16.dp

    /** The `SignalMeter` zone band — a hair taller than the old bar, so it reads as a meter face. */
    val MeterHeight: Dp = 10.dp

    /** The value marker on a `SignalMeter`. */
    val MeterMarkerWidth: Dp = 4.dp
    val MeterMarkerHeight: Dp = 18.dp

    /** The session-at-a-glance stacked quality bar on Session detail (`SpectrumStrip`). */
    val SpectrumStripHeight: Dp = 14.dp

    /** The margin of the floating action bar from the screen's edges. */
    val ActionBarInset: Dp = 12.dp

    /** The 1 px hairline that frames every card and chart well (daylight; shadow is not enough). */
    val HairlineWidth: Dp = 1.dp

    /** From this width a signal meter names its thresholds under its zones. */
    val SignalBarLabelsMinWidth: Dp = 280.dp
    val ListRowMinHeight: Dp = 72.dp

    /** Toggle, navigation and cell rows. */
    val SettingsRowMinHeight: Dp = 56.dp
    val KeyValueRowMinHeight: Dp = 40.dp

    /** Rows of a dense card: Overview, Collection, Files, Serving cell, Cadence details. */
    val KeyValueRowDenseMinHeight: Dp = 32.dp
    val BadgeMinHeight: Dp = 24.dp

    /** The one-line status strip pinned under the Live top bar while a session runs. */
    val StatusStripMinHeight: Dp = 40.dp

    /** The brand mark beside the disclosure's heading in a short window. */
    val BrandMarkCompact: Dp = 40.dp

    /** Centred prose (empty states) wraps at this width, even on wide screens. */
    val MaxTextWidth: Dp = 480.dp

    /** Content is centred and capped at this width on tablets and in landscape. */
    val MaxContentWidth: Dp = 720.dp

    /** From this window width the gutter is [Spacing.ScreenGutterWide] (tablets, most landscape phones). */
    val WideLayoutMinWidth: Dp = 600.dp

    /**
     * A wide window lower than this (a phone in landscape) has no room for a bottom action bar under its content: primary
     * actions move to a column beside it.
     */
    val ShortWindowMaxHeight: Dp = 480.dp

    /**
     * The column that holds a screen's primary actions beside its content in a short, wide window: three 48 dp icon
     * actions side by side at its top, the session buttons at its bottom.
     */
    val ActionRailWidth: Dp = 168.dp
}

/**
 * Depth tokens. `tonalElevation` is always **0**; depth is shadow + a 1 px hairline in light, and a
 * hairline + a top highlight in dark (see the card recipes in `DESIGN.md`). Read as `Elevation.Card`.
 */
object Elevation {
    /** Resting cards, tiles, rows. */
    val Card: Dp = 2.dp

    /** Sheets, dialogs, menus, the floating action bar, the top bar once content scrolls under it. */
    val Raised: Dp = 8.dp

    /** No shadow (chart wells, dark surfaces where shadow does not read). */
    val Flat: Dp = 0.dp
}

/** Animation durations in milliseconds. Keep motion short: this is an instrument, not a toy. */
object Durations {
    const val SHORT: Int = 150
    const val MEDIUM: Int = 250
    const val LONG: Int = 400

    /** One cycle of the recording dot's pulse. */
    const val PULSE: Int = 1100
}

/**
 * Motion specs for FieldTap's own components — physical, strictly-bounded springs that settle like an
 * instrument, never a bounce for its own sake. Built on `androidx.compose.animation.core` (always
 * available), independent of Material's `MotionScheme`, so the behaviour is unit-testable.
 *
 * Numbers never tween their value; only their **colour** and the **meter marker's position** move. Each
 * function takes [reduced] (from `LocalReducedMotion`): when true it collapses to [snap], so every entry,
 * resize and marker glide becomes an instant state change and the recording dot stops pulsing.
 */
object Motion {
    /** Standard easing for colour/opacity cross-fades. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Position, size and the meter marker: critically damped, settles like a needle with no overshoot. */
    fun <T> spatial(reduced: Boolean = false): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f)

    /** Container resize (chart grow, action-bar expand): a touch softer than [spatial]. */
    fun <T> container(reduced: Boolean = false): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 300f)

    /** Card and list entry: a whisper of bounce, one-shot only (never on the live feed). */
    fun <T> entry(reduced: Boolean = false): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.85f, stiffness = 300f)

    /** Colour and opacity cross-fades: standard easing, short. */
    fun <T> effect(reduced: Boolean = false): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMillis = Durations.SHORT, easing = Standard)
}
