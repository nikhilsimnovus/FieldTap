package com.fieldtap.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the palette numerically: WCAG AA for text (4.5:1) and 3:1 for graphical marks, in light and
 * dark, on every surface a component can put them on.
 */
class ThemeContrastTest {
    private class Theme(val name: String, val scheme: ColorScheme, val colors: FieldTapColors)

    private val themes = listOf(
        Theme("light", FieldTapColorSchemes.Light, FieldTapColors.Light),
        Theme("dark", FieldTapColorSchemes.Dark, FieldTapColors.Dark),
    )

    private fun surfaces(s: ColorScheme): Map<String, Color> = linkedMapOf(
        "surface" to s.surface,
        "background" to s.background,
        "surfaceDim" to s.surfaceDim,
        "surfaceBright" to s.surfaceBright,
        "surfaceContainerLowest" to s.surfaceContainerLowest,
        "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest,
    )

    private val failures = mutableListOf<String>()

    private fun expect(label: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = Contrast.ratio(foreground, background)
        if (ratio < minimum) {
            failures += String.format(Locale.ROOT, "%s: %.2f < %.1f", label, ratio, minimum)
        }
    }

    private fun assertNoFailures() {
        assertTrue(failures.joinToString(separator = "\n", prefix = "Contrast failures:\n"), failures.isEmpty())
    }

    @Test
    fun contrastRatioMatchesWcagReferenceValues() {
        assertEquals(21.0, Contrast.ratio(Color.Black, Color.White), 1e-9)
        assertEquals(21.0, Contrast.ratio(Color.White, Color.Black), 1e-9)
        assertEquals(1.0, Contrast.ratio(Color(0xFF777777), Color(0xFF777777)), 1e-9)
        // #767676 on white is the classic 4.54:1.
        assertEquals(4.54, Contrast.ratio(Color(0xFF767676), Color.White), 0.01)
    }

    @Test
    fun translucentForegroundIsCompositedFirst() {
        // 50 % black over white is mid grey: between #808080 and #7F7F7F, depending on rounding.
        val ratio = Contrast.ratio(Color.Black.copy(alpha = 0.5f), Color.White)
        val lighter = Contrast.ratio(Color(0xFF808080), Color.White)
        val darker = Contrast.ratio(Color(0xFF7F7F7F), Color.White)
        assertTrue("ratio $ratio not in [$lighter, $darker]", ratio >= lighter - 0.01 && ratio <= darker + 0.01)
    }

    @Test
    fun onRolesMeetAaOnTheirRoles() {
        for (t in themes) {
            val s = t.scheme
            val pairs = listOf(
                Triple("onPrimary/primary", s.onPrimary, s.primary),
                Triple("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer),
                Triple("onSecondary/secondary", s.onSecondary, s.secondary),
                Triple("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
                Triple("onTertiary/tertiary", s.onTertiary, s.tertiary),
                Triple("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
                Triple("onError/error", s.onError, s.error),
                Triple("onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer),
                Triple("onBackground/background", s.onBackground, s.background),
                Triple("onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant),
                Triple("inverseOnSurface/inverseSurface", s.inverseOnSurface, s.inverseSurface),
                Triple("inversePrimary/inverseSurface", s.inversePrimary, s.inverseSurface),
                Triple("onPrimaryFixed/primaryFixed", s.onPrimaryFixed, s.primaryFixed),
                Triple("onPrimaryFixedVariant/primaryFixed", s.onPrimaryFixedVariant, s.primaryFixed),
                Triple("onTertiaryFixed/tertiaryFixed", s.onTertiaryFixed, s.tertiaryFixed),
            )
            for ((label, fg, bg) in pairs) expect("${t.name} $label", fg, bg, Contrast.AA_TEXT)
        }
        assertNoFailures()
    }

    @Test
    fun textRolesMeetAaOnEverySurface() {
        for (t in themes) {
            val s = t.scheme
            val roles = mapOf(
                "onSurface" to s.onSurface,
                "onSurfaceVariant" to s.onSurfaceVariant,
                "primary" to s.primary,
                "secondary" to s.secondary,
                "tertiary" to s.tertiary,
                "error" to s.error,
            )
            for ((surfaceName, surface) in surfaces(s)) {
                for ((roleName, role) in roles) expect("${t.name} $roleName on $surfaceName", role, surface, Contrast.AA_TEXT)
                expect("${t.name} outline on $surfaceName", s.outline, surface, Contrast.AA_GRAPHICS)
            }
        }
        assertNoFailures()
    }

    @Test
    fun statusColoursMeetAa() {
        for (t in themes) {
            for (tone in StatusTone.entries) {
                val family = t.colors.status(tone)
                expect("${t.name} $tone onColor/color", family.onColor, family.color, Contrast.AA_TEXT)
                expect("${t.name} $tone onContainer/container", family.onContainer, family.container, Contrast.AA_TEXT)
                for ((surfaceName, surface) in surfaces(t.scheme)) {
                    expect("${t.name} $tone color on $surfaceName", family.color, surface, Contrast.AA_TEXT)
                }
            }
            val recording = t.colors.recording
            expect("${t.name} recording onColor/color", recording.onColor, recording.color, Contrast.AA_TEXT)
            expect("${t.name} recording onContainer/container", recording.onContainer, recording.container, Contrast.AA_TEXT)
            for ((surfaceName, surface) in surfaces(t.scheme)) {
                expect("${t.name} recording color on $surfaceName", recording.color, surface, Contrast.AA_TEXT)
            }
        }
        assertNoFailures()
    }

    @Test
    fun signalLevelsAreReadableAndVisibleOnEverySurface() {
        for (t in themes) {
            val levels = SignalQuality.entries.map { it.name to t.colors.signal.of(it) } + ("UNKNOWN" to t.colors.signal.unknown)
            for ((name, level) in levels) {
                expect("${t.name} $name onFill/fill", level.onFill, level.fill, Contrast.AA_TEXT)
                for ((surfaceName, surface) in surfaces(t.scheme)) {
                    expect("${t.name} $name content on $surfaceName", level.content, surface, Contrast.AA_TEXT)
                    val mark = maxOf(Contrast.ratio(level.fill, surface), Contrast.ratio(level.edge, surface))
                    if (name != "UNKNOWN" && mark < Contrast.AA_GRAPHICS) {
                        failures += String.format(Locale.ROOT, "%s %s fill|edge on %s: %.2f < 3.0", t.name, name, surfaceName, mark)
                    }
                }
            }
        }
        assertNoFailures()
    }

    @Test
    fun chartLinesAreVisibleOnEverySurface() {
        for (t in themes) {
            for ((surfaceName, surface) in surfaces(t.scheme)) {
                expect("${t.name} chartRsrp on $surfaceName", t.colors.chartRsrp, surface, Contrast.AA_GRAPHICS)
                expect("${t.name} chartSinr on $surfaceName", t.colors.chartSinr, surface, Contrast.AA_GRAPHICS)
                expect("${t.name} chartReference on $surfaceName", t.colors.chartReference, surface, Contrast.AA_GRAPHICS)
            }
        }
        assertNoFailures()
    }

    @Test
    fun brandMarkIsVisibleOnTheBrandGradient() {
        for (background in listOf(BrandColors.GradientStart, BrandColors.GradientEnd)) {
            expect("mark bars on gradient", BrandColors.MarkBars, background, Contrast.AA_GRAPHICS)
            expect("mark accent on gradient", BrandColors.MarkAccent, background, Contrast.AA_GRAPHICS)
        }
        assertNoFailures()
    }

    @Test
    fun lightFillsAreExactlyTheReportRouteColours() {
        for (quality in SignalQuality.entries) {
            assertEquals(quality.name, SignalColors.ReportRoute.getValue(quality), SignalColors.Light.of(quality).fill)
        }
    }

    @Test
    fun darkThemeFlagsMatch() {
        assertEquals(false, FieldTapColors.Light.isDark)
        assertEquals(true, FieldTapColors.Dark.isDark)
        assertEquals(FieldTapColorSchemes.Dark, FieldTapColorSchemes.of(dark = true))
        assertEquals(FieldTapColors.Light, FieldTapColors.of(dark = false))
    }
}
