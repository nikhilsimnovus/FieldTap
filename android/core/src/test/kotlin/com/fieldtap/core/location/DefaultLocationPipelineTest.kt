package com.fieldtap.core.location

import com.fieldtap.core.input.FixSample
import com.fieldtap.core.privacy.PrivacyZone
import com.fieldtap.core.privacy.PrivacyZoneGate
import com.fieldtap.format.EventKind
import com.fieldtap.format.EventRat
import com.fieldtap.format.FixProvider
import com.fieldtap.format.LatLon
import com.fieldtap.format.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultLocationPipelineTest {

    private val metresPerDegree = 6_371_000.0 * Math.PI / 180.0

    /** A 100 m zone; the walk below passes through its centre going north at 10 m/s. */
    private val zone = PrivacyZone(id = "zone-1", label = "Home", lat = 38.8900, lon = -77.0300, radiusM = 100.0)

    private val startElapsedMs = 1_000_000L

    /** Starts 333.6 m south of the centre: seconds 24 to 43 are inside, 23 and 44 are 3.6 m and 6.4 m outside. */
    private fun walk(second: Int): FixSample = fix(
        elapsedMs = startElapsedMs + second * 1_000L,
        lat = 38.8870 + second * 10.0 / metresPerDegree,
        lon = -77.0300,
        accuracyM = null,
    )

    private fun positionOf(fix: FixSample) = LatLon(fix.lat, fix.lon)

    @Test
    fun walkingThroughAZoneWritesTwoPrivacyEventsAndNoTrackInside() {
        val pipeline = DefaultLocationPipeline(listOf(zone))
        val steps = (0..66).map { second -> second to pipeline.onFix(walk(second)) }

        val events = steps.flatMap { it.second.events }
        assertEquals("only privacy_zone events", listOf(EventKind.PRIVACY_ZONE, EventKind.PRIVACY_ZONE), events.map { it.kind })
        assertEquals(PrivacyZoneGate.PAUSED_TITLE, events[0].title)
        assertEquals(walk(24).observedWallMs, events[0].timeUtcMs)
        assertEquals(PrivacyZoneGate.RESUMED_TITLE, events[1].title)
        assertEquals(walk(44).observedWallMs, events[1].timeUtcMs)
        for (event in events) {
            assertEquals(EventRat.NONE, event.rat)
            assertEquals(Severity.INFO, event.severity)
            assertNull(event.detail)
            assertNull(event.pci)
            assertNull(event.arfcn)
            assertNull(event.cause)
            assertFalse(event.title.contains(zone.label))
        }

        for ((second, step) in steps) {
            if (second in 24..43) assertNull("no track row at second $second", step.track)
            else assertNotNull("track row at second $second", step.track)
        }
        assertEquals(listOf(24, 44), steps.filter { it.second.pauseChanged }.map { it.first })
        assertEquals(1, pipeline.zonePauses)
        assertFalse(pipeline.paused)
    }

    @Test
    fun whileInsideTheZoneLoggingIsPausedAndTheLastFixIsTheLastOneOutside() {
        val pipeline = DefaultLocationPipeline(listOf(zone))
        for (second in 0..30) pipeline.onFix(walk(second))
        assertTrue(pipeline.paused)
        assertEquals(1, pipeline.zonePauses)
        assertEquals(walk(23), pipeline.lastFix())
    }

    @Test
    fun joinsNeverUseAFixFromInsideTheZone() {
        val pipeline = DefaultLocationPipeline(listOf(zone))
        for (second in 0..66) pipeline.onFix(walk(second))
        val inside = (24..43).map { positionOf(walk(it)) }.toSet()

        for (tenth in 0..660) {
            val position = pipeline.joinFinal(startElapsedMs + tenth * 100L)
            if (position != null) assertFalse("measurement at ${tenth / 10.0} s took an inside fix", position in inside)
        }
        assertEquals(positionOf(walk(23)), pipeline.joinFinal(startElapsedMs + 25_000))
        assertNull("10 s from both edges", pipeline.joinFinal(startElapsedMs + 33_500))
        assertEquals(positionOf(walk(44)), pipeline.joinFinal(startElapsedMs + 42_000))
    }

    @Test
    fun aRowWaitingAtTheZoneEdgeResolvesFromTheLastOutsideFix() {
        val pipeline = DefaultLocationPipeline(listOf(zone))
        for (second in 0..23) pipeline.onFix(walk(second))
        val measured = startElapsedMs + 23_400
        assertEquals(JoinResult.Pending, pipeline.join(measured, nowElapsedMs = startElapsedMs + 23_900))
        for (second in 24..29) pipeline.onFix(walk(second))
        assertEquals("inside fixes do not end the wait", JoinResult.Pending, pipeline.join(measured, measured + 6_499))
        assertEquals(JoinResult.Resolved(positionOf(walk(23))), pipeline.join(measured, measured + 6_500))
    }

    @Test
    fun gpsRestoredByAFixOutsideEveryZoneIsWritten() {
        val pipeline = DefaultLocationPipeline(listOf(zone))
        pipeline.onFix(walk(0))
        val lost = pipeline.onTick(nowWallMs = 42L, nowElapsedMs = walk(0).observedElapsedMs + 5_001)
        assertEquals(listOf(EventKind.GPS_LOST), lost.map { it.kind })
        assertEquals(42L, lost.single().timeUtcMs)

        val step = pipeline.onFix(walk(10))
        assertEquals(listOf(EventKind.GPS_RESTORED), step.events.map { it.kind })
        assertNotNull(step.track)
        assertFalse(step.pauseChanged)
    }

    @Test
    fun gpsRestoredByAFixInsideAZoneIsNotWritten() {
        val pipeline = DefaultLocationPipeline(listOf(zone))
        pipeline.onFix(walk(0))
        assertEquals(1, pipeline.onTick(nowWallMs = 1L, nowElapsedMs = walk(0).observedElapsedMs + 5_001).size)

        val step = pipeline.onFix(walk(30))
        assertEquals(listOf(EventKind.PRIVACY_ZONE), step.events.map { it.kind })
        assertNull(step.track)
        assertTrue(step.pauseChanged)
    }

    @Test
    fun onResumeThePrivacyEventComesBeforeGpsRestored() {
        val pipeline = DefaultLocationPipeline(listOf(zone))
        pipeline.onFix(walk(0))
        pipeline.onFix(walk(30))
        assertTrue(pipeline.paused)
        val lost = pipeline.onTick(nowWallMs = 1L, nowElapsedMs = walk(30).observedElapsedMs + 5_001)
        assertEquals("returned while paused; the recorder drops it", listOf(EventKind.GPS_LOST), lost.map { it.kind })

        val step = pipeline.onFix(walk(50))
        assertEquals(listOf(EventKind.PRIVACY_ZONE, EventKind.GPS_RESTORED), step.events.map { it.kind })
        assertEquals(PrivacyZoneGate.RESUMED_TITLE, step.events[0].title)
        assertNotNull(step.track)
        assertTrue(step.pauseChanged)
    }

    @Test
    fun beforeAnyFixLoggingIsNotPausedButWithZonesWritesWait() {
        val pipeline = DefaultLocationPipeline(listOf(zone))
        assertFalse(pipeline.paused)
        assertTrue("nothing shows yet whether the phone is in the zone", pipeline.holding)
        assertEquals(0, pipeline.zonePauses)
        assertNull(pipeline.lastFix())
        assertTrue(pipeline.onTick(nowWallMs = 1L, nowElapsedMs = 999_999_999L).isEmpty())
        assertEquals(JoinResult.Pending, pipeline.join(measurementElapsedMs = 0, nowElapsedMs = 6_499))
        assertEquals(JoinResult.Resolved(null), pipeline.join(measurementElapsedMs = 0, nowElapsedMs = 6_500))

        assertFalse("without zones nothing ever waits", DefaultLocationPipeline(emptyList()).holding)
    }

    @Test
    fun aFixWhoseAccuracyReachesIntoTheZoneIsNeitherATrackRowNorAJoinCandidate() {
        val pipeline = DefaultLocationPipeline(listOf(zone))
        val far = fix(elapsedMs = startElapsedMs, lat = 38.8800, lon = -77.0300)
        val farStep = pipeline.onFix(far)
        assertTrue(farStep.confirmsOutside)
        assertNotNull(farStep.track)
        assertFalse(pipeline.holding)

        // A network fix 180 m from the centre, good to 150 m, more than 3 s after the last GPS fix.
        val coarse = fix(
            elapsedMs = startElapsedMs + 10_000,
            lat = 38.8900 + 180.0 / metresPerDegree,
            lon = -77.0300,
            provider = FixProvider.NETWORK,
            accuracyM = 150.0,
        )
        assertEquals(LocationStep(track = null, events = emptyList(), pauseChanged = false), pipeline.onFix(coarse))
        assertFalse(pipeline.paused)
        assertTrue(pipeline.holding)
        assertEquals(far, pipeline.lastFix())
        assertEquals(JoinResult.Resolved(null), pipeline.join(startElapsedMs + 10_000, nowElapsedMs = startElapsedMs + 20_000))

        assertEquals(listOf(EventKind.GPS_LOST), pipeline.onTick(1L, coarse.observedElapsedMs + 60_000).map { it.kind })
        val paused = pipeline.onTick(2L, coarse.observedElapsedMs + 60_001)
        assertEquals(listOf(PrivacyZoneGate.PAUSED_NO_FIX_TITLE), paused.map { it.title })
        assertTrue(pipeline.paused)
        assertTrue(pipeline.pausedWithoutFix)
    }

    @Test
    fun aRejectedFixProducesNothing() {
        val release = DefaultLocationPipeline(emptyList(), allowMockFixes = false)
        val expected = LocationStep(track = null, events = emptyList(), pauseChanged = false)
        assertEquals(expected, release.onFix(fix(1_000, mock = true)))
        assertEquals(expected, release.onFix(fix(2_000, lat = 0.0, lon = 0.0)))
        assertNull(release.lastFix())
        assertNull(release.joinFinal(1_000))

        val debug = DefaultLocationPipeline(emptyList(), allowMockFixes = true)
        val mock = fix(1_000, mock = true)
        assertNotNull(debug.onFix(mock).track)
        assertEquals(mock, debug.lastFix())
    }

    @Test
    fun theZonesAreThoseGivenAtConstruction() {
        val zones = mutableListOf<PrivacyZone>()
        val pipeline = DefaultLocationPipeline(zones)
        zones += zone
        val step = pipeline.onFix(walk(30))
        assertNotNull(step.track)
        assertFalse(pipeline.paused)
    }

    @Test
    fun trackRowsComeFromAcceptedFixesOutsideZones() {
        val pipeline = DefaultLocationPipeline(emptyList())
        val first = walk(0)
        val row = requireNotNull(pipeline.onFix(first).track) { "the first fix is a track row" }
        assertEquals(TrackRows.of(first), row)
        assertNull("a duplicate time is rejected", pipeline.onFix(walk(0)).track)
        assertEquals(first, pipeline.lastFix())
    }
}
