package com.fieldtap.ui.settings

import com.fieldtap.core.privacy.PrivacyZone
import com.fieldtap.core.privacy.PrivacyZones
import com.fieldtap.ui.setup.SetupSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneDraftsTest {

    @Test
    fun decimalsAcceptAPointOrACommaAndEitherMinusSign() {
        assertEquals(52.52, ZoneDrafts.parseDecimal("52.52"), 0.0)
        assertEquals(-33.8688, ZoneDrafts.parseDecimal(" -33,8688 "), 0.0)
        assertEquals(-12.5, ZoneDrafts.parseDecimal("−12.5"), 0.0)
        assertEquals(7.0, ZoneDrafts.parseDecimal("+7"), 0.0)
        assertEquals(0.5, ZoneDrafts.parseDecimal(".5"), 0.0)
        assertEquals(5.0, ZoneDrafts.parseDecimal("5."), 0.0)
    }

    @Test
    fun anythingElseIsNotANumber() {
        listOf("", " ", "abc", "1e3", "NaN", "Infinity", "-", ".", "1,000.5", "1.2.3", "0x10", "--1").forEach { text ->
            assertTrue(text, ZoneDrafts.parseDecimal(text).isNaN())
        }
    }

    @Test
    fun aNewDraftHasTheDefaultRadiusAndNoPosition() {
        assertEquals(ZoneDraft(id = null, label = "", radius = "200", lat = "", lon = ""), ZoneDraft.blank())
    }

    @Test
    fun anExistingZoneIsShownForEditingWithSixDecimals() {
        val zone = PrivacyZone(id = "home", label = "Home", lat = 52.5200081, lon = 13.404954, radiusM = 150.55)

        assertEquals(ZoneDraft(id = "home", label = "Home", radius = "150.6", lat = "52.520008", lon = "13.404954"), ZoneDraft.of(zone))
    }

    @Test
    fun toZoneTrimsTheNameKeepsTheIdAndLeavesUnreadableNumbersToValidation() {
        val zone = ZoneDraft(id = null, label = "  Home ", radius = "abc", lat = "52,52", lon = "").toZone { "generated" }

        assertEquals("generated", zone.id)
        assertEquals("Home", zone.label)
        assertEquals(52.52, zone.lat, 0.0)
        assertTrue(zone.lon.isNaN())
        assertTrue(zone.radiusM.isNaN())
        assertEquals(
            listOf("Longitude must be between -180 and 180.", "Radius must be between 50 m and 5000 m."),
            PrivacyZones.validate(zone),
        )

        val edited = ZoneDraft(id = "home", label = "Home", radius = "200", lat = "52.52", lon = "13.4")
        assertEquals("home", edited.toZone { throw AssertionError("an existing zone keeps its id") }.id)
        assertEquals(emptyList<String>(), PrivacyZones.validate(edited.toZone { "unused" }))
    }

    @Test
    fun withPositionWritesTheFixWithSixDecimals() {
        val draft = ZoneDraft.blank().copy(label = "Hotel").withPosition(lat = -33.86882, lon = 151.20929)

        assertEquals(ZoneDraft(id = null, label = "Hotel", radius = "200", lat = "-33.868820", lon = "151.209290"), draft)
    }

    @Test
    fun theRadiusFallsBackToTheDefaultWhileUnreadable() {
        assertEquals(350.0, ZoneDrafts.radiusOrDefault("350"), 0.0)
        assertEquals(ZoneDrafts.DEFAULT_RADIUS_M, ZoneDrafts.radiusOrDefault(""), 0.0)
        assertEquals(ZoneDrafts.DEFAULT_RADIUS_M, ZoneDrafts.radiusOrDefault("far"), 0.0)
    }

    @Test
    fun aFixIsRecentForOneMinute() {
        val fix = SetupSamples.fix(elapsedMs = 40_000)
        assertTrue(ZoneDrafts.isRecent(fix, nowElapsedMs = 40_000))
        assertTrue(ZoneDrafts.isRecent(fix, nowElapsedMs = 100_000))
        assertFalse(ZoneDrafts.isRecent(fix, nowElapsedMs = 100_001))
        assertTrue("a fix stamped just after now is not old", ZoneDrafts.isRecent(fix, nowElapsedMs = 39_900))
    }

    @Test
    fun upsertReplacesInPlaceOrAppends() {
        val a = PrivacyZone(id = "a", label = "A", lat = 1.0, lon = 1.0, radiusM = 100.0)
        val b = PrivacyZone(id = "b", label = "B", lat = 2.0, lon = 2.0, radiusM = 100.0)
        val c = PrivacyZone(id = "c", label = "C", lat = 3.0, lon = 3.0, radiusM = 100.0)

        assertEquals(listOf(a, b, c), ZoneDrafts.upsert(listOf(a, b), c))
        val renamed = b.copy(label = "B2")
        assertEquals(listOf(a, renamed, c), ZoneDrafts.upsert(listOf(a, b, c), renamed))
        assertEquals(listOf(a), ZoneDrafts.upsert(emptyList(), a))
    }
}
