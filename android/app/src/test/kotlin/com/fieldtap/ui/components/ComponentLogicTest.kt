package com.fieldtap.ui.components

import com.fieldtap.core.live.AgeBadge
import com.fieldtap.ui.theme.SignalQuality
import com.fieldtap.ui.theme.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decisions components make from state, which both screen workstreams rely on being the same. */
class ComponentLogicTest {
    @Test
    fun ageBadgeTurnsAmberThenRed() {
        assertEquals(StatusTone.NEUTRAL, ageTone(AgeBadge.NONE))
        assertEquals(StatusTone.NEUTRAL, ageTone(AgeBadge.FRESH))
        assertEquals(StatusTone.WARNING, ageTone(AgeBadge.AGING))
        assertEquals(StatusTone.ERROR, ageTone(AgeBadge.STALE))
    }

    @Test
    fun cadenceIsGreenAtTwoSecondsAndAmberAtTen() {
        assertEquals(StatusTone.SUCCESS, cadenceTone(shortInterval = true))
        assertEquals(StatusTone.WARNING, cadenceTone(shortInterval = false))
        assertEquals(StatusTone.NEUTRAL, cadenceTone(shortInterval = null))
    }

    @Test
    fun successWarningAndErrorHaveDifferentIcons() {
        val names = listOf(StatusTone.SUCCESS, StatusTone.WARNING, StatusTone.ERROR).map { statusIcon(it).name }
        assertEquals(names.size, names.toSet().size)
        assertNotEquals(statusIcon(StatusTone.INFO).name, statusIcon(StatusTone.WARNING).name)
    }

    @Test
    fun qualityLabelsPickTheLevelWord() {
        val labels = SignalQualityLabels(excellent = "Excellent", good = "Good", fair = "Fair", poor = "Poor", unknown = "No value")
        assertEquals("Excellent", labels.of(SignalQuality.EXCELLENT))
        assertEquals("Good", labels.of(SignalQuality.GOOD))
        assertEquals("Fair", labels.of(SignalQuality.FAIR))
        assertEquals("Poor", labels.of(SignalQuality.POOR))
        assertEquals("No value", labels.of(null))
    }

    @Test
    fun blockingProblemsComeFirstInTheirOriginalOrder() {
        val wifi = problem("WIFI_OFF", blocking = false)
        val location = problem("LOCATION_ENABLED", blocking = true)
        val battery = problem("BATTERY_OPTIMISATION", blocking = false)
        val storage = problem("STORAGE_FULL", blocking = true)
        val ordered = ReadinessProblems.ordered(listOf(wifi, location, battery, storage))
        assertEquals(listOf("LOCATION_ENABLED", "STORAGE_FULL", "WIFI_OFF", "BATTERY_OPTIMISATION"), ordered.map { it.id })
    }

    @Test
    fun startAnywayOnlyWhenNothingBlocks() {
        assertTrue(ReadinessProblems.canStartAnyway(emptyList()))
        assertTrue(ReadinessProblems.canStartAnyway(listOf(problem("WIFI_OFF", false), problem("SIM_PRESENT", false))))
        assertFalse(ReadinessProblems.canStartAnyway(listOf(problem("WIFI_OFF", false), problem("NO_CONSENT", true))))
    }

    private fun problem(id: String, blocking: Boolean) =
        ReadinessProblem(id = id, title = id, detail = "detail", blocking = blocking)
}
