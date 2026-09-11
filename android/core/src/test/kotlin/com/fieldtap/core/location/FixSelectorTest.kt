package com.fieldtap.core.location

import com.fieldtap.format.FixProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FixSelectorTest {

    @Test
    fun gpsFixesInTimeOrderAreAccepted() {
        val selector = FixSelector()
        assertTrue(selector.accept(fix(1_000)))
        assertTrue(selector.accept(fix(2_000)))
        assertTrue(selector.accept(fix(2_001)))
    }

    @Test
    fun mockFixesAreRejectedInReleaseAndAcceptedInDebug() {
        val release = FixSelector(allowMock = false)
        assertFalse(release.accept(fix(1_000, mock = true)))
        // The rejected mock fix did not become the reference: a real fix at the same time passes.
        assertTrue(release.accept(fix(1_000)))

        val debug = FixSelector(allowMock = true)
        assertTrue(debug.accept(fix(1_000, mock = true)))
    }

    @Test
    fun nullIslandIsRejectedButItsNeighboursAreNot() {
        val selector = FixSelector()
        assertFalse(selector.accept(fix(1_000, lat = 0.0, lon = 0.0)))
        assertTrue(selector.accept(fix(2_000, lat = 0.0, lon = 0.5)))
        assertTrue(selector.accept(fix(3_000, lat = 0.5, lon = 0.0)))
    }

    @Test
    fun positionsOutOfRangeOrNotANumberAreRejected() {
        val selector = FixSelector()
        assertFalse(selector.accept(fix(1_000, lat = 90.0001)))
        assertFalse(selector.accept(fix(1_000, lat = -90.5)))
        assertFalse(selector.accept(fix(1_000, lon = 180.1)))
        assertFalse(selector.accept(fix(1_000, lon = -180.0001)))
        assertFalse(selector.accept(fix(1_000, lat = Double.NaN)))
        assertFalse(selector.accept(fix(1_000, lon = Double.POSITIVE_INFINITY)))
        assertTrue("range bounds are inclusive", selector.accept(fix(1_000, lat = 90.0, lon = -180.0)))
    }

    @Test
    fun aFixNotLaterThanTheLastAcceptedIsRejected() {
        val selector = FixSelector()
        assertTrue(selector.accept(fix(2_000)))
        assertFalse("same time from a second provider", selector.accept(fix(2_000, provider = FixProvider.FUSED)))
        assertFalse("same time again", selector.accept(fix(2_000)))
        assertFalse("earlier", selector.accept(fix(1_999)))
        assertTrue(selector.accept(fix(2_001)))
    }

    @Test
    fun fallbackProvidersWaitMoreThanThreeSecondsAfterTheLastGpsFix() {
        val selector = FixSelector()
        assertTrue(selector.accept(fix(10_000)))
        assertFalse(selector.accept(fix(11_000, provider = FixProvider.FUSED)))
        assertFalse("exactly 3 s is still within", selector.accept(fix(13_000, provider = FixProvider.NETWORK)))
        assertTrue(selector.accept(fix(13_001, provider = FixProvider.FUSED)))
        assertTrue(selector.accept(fix(14_001, provider = FixProvider.NETWORK)))
    }

    @Test
    fun fallbackProvidersAreAcceptedBeforeAnyGpsFix() {
        val selector = FixSelector()
        assertTrue(selector.accept(fix(1_000, provider = FixProvider.FUSED)))
        assertTrue(selector.accept(fix(2_000, provider = FixProvider.NETWORK)))
    }

    @Test
    fun gpsTakesOverAgainAfterAFallback() {
        val selector = FixSelector()
        assertTrue(selector.accept(fix(10_000)))
        assertTrue(selector.accept(fix(13_001, provider = FixProvider.FUSED)))
        assertFalse("a GPS fix older than the accepted fallback", selector.accept(fix(13_000)))
        assertTrue(selector.accept(fix(13_500)))
        assertFalse(selector.accept(fix(14_000, provider = FixProvider.FUSED)))
        assertTrue(selector.accept(fix(14_500)))
    }

    @Test
    fun theFallbackDelayIsConfigurable() {
        val selector = FixSelector(fallbackAfterMs = 0)
        assertTrue(selector.accept(fix(1_000)))
        assertFalse(selector.accept(fix(1_000, provider = FixProvider.FUSED)))
        assertTrue(selector.accept(fix(1_001, provider = FixProvider.FUSED)))
    }

    @Test
    fun aNegativeFallbackDelayIsAProgrammingError() {
        assertThrows(IllegalArgumentException::class.java) { FixSelector(fallbackAfterMs = -1) }
    }
}
