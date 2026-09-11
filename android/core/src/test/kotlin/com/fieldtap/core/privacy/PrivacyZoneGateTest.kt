package com.fieldtap.core.privacy

import com.fieldtap.core.location.fix
import com.fieldtap.format.EventKind
import com.fieldtap.format.EventRat
import com.fieldtap.format.EventRow
import com.fieldtap.format.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyZoneGateTest {

    private val zone = PrivacyZone(
        id = "7e0c8f5a-0b7e-4d2b-9a55-3f0c2d1e4b6a",
        label = "Mum's flat, 12 Elm Road",
        lat = 38.8977,
        lon = -77.0365,
        radiusM = 150.0,
    )

    private fun inside(elapsedMs: Long) = fix(elapsedMs, lat = zone.lat, lon = zone.lon)

    private fun outside(elapsedMs: Long) = fix(elapsedMs, lat = zone.lat + 0.01, lon = zone.lon)

    @Test
    fun loggingIsNotPausedBeforeAnyFix() {
        val gate = PrivacyZoneGate(listOf(zone))
        assertFalse(gate.paused)
        assertEquals(0, gate.pauses)
    }

    @Test
    fun aFixOutsideWhileNotPausedChangesNothing() {
        val gate = PrivacyZoneGate(listOf(zone))
        assertNull(gate.onFix(outside(1_000)))
        assertFalse(gate.paused)
        assertEquals(0, gate.pauses)
    }

    @Test
    fun theFirstFixInsidePausesCountsAndSaysNothingAboutThePlace() {
        val gate = PrivacyZoneGate(listOf(zone))
        val entering = inside(1_000)
        val event = gate.onFix(entering)
        assertEquals(
            EventRow(
                timeUtcMs = entering.observedWallMs,
                rat = EventRat.NONE,
                kind = EventKind.PRIVACY_ZONE,
                severity = Severity.INFO,
                title = "Logging paused in a privacy zone",
                detail = null,
                pci = null,
                arfcn = null,
                cause = null,
            ),
            event,
        )
        assertTrue(gate.paused)
        assertEquals(1, gate.pauses)
        assertNull("still inside", gate.onFix(inside(2_000)))
        assertEquals(1, gate.pauses)
    }

    @Test
    fun theFirstFixOutsideEveryZoneResumes() {
        val gate = PrivacyZoneGate(listOf(zone))
        gate.onFix(inside(1_000))
        val leaving = outside(2_000)
        val event = gate.onFix(leaving)!!
        assertEquals(EventKind.PRIVACY_ZONE, event.kind)
        assertEquals("Logging resumed", event.title)
        assertEquals(leaving.observedWallMs, event.timeUtcMs)
        assertNull(event.detail)
        assertFalse(gate.paused)
        assertEquals(1, gate.pauses)
        assertNull(gate.onFix(outside(3_000)))
    }

    @Test
    fun aSecondVisitCountsASecondPause() {
        val gate = PrivacyZoneGate(listOf(zone))
        gate.onFix(inside(1_000))
        gate.onFix(outside(2_000))
        gate.onFix(inside(3_000))
        assertTrue(gate.paused)
        assertEquals(2, gate.pauses)
    }

    @Test
    fun eventsNeverNameOrLocateTheZone() {
        val gate = PrivacyZoneGate(listOf(zone))
        val events = listOfNotNull(gate.onFix(inside(1_000)), gate.onFix(outside(2_000)))
        assertEquals(2, events.size)
        for (event in events) {
            val text = listOfNotNull(event.title, event.detail, event.cause).joinToString(" ")
            assertFalse(text.contains("Elm"))
            assertFalse(text.contains("Mum"))
            assertFalse(text.contains(zone.id))
            assertFalse(text.contains("38.89"))
            assertFalse(text.contains("77.03"))
            assertFalse(Regex("[0-9]").containsMatchIn(text))
        }
    }

    @Test
    fun withoutZonesNothingEverPauses() {
        val gate = PrivacyZoneGate(emptyList())
        assertNull(gate.onFix(inside(1_000)))
        assertFalse(gate.paused)
    }

    @Test
    fun theZonesAreCopiedAtConstruction() {
        val zones = mutableListOf(zone)
        val gate = PrivacyZoneGate(zones)
        zones.clear()
        assertTrue(gate.onFix(inside(1_000)) != null)
        assertTrue(gate.paused)
    }
}
