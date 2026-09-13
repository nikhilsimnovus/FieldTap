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
 * - [content]: text or icons in the level's hue on a surface (4.5:1), for example a value's quality.
 * - [pillContainer] / [onPillContainer]: the Momentum **quality pill** — a soft tinted fill with its own
 *   readable text (4.5:1), for the "Excellent" pill in the Live hero and the quality chip. The pill's
 *   leading dot uses [fill] over [edge], so the exact report colour is still on screen next to the soft
 *   tint.
 */
@Immutable
data class SignalLevelColors(
    val fill: Color,
    val edge: Color,
    val onFill: Color,
    val content: Color,
    val pillContainer: Color,
    val onPillContainer: Color,
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
            excellent = SignalLevelColors(Color(0xFF1A9641), Color(0xFF127533), Color(0xFF000000), Color(0xFF13692F), Color(0xFFDCFCE7), Color(0xFF15803D)),
            good = SignalLevelColors(Color(0xFFA6D96A), Color(0xFF4A7A1E), Color(0xFF000000), Color(0xFF46691A), Color(0xFFECFCCB), Color(0xFF4D7C0F)),
            fair = SignalLevelColors(Color(0xFFFDAE61), Color(0xFFB8660B), Color(0xFF000000), Color(0xFF8F4A00), Color(0xFFFEF3C7), Color(0xFF92400E)),
            poor = SignalLevelColors(Color(0xFFD7191C), Color(0xFFD7191C), Color(0xFFFFFFFF), Color(0xFFB3141A), Color(0xFFFEE2E2), Color(0xFFB91C1C)),
            unknown = SignalLevelColors(
                fill = FieldTapColorSchemes.Light.surfaceContainerHighest,
                edge = FieldTapColorSchemes.Light.outline,
                onFill = FieldTapColorSchemes.Light.onSurfaceVariant,
                content = FieldTapColorSchemes.Light.onSurfaceVariant,
                pillContainer = FieldTapColorSchemes.Light.surfaceContainerHighest,
                onPillContainer = FieldTapColorSchemes.Light.onSurfaceVariant,
            ),
        )

        /** Same hues; the red is lifted so it keeps 3:1 on raised dark containers; pills are dark tints. */
        val Dark: SignalColors = SignalColors(
            excellent = SignalLevelColors(Color(0xFF1A9641), Color(0xFF3DBA63), Color(0xFF000000), Color(0xFF6BD48A), Color(0xFF123524), Color(0xFF7EE0A0)),
            good = SignalLevelColors(Color(0xFFA6D96A), Color(0xFFA6D96A), Color(0xFF000000), Color(0xFFB6E07C), Color(0xFF26340F), Color(0xFFC3E88A)),
            fair = SignalLevelColors(Color(0xFFFDAE61), Color(0xFFFDAE61), Color(0xFF000000), Color(0xFFFFB870), Color(0xFF3A2A0A), Color(0xFFFBBF66)),
            poor = SignalLevelColors(Color(0xFFF0443E), Color(0xFFF0443E), Color(0xFF000000), Color(0xFFFF8A80), Color(0xFF3A1518), Color(0xFFFF9B94)),
            unknown = SignalLevelColors(
                fill = FieldTapColorSchemes.Dark.surfaceContainerHighest,
                edge = FieldTapColorSchemes.Dark.outline,
                onFill = FieldTapColorSchemes.Dark.onSurfaceVariant,
                content = FieldTapColorSchemes.Dark.onSurfaceVariant,
                pillContainer = FieldTapColorSchemes.Dark.surfaceContainerHighest,
                onPillContainer = FieldTapColorSchemes.Dark.onSurfaceVariant,
            ),
        )
    }
}

/**
 * Colours Material 3 has no role for. Read them with `FieldTapDesign.colors`.
 *
 * [recording] is the running-session colour (Stop button, recording dot); it is not [error], so a
 * running session never reads as a failure in code. [info] maps to the indigo accent (`primary`). Chart
 * colours: RSRP is the accent indigo ([chartRsrp] = `primary`), SINR a muted violet ([chartSinr] =
 * `tertiary`), neither of which is on the signal scale; [chartReference] is the `outline` dashed
 * threshold, [chartKeyReference] the `onSurfaceVariant` solid −105 dBm key line.
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
                success = StatusColors(Color(0xFF137536), Color(0xFFFFFFFF), Color(0xFFB7F1BD), Color(0xFF00391A)),
                warning = StatusColors(Color(0xFF8A4600), Color(0xFFFFFFFF), Color(0xFFFFDDB0), Color(0xFF5A2E00)),
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
                success = StatusColors(Color(0xFF7FD98E), Color(0xFF00391A), Color(0xFF00391A), Color(0xFFB7F1BD)),
                warning = StatusColors(Color(0xFFFFB84D), Color(0xFF5A2E00), Color(0xFF5A2E00), Color(0xFFFFDDB0)),
                error = StatusColors(s.error, s.onError, s.errorContainer, s.onErrorContainer),
                recording = StatusColors(Color(0xFFFFB3AE), Color(0xFF8E0016), Color(0xFF8E0016), Color(0xFFFFDAD9)),
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
