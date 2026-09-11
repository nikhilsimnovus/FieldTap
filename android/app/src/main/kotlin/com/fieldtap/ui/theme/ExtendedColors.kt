package com.fieldtap.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** The tone of a status message, banner, badge or problem. */
enum class StatusTone {
    /** Nothing wrong; context or an explanation. */
    NEUTRAL,

    /** Brand-tinted information. */
    INFO,

    /** Done, granted, ready. */
    SUCCESS,

    /** Works, but worse: Wi-Fi forcing 10 s, a sample getting old, advice. */
    WARNING,

    /** Blocked, failed, stale, lost. */
    ERROR,
}

/**
 * A colour family: [color] for text, icons and small marks on any surface (4.5:1), [onColor] on a
 * [color] fill, [container] for a tinted background and [onContainer] for text on it (4.5:1).
 */
@Immutable
data class StatusColors(
    val color: Color,
    val onColor: Color,
    val container: Color,
    val onContainer: Color,
)

/**
 * One level of the signal scale.
 *
 * - [fill]: the report's route colour, for swatches, bar fills and signal bars. Never text.
 * - [edge]: drawn around or under a [fill] so the mark keeps 3:1 against the surface (the report's
 *   light green and orange are too pale on a light surface on their own).
 * - [onFill]: text or icons on a [fill] (4.5:1).
 * - [content]: text or icons in the level's hue on a surface (4.5:1), for example the quality label.
 */
@Immutable
data class SignalLevelColors(
    val fill: Color,
    val edge: Color,
    val onFill: Color,
    val content: Color,
)

/** The colours of [SignalQuality], plus [unknown] for a missing value. */
@Immutable
data class SignalColors(
    val excellent: SignalLevelColors,
    val good: SignalLevelColors,
    val fair: SignalLevelColors,
    val poor: SignalLevelColors,
    val unknown: SignalLevelColors,
) {
    fun of(quality: SignalQuality?): SignalLevelColors = when (quality) {
        SignalQuality.EXCELLENT -> excellent
        SignalQuality.GOOD -> good
        SignalQuality.FAIR -> fair
        SignalQuality.POOR -> poor
        null -> unknown
    }

    companion object {
        /** The report's route colours, exactly: #1a9641, #a6d96a, #fdae61, #d7191c. */
        val ReportRoute: Map<SignalQuality, Color> = mapOf(
            SignalQuality.EXCELLENT to Color(0xFF1A9641),
            SignalQuality.GOOD to Color(0xFFA6D96A),
            SignalQuality.FAIR to Color(0xFFFDAE61),
            SignalQuality.POOR to Color(0xFFD7191C),
        )

        val Light: SignalColors = SignalColors(
            excellent = SignalLevelColors(Color(0xFF1A9641), Color(0xFF127533), Color(0xFF000000), Color(0xFF13692F)),
            good = SignalLevelColors(Color(0xFFA6D96A), Color(0xFF4A7A1E), Color(0xFF000000), Color(0xFF46691A)),
            fair = SignalLevelColors(Color(0xFFFDAE61), Color(0xFFB8660B), Color(0xFF000000), Color(0xFF8F4A00)),
            poor = SignalLevelColors(Color(0xFFD7191C), Color(0xFFD7191C), Color(0xFFFFFFFF), Color(0xFFB3141A)),
            unknown = SignalLevelColors(
                fill = FieldTapColorSchemes.Light.surfaceContainerHighest,
                edge = FieldTapColorSchemes.Light.outline,
                onFill = FieldTapColorSchemes.Light.onSurfaceVariant,
                content = FieldTapColorSchemes.Light.onSurfaceVariant,
            ),
        )

        /** Same hues; the red is lifted so it keeps 3:1 on raised dark containers. */
        val Dark: SignalColors = SignalColors(
            excellent = SignalLevelColors(Color(0xFF1A9641), Color(0xFF3DBA63), Color(0xFF000000), Color(0xFF6BD48A)),
            good = SignalLevelColors(Color(0xFFA6D96A), Color(0xFFA6D96A), Color(0xFF000000), Color(0xFFB6E07C)),
            fair = SignalLevelColors(Color(0xFFFDAE61), Color(0xFFFDAE61), Color(0xFF000000), Color(0xFFFFB870)),
            poor = SignalLevelColors(Color(0xFFF0443E), Color(0xFFF0443E), Color(0xFF000000), Color(0xFFFF8A80)),
            unknown = SignalLevelColors(
                fill = FieldTapColorSchemes.Dark.surfaceContainerHighest,
                edge = FieldTapColorSchemes.Dark.outline,
                onFill = FieldTapColorSchemes.Dark.onSurfaceVariant,
                content = FieldTapColorSchemes.Dark.onSurfaceVariant,
            ),
        )
    }
}

/**
 * Colours Material 3 has no role for. Read them with `FieldTapDesign.colors`.
 *
 * [recording] is the running-session colour (Stop button, recording dot); it is not [error], so a
 * running session never reads as a failure in code. Chart colours: RSRP is the brand blue, SINR the
 * brand violet, neither of which is on the signal scale.
 */
@Immutable
data class FieldTapColors(
    val neutral: StatusColors,
    val info: StatusColors,
    val success: StatusColors,
    val warning: StatusColors,
    val error: StatusColors,
    val recording: StatusColors,
    val signal: SignalColors,
    val chartRsrp: Color,
    val chartSinr: Color,
    val chartGrid: Color,
    val chartReference: Color,
    val chartKeyReference: Color,
    val isDark: Boolean,
) {
    fun status(tone: StatusTone): StatusColors = when (tone) {
        StatusTone.NEUTRAL -> neutral
        StatusTone.INFO -> info
        StatusTone.SUCCESS -> success
        StatusTone.WARNING -> warning
        StatusTone.ERROR -> error
    }

    companion object {
        val Light: FieldTapColors = FieldTapColorSchemes.Light.let { s ->
            FieldTapColors(
                neutral = StatusColors(s.onSurfaceVariant, s.surface, s.surfaceContainerHighest, s.onSurfaceVariant),
                info = StatusColors(s.primary, s.onPrimary, s.primaryContainer, s.onPrimaryContainer),
                success = StatusColors(Color(0xFF1E6B34), Color(0xFFFFFFFF), Color(0xFFB7F1BD), Color(0xFF005320)),
                warning = StatusColors(Color(0xFF825500), Color(0xFFFFFFFF), Color(0xFFFFDDB0), Color(0xFF633F00)),
                error = StatusColors(s.error, s.onError, s.errorContainer, s.onErrorContainer),
                recording = StatusColors(Color(0xFFB3132A), Color(0xFFFFFFFF), Color(0xFFFFDAD9), Color(0xFF8E0016)),
                signal = SignalColors.Light,
                chartRsrp = s.primary,
                chartSinr = s.tertiary,
                chartGrid = s.outlineVariant,
                chartReference = s.outline,
                chartKeyReference = s.onSurfaceVariant,
                isDark = false,
            )
        }

        val Dark: FieldTapColors = FieldTapColorSchemes.Dark.let { s ->
            FieldTapColors(
                neutral = StatusColors(s.onSurfaceVariant, s.surface, s.surfaceContainerHighest, s.onSurfaceVariant),
                info = StatusColors(s.primary, s.onPrimary, s.primaryContainer, s.onPrimaryContainer),
                success = StatusColors(Color(0xFF8CD793), Color(0xFF00391A), Color(0xFF005320), Color(0xFFB7F1BD)),
                warning = StatusColors(Color(0xFFFFB951), Color(0xFF452B00), Color(0xFF633F00), Color(0xFFFFDDB0)),
                error = StatusColors(s.error, s.onError, s.errorContainer, s.onErrorContainer),
                recording = StatusColors(Color(0xFFFFB3AE), Color(0xFF68000D), Color(0xFF8E0016), Color(0xFFFFDAD9)),
                signal = SignalColors.Dark,
                chartRsrp = s.primary,
                chartSinr = s.tertiary,
                chartGrid = s.outlineVariant,
                chartReference = s.outline,
                chartKeyReference = s.onSurfaceVariant,
                isDark = true,
            )
        }

        fun of(dark: Boolean): FieldTapColors = if (dark) Dark else Light
    }
}
