package com.fieldtap.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the "Momentum" palette numerically: WCAG AA for text (4.5:1) and 3:1 for graphical marks, in
 * light and dark, on the surfaces each thing is actually placed on.
 *
 * Three surface sets:
 * - [allSurfaces] — every container a load-bearing text role can sit on; text roles clear AA on all of them.
 * - [contentSurfaces] — where status words and signal marks are placed. `surfaceDim` is excluded: it is a
 *   dimmed backdrop (behind scrims and disabled content), never a ground for a word or a mark. Every real
 *   content ground clears the floor; the tightest is the chip track (`surfaceContainerHighest`).
 * - [graphicGrounds] — the canvas and white/well grounds a ghost-chip border, a card hairline and a chart
 *   line frame sit on. The `outline` role frames these; the darker fill surfaces use `outlineVariant`.
 */
class ThemeContrastTest {
    private class Theme(val name: String, val scheme: ColorScheme, val colors: FieldTapColors)

    private val themes = listOf(
        Theme("light", FieldTapColorSchemes.Light, FieldTapColors.Light),
        Theme("dark", FieldTapColorSchemes.Dark, FieldTapColors.Dark),
    )

    private fun allSurfaces(s: ColorScheme): Map<String, Color> = linkedMapOf(
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

    private fun contentSurfaces(s: ColorScheme): Map<String, Color> =
        allSurfaces(s).filterKeys { it != "surfaceDim" }

    private fun graphicGrounds(s: ColorScheme): Map<String, Color> = linkedMapOf(
        "surface" to s.surface,
        "background" to s.background,
        "surfaceContainerLowest" to s.surfaceContainerLowest,
        "surfaceContainerLow" to s.surfaceContainerLow,
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
            for ((surfaceName, surface) in allSurfaces(s)) {
                for ((roleName, role) in roles) expect("${t.name} $roleName on $surfaceName", role, surface, Contrast.AA_TEXT)
            }
        }
        assertNoFailures()
    }

    @Test
    fun outlineAndChartLinesReadOnTheirGrounds() {
        for (t in themes) {
            for ((surfaceName, surface) in graphicGrounds(t.scheme)) {
                // outline frames ghost chips and card hairlines that must read; chart lines/reference frame the well.
                expect("${t.name} outline on $surfaceName", t.scheme.outline, surface, Contrast.AA_GRAPHICS)
                expect("${t.name} chartRsrp on $surfaceName", t.colors.chartRsrp, surface, Contrast.AA_GRAPHICS)
                expect("${t.name} chartSinr on $surfaceName", t.colors.chartSinr, surface, Contrast.AA_GRAPHICS)
                expect("${t.name} chartReference on $surfaceName", t.colors.chartReference, surface, Contrast.AA_GRAPHICS)
            }
        }
        assertNoFailures()
    }

    @Test
    fun statusColoursMeetAa() {
        for (t in themes) {
            val tones = StatusTone.entries.map { it to t.colors.status(it) } + (null to t.colors.recording)
            for ((tone, family) in tones) {
                val name = tone?.name ?: "recording"
                expect("${t.name} $name onColor/color", family.onColor, family.color, Contrast.AA_TEXT)
                expect("${t.name} $name onContainer/container", family.onContainer, family.container, Contrast.AA_TEXT)
                for ((surfaceName, surface) in contentSurfaces(t.scheme)) {
                    expect("${t.name} $name color on $surfaceName", family.color, surface, Contrast.AA_TEXT)
                }
            }
        }
        assertNoFailures()
    }

    @Test
    fun signalLevelsAreReadableAndVisibleOnTheirGrounds() {
        for (t in themes) {
            val levels = SignalQuality.entries.map { it.name to t.colors.signal.of(it) } + ("UNKNOWN" to t.colors.signal.unknown)
            for ((name, level) in levels) {
                expect("${t.name} $name onFill/fill", level.onFill, level.fill, Contrast.AA_TEXT)
                for ((surfaceName, surface) in contentSurfaces(t.scheme)) {
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

    /**
     * The tightest pairs of the Momentum palette (DESIGN.md §2.6), guarded explicitly against a regression
     * that would drop them below the accessibility floor. The signal fills, edges and content are kept
     * verbatim from the report route; the surfaces are Momentum's white cards on a cool ground, so these
     * are the pairs to watch. The comment on each line is the computed WCAG ratio.
     */
    @Test
    fun theTightestFloorsAreGuarded() {
        val chip = FieldTapColorSchemes.Light.surfaceContainerHighest
        val background = FieldTapColorSchemes.Light.background
        val card = FieldTapColorSchemes.Light.surfaceContainerLow
        val well = FieldTapColorSchemes.Light.surfaceContainerHigh
        val darkCard = FieldTapColorSchemes.Dark.surfaceContainerLow
        val darkChip = FieldTapColorSchemes.Dark.surfaceContainerHighest
        // outline #737B8B on the cool light ground = 3.76 (card hairline / focus ring / chip border that must read).
        expect("light outline on background", FieldTapColorSchemes.Light.outline, background, Contrast.AA_GRAPHICS)
        // Signal FAIR edge #B8660B on a white card = 4.25 (the pale FAIR fill relies on the edge stroke).
        expect("light FAIR edge on card", SignalColors.Light.fair.edge, card, Contrast.AA_GRAPHICS)
        // Signal POOR edge #F0443E on a dark card = 4.64 (the lifted dark red keeps its meter boundary).
        expect("dark POOR edge on card", SignalColors.Dark.poor.edge, darkCard, Contrast.AA_GRAPHICS)
        // Chart SINR line #674EAD on the light well = 5.63 (the muted violet reads on the chart recess).
        expect("light chart SINR on well", FieldTapColors.Light.chartSinr, well, Contrast.AA_GRAPHICS)
        // Signal GOOD content #46691A on the light chip track = 5.25 (the tightest signal text pair).
        expect("light GOOD content on chip", SignalColors.Light.good.content, chip, Contrast.AA_TEXT)
        // Signal POOR content #FF8A80 on the dark chip track = 6.21.
        expect("dark POOR content on chip", SignalColors.Dark.poor.content, darkChip, Contrast.AA_TEXT)
        // onSurfaceVariant #565E70 on the light chip track = 5.36 (the label/secondary-text floor).
        expect("light onSurfaceVariant on chip", FieldTapColorSchemes.Light.onSurfaceVariant, chip, Contrast.AA_TEXT)
        // Momentum quality pills, the tightest soft-fill text pairs:
        // EXCELLENT #15803D on #DCFCE7 = 4.57, GOOD #4D7C0F on #ECFCCB = 4.60.
        expect("light EXCELLENT pill text", SignalColors.Light.excellent.onPillContainer, SignalColors.Light.excellent.pillContainer, Contrast.AA_TEXT)
        expect("light GOOD pill text", SignalColors.Light.good.onPillContainer, SignalColors.Light.good.pillContainer, Contrast.AA_TEXT)
        assertNoFailures()
    }

    /** Every quality pill's text meets AA on its own soft fill, in both themes (the Momentum quality chip / hero pill). */
    @Test
    fun qualityPillsMeetAa() {
        for (t in themes) {
            val levels = SignalQuality.entries.map { it.name to t.colors.signal.of(it) } + ("UNKNOWN" to t.colors.signal.unknown)
            for ((name, level) in levels) {
                expect("${t.name} $name pill text/fill", level.onPillContainer, level.pillContainer, Contrast.AA_TEXT)
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
