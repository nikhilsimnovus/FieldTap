package com.fieldtap.core.privacy

import com.fieldtap.core.location.fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyZonesTest {

    private val metresPerDegree = PrivacyZones.EARTH_RADIUS_M * Math.PI / 180.0

    private val zone = PrivacyZone(id = "zone-1", label = "Office", lat = 51.5007, lon = -0.1246, radiusM = 100.0)

    /** A fix [metres] due north of [zone]'s centre. */
    private fun north(metres: Double, accuracyM: Double? = null) =
        fix(elapsedMs = 1_000, lat = zone.lat + metres / metresPerDegree, lon = zone.lon, accuracyM = accuracyM)

    @Test
    fun oneDegreeOfLatitudeOrOfLongitudeAtTheEquatorIsAboutOneHundredElevenKilometres() {
        assertEquals(metresPerDegree, PrivacyZones.distanceM(0.0, 0.0, 1.0, 0.0), 1e-6)
        assertEquals(metresPerDegree, PrivacyZones.distanceM(0.0, 0.0, 0.0, 1.0), 1e-6)
        assertEquals(111_194.93, metresPerDegree, 0.01)
    }

    @Test
    fun distanceIsSymmetricAndZeroForTheSamePoint() {
        val a = PrivacyZones.distanceM(38.8895, -77.0353, 38.8893, -77.0502)
        val b = PrivacyZones.distanceM(38.8893, -77.0502, 38.8895, -77.0353)
        assertEquals(a, b, 1e-9)
        assertEquals(0.0, PrivacyZones.distanceM(38.8895, -77.0353, 38.8895, -77.0353), 0.0)
        assertTrue(a > 1_280 && a < 1_300)
    }

    @Test
    fun distanceIsShortAcrossTheAntimeridian() {
        assertEquals(metresPerDegree * 0.001, PrivacyZones.distanceM(0.0, 179.9995, 0.0, -179.9995), 1e-6)
    }

    @Test
    fun aFixOnTheRadiusIsInsideAndJustBeyondItIsOutside() {
        assertTrue(PrivacyZones.contains(listOf(zone), north(99.9)))
        assertTrue(PrivacyZones.contains(listOf(zone), north(0.0)))
        assertFalse(PrivacyZones.contains(listOf(zone), north(100.1)))
    }

    @Test
    fun accuracyWidensTheZoneByAtMostFiftyMetres() {
        assertTrue(PrivacyZones.contains(listOf(zone), north(119.9, accuracyM = 20.0)))
        assertFalse(PrivacyZones.contains(listOf(zone), north(120.1, accuracyM = 20.0)))
        assertTrue(PrivacyZones.contains(listOf(zone), north(149.9, accuracyM = 500.0)))
        assertFalse(PrivacyZones.contains(listOf(zone), north(150.1, accuracyM = 500.0)))
        assertFalse(PrivacyZones.contains(listOf(zone), north(150.1, accuracyM = Double.POSITIVE_INFINITY)))
    }

    @Test
    fun anUnusableAccuracyErrsTowardsPrivacyOrCountsAsNone() {
        assertTrue("NaN takes the full margin", PrivacyZones.contains(listOf(zone), north(149.9, accuracyM = Double.NaN)))
        assertFalse("negative counts as none", PrivacyZones.contains(listOf(zone), north(100.1, accuracyM = -30.0)))
        assertFalse("unknown counts as none", PrivacyZones.contains(listOf(zone), north(100.1, accuracyM = null)))
    }

    @Test
    fun anyZoneCountsAndNoZonesNeverContain() {
        val far = zone.copy(id = "zone-2", lat = -33.8568, lon = 151.2153)
        assertTrue(PrivacyZones.contains(listOf(far, zone), north(10.0)))
        assertFalse(PrivacyZones.contains(listOf(far), north(10.0)))
        assertFalse(PrivacyZones.contains(emptyList(), north(0.0)))
    }

    @Test
    fun aValidZoneHasNoProblems() {
        assertEquals(emptyList<String>(), PrivacyZones.validate(zone))
        assertEquals(emptyList<String>(), PrivacyZones.validate(zone.copy(radiusM = PrivacyZones.MIN_RADIUS_M)))
        assertEquals(emptyList<String>(), PrivacyZones.validate(zone.copy(radiusM = PrivacyZones.MAX_RADIUS_M)))
        assertEquals(emptyList<String>(), PrivacyZones.validate(zone.copy(lat = 90.0, lon = -180.0)))
        assertEquals(emptyList<String>(), PrivacyZones.validate(zone.copy(lat = 0.0, lon = 0.001)))
    }

    @Test
    fun aBlankLabelIsAProblem() {
        assertEquals(listOf("Give the zone a name."), PrivacyZones.validate(zone.copy(label = " \t")))
    }

    @Test
    fun theRadiusMustBeBetweenFiftyAndFiveThousandMetres() {
        val message = listOf("Radius must be between 50 m and 5000 m.")
        assertEquals(message, PrivacyZones.validate(zone.copy(radiusM = 49.9)))
        assertEquals(message, PrivacyZones.validate(zone.copy(radiusM = 5_000.1)))
        assertEquals(message, PrivacyZones.validate(zone.copy(radiusM = Double.NaN)))
        assertEquals(message, PrivacyZones.validate(zone.copy(radiusM = Double.POSITIVE_INFINITY)))
        assertEquals(message, PrivacyZones.validate(zone.copy(radiusM = -100.0)))
    }

    @Test
    fun coordinatesMustBeInRange() {
        assertEquals(listOf("Latitude must be between -90 and 90."), PrivacyZones.validate(zone.copy(lat = 90.01)))
        assertEquals(listOf("Latitude must be between -90 and 90."), PrivacyZones.validate(zone.copy(lat = Double.NaN)))
        assertEquals(listOf("Longitude must be between -180 and 180."), PrivacyZones.validate(zone.copy(lon = -180.01)))
    }

    @Test
    fun nullIslandIsAProblem() {
        val problems = PrivacyZones.validate(zone.copy(lat = 0.0, lon = 0.0))
        assertEquals(1, problems.size)
        assertTrue(problems.single().startsWith("0, 0 is not a real position"))
    }

    @Test
    fun everyProblemIsListedInFieldOrder() {
        val problems = PrivacyZones.validate(PrivacyZone(id = "z", label = "", lat = 91.0, lon = 200.0, radiusM = 10.0))
        assertEquals(
            listOf(
                "Give the zone a name.",
                "Latitude must be between -90 and 90.",
                "Longitude must be between -180 and 180.",
                "Radius must be between 50 m and 5000 m.",
            ),
            problems,
        )
    }
}
