package com.fieldtap.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing on a 4 dp grid. Use these instead of literal dp values, so both screen workstreams keep
 * the same rhythm.
 *
 * Screens: [ScreenGutter] at the sides, [SectionGap] between cards, [ItemGap] between items in a card.
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

    /** Horizontal screen padding on phones. */
    val ScreenGutter: Dp = 16.dp

    /** Horizontal screen padding at 600 dp and wider. */
    val ScreenGutterWide: Dp = 24.dp

    /** Inside cards and tiles. */
    val CardPadding: Dp = 16.dp

    /** Between cards and sections. */
    val SectionGap: Dp = 16.dp

    /** Between items inside a card. */
    val ItemGap: Dp = 12.dp
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

    /** The colour swatch in a quality chip. */
    val Swatch: Dp = 10.dp

    /** The pulsing dot next to "Recording". */
    val RecordingDot: Dp = 12.dp

    /** A spinner inside a button or a row. */
    val InlineProgress: Dp = 20.dp

    /** A metric tile is never narrower than this (times the font scale, when above 1). */
    val TileMinWidth: Dp = 148.dp

    /** A compact tile of a four-tile headline (Session detail): two columns on a phone at font scale 1.3, four in landscape. */
    val TileCompactMinWidth: Dp = 120.dp

    val PrimaryButtonHeight: Dp = 64.dp
    val ChartPanelHeight: Dp = 128.dp

    /** The RSRP panel when the SINR panel folds to one line, so the trend takes the room SINR leaves. */
    val ChartPanelTallHeight: Dp = 160.dp

    /** A chart panel beside the session buttons in a short, wide window (a phone in landscape). */
    val ChartPanelCompactHeight: Dp = 96.dp
    val SignalBarHeight: Dp = 8.dp

    /** The value's mark on a signal bar, drawn over the bar's zones. */
    val SignalMarkerWidth: Dp = 4.dp
    val SignalMarkerHeight: Dp = 16.dp

    /** From this width a signal bar names its thresholds under its zones. */
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

/** Animation durations in milliseconds. Keep motion short: this is an instrument, not a toy. */
object Durations {
    const val SHORT: Int = 150
    const val MEDIUM: Int = 250
    const val LONG: Int = 400

    /** One cycle of the recording dot's pulse. */
    const val PULSE: Int = 1100
}
