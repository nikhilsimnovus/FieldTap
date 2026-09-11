package com.fieldtap.core.privacy

import com.fieldtap.core.location.fix
import com.fieldtap.format.EventKind
import com.fieldtap.format.EventRat
import com.fieldtap.format.EventRow
import com.fieldtap.format.Severity
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val METRES_PER_DEGREE: Double = PrivacyZones.EARTH_RADIUS_M * PI / 180.0

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

    private fun north(elapsedMs: Long, metres: Double, accuracyM: Double? = 5.0) =
        fix(elapsedMs, lat = zone.lat + metres / METRES_PER_DEGREE, lon = zone.lon, accuracyM = accuracyM)

    @Test
    fun placementCountsAccuracyTowardInsideUpToFiftyMetresAndTowardAmbiguousInFull() {
        val zones = listOf(zone)
        assertEquals(ZonePlacement.INSIDE, PrivacyZones.placement(zones, north(0, 199.0, accuracyM = 60.0)))
        assertEquals(ZonePlacement.AMBIGUOUS, PrivacyZones.placement(zones, north(0, 201.0, accuracyM = 60.0)))
        // A network fix 330 m away that is only good to 300 m: the phone may be at the centre.
        assertEquals(ZonePlacement.AMBIGUOUS, PrivacyZones.placement(zones, north(0, 330.0, accuracyM = 300.0)))
        assertEquals(ZonePlacement.NEAR, PrivacyZones.placement(zones, north(0, 399.0)))
        assertEquals(ZonePlacement.CLEAR, PrivacyZones.placement(zones, north(0, 401.0)))
        assertEquals(ZonePlacement.AMBIGUOUS, PrivacyZones.placement(zones, north(0, 1_000.0, accuracyM = Double.NaN)))
        assertEquals(ZonePlacement.CLEAR, PrivacyZones.placement(emptyList(), north(0, 0.0)))
        assertFalse(PrivacyZones.contains(zones, north(0, 201.0, accuracyM = 60.0)))
    }

    @Test
    fun withZonesWritesWaitFromTheStartUntilAFixShowsThePhoneClearOfThem() {
        val gate = PrivacyZoneGate(listOf(zone), startElapsedMs = 500)
        assertTrue(gate.holding)
        assertNull(gate.outsideUntilMs)
        assertEquals(0L, gate.holdAgeMs(5_000))
        assertNull(gate.onFix(outside(1_000)))
        assertFalse(gate.holding)
        // 955 m from the edge at walking speed: no zone is within 5 s, so inputs up to 5 s after the fix are outside.
        assertEquals(6_000L, gate.outsideUntilMs)
        assertNull(gate.holdAgeMs(5_000))
        assertEquals(1_000L, gate.holdAgeMs(7_000))

        assertNull(gate.onFix(north(2_000, 300.0)))
        assertEquals(ZonePlacement.NEAR, gate.lastPlacement)
        // 145 m from the edge a zone is within 5 s: only what the fix itself places is outside.
        assertEquals(2_000L, gate.outsideUntilMs)
        assertTrue("near a zone the next fix decides", gate.holding)
        assertNull(gate.onFix(outside(3_000)))
        assertFalse(gate.holding)

        val none = PrivacyZoneGate(emptyList())
        assertFalse(none.holding)
        assertEquals(Long.MAX_VALUE, none.outsideUntilMs)
    }

    @Test
    fun aFixOutsideAfterAStretchInWhichAZoneWasWithinReachPausesUntilTwoFixesCloseTogether() {
        val gate = PrivacyZoneGate(listOf(zone), startElapsedMs = 0)
        assertNull(gate.onFix(outside(1_000)))

        // 50 m from the edge, 20 s later: walking speed or not, the phone could have been in the zone and back.
        val back = north(21_000, 200.0)
        val paused = gate.onFix(back)

        assertEquals(
            EventRow(back.observedWallMs, EventRat.NONE, EventKind.PRIVACY_ZONE, Severity.INFO, PrivacyZoneGate.PAUSED_NO_FIX_TITLE, PrivacyZoneGate.NO_FIX_DETAIL),
            paused,
        )
        assertTrue(gate.pausedWithoutFix)
        assertNull(gate.outsideUntilMs)
        assertEquals("not counted: no fix showed the phone inside", 0, gate.pauses)

        // One second on, still 50 m out: no time to have been inside in between, so logging resumes.
        val resumed = gate.onFix(north(22_000, 200.0))
        assertEquals(PrivacyZoneGate.RESUMED_TITLE, resumed?.title)
        assertFalse(gate.paused)
        assertEquals(0, gate.pauses)
    }

    @Test
    fun skirtingAZoneWithFixesTooFarApartPausesOnceAndResumesOnce() {
        val gate = PrivacyZoneGate(listOf(zone), startElapsedMs = 1_000)
        assertNull(gate.onFix(north(1_000, 160.0, accuracyM = 5.0).copy(speedMps = null)))
        // Fixes with no speed, 5 m outside the edge, every 2 s: each gap could hold a visit, but it pauses only once.
        val events = (1..5).mapNotNull { gate.onFix(north(1_000 + it * 2_000L, 160.0, accuracyM = 5.0).copy(speedMps = null)) }
        assertEquals(listOf(PrivacyZoneGate.PAUSED_NO_FIX_TITLE), events.map { it.title })
        assertTrue(gate.paused)

        // Walking away with GPS speed, 35 m and more from the edge, a fix a second: it resumes once.
        val walkingAway = (0..3).mapNotNull { gate.onFix(north(12_000 + it * 1_000L, 190.0 + 2 * it)) }
        assertEquals(listOf(PrivacyZoneGate.RESUMED_TITLE), walkingAway.map { it.title })
    }

    @Test
    fun theFirstFixOutsideAfterTheStartPausesWhenTheSessionCouldHaveStartedInsideAZone() {
        // Started indoors next to the zone; the first fix, 80 m out, comes 25 s later.
        val started = PrivacyZoneGate(listOf(zone), startElapsedMs = 0)
        assertEquals(PrivacyZoneGate.PAUSED_NO_FIX_TITLE, started.onFix(north(25_000, 230.0))?.title)
        assertEquals(PrivacyZoneGate.RESUMED_TITLE, started.onFix(north(26_000, 230.0))?.title)

        // The same fix 2 s after the start: 80 m cannot be covered from inside the zone in 2 s.
        val quick = PrivacyZoneGate(listOf(zone), startElapsedMs = 23_000)
        assertNull(quick.onFix(north(25_000, 230.0)))
        assertFalse(quick.paused)
    }

    @Test
    fun aHoldLongerThanItsLimitPausesWithoutAFixAndAFixInsideConfirmsThePause() {
        val gate = PrivacyZoneGate(listOf(zone), holdLimitMs = 60_000)
        val near = north(1_000, 300.0)
        gate.onFix(near)
        assertNull(gate.onTick(nowWallMs = 41L, nowElapsedMs = near.observedElapsedMs + 60_000))

        val paused = gate.onTick(nowWallMs = 42L, nowElapsedMs = near.observedElapsedMs + 60_001)

        assertEquals(
            EventRow(42L, EventRat.NONE, EventKind.PRIVACY_ZONE, Severity.INFO, PrivacyZoneGate.PAUSED_NO_FIX_TITLE, PrivacyZoneGate.NO_FIX_DETAIL),
            paused,
        )
        assertTrue(gate.paused)
        assertTrue(gate.pausedWithoutFix)
        assertFalse(gate.holding)
        assertEquals("not counted until a fix shows the phone inside", 0, gate.pauses)
        assertNull("the pause is written once", gate.onTick(nowWallMs = 43L, nowElapsedMs = near.observedElapsedMs + 120_000))

        assertNull("a fix inside confirms it without a second event", gate.onFix(inside(70_000)))
        assertFalse(gate.pausedWithoutFix)
        assertEquals(1, gate.pauses)
        assertEquals(PrivacyZoneGate.RESUMED_TITLE, gate.onFix(outside(80_000))?.title)
    }

    @Test
    fun aHoldThatBeganBeforeTheFirstTickIsTimedFromThatTick() {
        val gate = PrivacyZoneGate(listOf(zone), holdLimitMs = 60_000)
        assertNull(gate.onTick(1L, 500_000))
        assertNull(gate.onTick(1L, 560_000))
        assertNotNull(gate.onTick(1L, 560_001))
        assertTrue(gate.pausedWithoutFix)
    }

    @Test
    fun aFixFarFromEveryZoneStillPausesWhenNoFixFollowsWithinTheLimit() {
        // A fix 955 m from the zone's edge, then none: the phone could be anywhere within a minute's reach.
        val gate = PrivacyZoneGate(listOf(zone), holdLimitMs = 60_000)
        val far = outside(1_000)
        assertNull(gate.onFix(far))
        assertFalse(gate.holding)
        assertNull(gate.onTick(1L, far.elapsedMs + 5_000))
        assertFalse("the fix itself places the next 5 s", gate.holding)
        assertNull(gate.onTick(1L, far.elapsedMs + 6_000))
        assertTrue(gate.holding)
        assertNull(gate.onTick(1L, far.observedElapsedMs + 60_000))

        assertEquals(PrivacyZoneGate.PAUSED_NO_FIX_TITLE, gate.onTick(1L, far.observedElapsedMs + 60_001)?.title)
        assertTrue(gate.pausedWithoutFix)
    }

    @Test
    fun aFixWhoseAccuracyReachesIntoAZoneNeitherPausesNorResumesNorExtendsAHold() {
        val gate = PrivacyZoneGate(listOf(zone), holdLimitMs = 60_000)
        val outsideFix = outside(1_000)
        gate.onFix(outsideFix)
        val coarse = north(2_000, 250.0, accuracyM = 150.0)
        assertNull(gate.onFix(coarse))
        assertEquals(ZonePlacement.AMBIGUOUS, gate.lastPlacement)
        assertFalse(gate.paused)
        assertEquals("inputs after the coarse fix wait", coarse.elapsedMs, gate.outsideUntilMs)
        assertTrue(gate.holding)
        assertNull(gate.onFix(north(30_000, 250.0, accuracyM = 150.0)))
        assertNull("timed from the last fix outside every zone", gate.onTick(1L, outsideFix.observedElapsedMs + 60_000))
        assertNotNull(gate.onTick(1L, outsideFix.observedElapsedMs + 60_001))

        val paused = PrivacyZoneGate(listOf(zone))
        paused.onFix(inside(1_000))
        assertNull("a coarse fix never resumes", paused.onFix(north(10_000, 250.0, accuracyM = 150.0)))
        assertTrue(paused.paused)
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
