package com.fieldtap.platform.location

import android.location.LocationManager
import com.fieldtap.core.input.FixSample
import com.fieldtap.core.input.LocationAvailability
import com.fieldtap.format.FixProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationValuesTest {

    @Test
    fun providerNamesAreAndroidsConstantsInRequestOrder() {
        assertEquals(
            listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER),
            LocationValues.PROVIDERS.map { it.first },
        )
        assertEquals(listOf(FixProvider.GPS, FixProvider.FUSED, FixProvider.NETWORK), LocationValues.PROVIDERS.map { it.second })
    }

    @Test
    fun onlyTheThreeRequestedProvidersMapToTrackProviders() {
        assertEquals(FixProvider.GPS, LocationValues.providerOf("gps"))
        assertEquals(FixProvider.FUSED, LocationValues.providerOf("fused"))
        assertEquals(FixProvider.NETWORK, LocationValues.providerOf("network"))
        assertNull(LocationValues.providerOf(LocationManager.PASSIVE_PROVIDER))
        assertNull(LocationValues.providerOf("GPS"))
        assertNull(LocationValues.providerOf(null))
    }

    @Test
    fun elapsedRealtimeNanosBecomeMillisecondsRoundedDown() {
        assertEquals(25_323_456L, LocationValues.elapsedMillis(25_323_456_999_999L))
        assertEquals(0L, LocationValues.elapsedMillis(999_999L))
    }

    @Test
    fun aFixTimeIsTranslatedOntoTheAppsWallClock() {
        // Observed at wall 14:30:00.400 and elapsed 25 323 456; the fix is 1 000 ms older on the monotonic clock.
        assertEquals(
            1_789_050_599_400L,
            LocationValues.wallTimeOf(fixElapsedMs = 25_322_456L, observedWallMs = 1_789_050_600_400L, observedElapsedMs = 25_323_456L),
        )
        assertEquals(
            1_789_050_600_400L,
            LocationValues.wallTimeOf(fixElapsedMs = 25_323_456L, observedWallMs = 1_789_050_600_400L, observedElapsedMs = 25_323_456L),
        )
    }

    @Test
    fun aFixTimeThatIsNotTimeSinceBootTakesTheCallbacksTime() {
        // The API 31 emulator reports wall-clock nanoseconds: 57 years ahead of the callback's elapsed time.
        assertEquals(106_020L, LocationValues.fixElapsedMillis(fixElapsedNanos = 1_789_117_303_614_000_000L, observedElapsedMs = 106_020L))
        // Just past the tolerance ahead, just past the maximum age, zero: the callback's time.
        val tooFarAhead = (50_000L + LocationValues.FIX_CLOCK_TOLERANCE_MS + 1) * 1_000_000
        assertEquals(50_000L, LocationValues.fixElapsedMillis(tooFarAhead, observedElapsedMs = 50_000L))
        val tooOld = (4_000_000L - LocationValues.MAX_FIX_AGE_MS - 1) * 1_000_000
        assertEquals(4_000_000L, LocationValues.fixElapsedMillis(tooOld, observedElapsedMs = 4_000_000L))
        assertEquals(50_000L, LocationValues.fixElapsedMillis(0L, observedElapsedMs = 50_000L))
        // Plausible times stay as reported: a second old, at the tolerance ahead, exactly the maximum age.
        assertEquals(49_000L, LocationValues.fixElapsedMillis(49_000_000_000L, observedElapsedMs = 50_000L))
        assertEquals(51_000L, LocationValues.fixElapsedMillis(51_000_000_000L, observedElapsedMs = 50_000L))
        assertEquals(400_000L, LocationValues.fixElapsedMillis(400_000_000_000L, observedElapsedMs = 400_000L + LocationValues.MAX_FIX_AGE_MS))
    }

    @Test
    fun aFixWithAnInvalidTimeIsStampedWhenItArrived() {
        val fix = LocationValues.fix(
            providerName = "gps",
            fixElapsedNanos = 1_789_117_303_614_000_000L,
            lat = 12.9725050,
            lon = 77.5945983,
            accuracyM = 5.0f,
            altitudeM = 921.9,
            speedMps = 0.0f,
            mock = false,
            observedWallMs = 1_789_117_303_700L,
            observedElapsedMs = 106_020L,
        )

        requireNotNull(fix)
        assertEquals(106_020L, fix.elapsedMs)
        assertEquals(1_789_117_303_700L, fix.wallMs)
    }

    @Test
    fun floatsBecomeTheDecimalTheyPrintAs() {
        assertEquals(4.7, LocationValues.decimal(4.7f), 0.0)
        assertEquals(0.25, LocationValues.decimal(0.25f), 0.0)
        assertEquals(3.0, LocationValues.decimal(3.0f), 0.0)
        assertEquals(12.345, LocationValues.decimal(12.345f), 0.0)
    }

    @Test
    fun aGpsFixBecomesAFixSampleOnTheAppsClocks() {
        val fix = LocationValues.fix(
            providerName = "gps",
            fixElapsedNanos = 25_322_456_789_000L,
            lat = 47.6205063,
            lon = -122.3492774,
            accuracyM = 4.7f,
            altitudeM = 56.3,
            speedMps = 1.4f,
            mock = false,
            observedWallMs = 1_789_050_600_400L,
            observedElapsedMs = 25_323_456L,
        )

        assertEquals(
            FixSample(
                elapsedMs = 25_322_456L,
                wallMs = 1_789_050_599_400L,
                lat = 47.6205063,
                lon = -122.3492774,
                accuracyM = 4.7,
                altitudeM = 56.3,
                speedMps = 1.4,
                provider = FixProvider.GPS,
                mock = false,
                observedWallMs = 1_789_050_600_400L,
                observedElapsedMs = 25_323_456L,
            ),
            fix,
        )
    }

    @Test
    fun valuesAndroidDoesNotHaveStayNullAndMockIsPassedOn() {
        val fix = LocationValues.fix(
            providerName = "network",
            fixElapsedNanos = 1_000_000_000L,
            lat = 1.5,
            lon = 2.5,
            accuracyM = null,
            altitudeM = null,
            speedMps = null,
            mock = true,
            observedWallMs = 10_000L,
            observedElapsedMs = 1_000L,
        )

        requireNotNull(fix)
        assertNull(fix.accuracyM)
        assertNull(fix.altitudeM)
        assertNull(fix.speedMps)
        assertEquals(FixProvider.NETWORK, fix.provider)
        assertTrue(fix.mock)
        assertEquals(1_000L, fix.elapsedMs)
        assertEquals(10_000L, fix.wallMs)
    }

    @Test
    fun aFusedFixKeepsItsProvider() {
        val fix = LocationValues.fix("fused", 2_000_000_000L, 1.0, 1.0, 12.0f, null, 0.0f, false, 5_000L, 2_000L)

        assertEquals(FixProvider.FUSED, fix?.provider)
        assertEquals(12.0, fix?.accuracyM)
        assertEquals(0.0, fix?.speedMps)
    }

    @Test
    fun fixesFromOtherProvidersAreDropped() {
        assertNull(LocationValues.fix(LocationManager.PASSIVE_PROVIDER, 0L, 1.0, 1.0, null, null, null, false, 0L, 0L))
        assertNull(LocationValues.fix("vendor_gnss", 0L, 1.0, 1.0, null, null, null, false, 0L, 0L))
        assertNull(LocationValues.fix(null, 0L, 1.0, 1.0, null, null, null, false, 0L, 0L))
    }

    @Test
    fun satellitesUsedInTheFixAreCounted() {
        val used = setOf(0, 2, 5)

        assertEquals(3, LocationValues.usedInFix(8) { it in used })
        assertEquals(2, LocationValues.usedInFix(3) { it in used })
        assertEquals(0, LocationValues.usedInFix(0) { true })
        assertEquals(0, LocationValues.usedInFix(-1) { true })
    }

    @Test
    fun availabilityComparisonIgnoresObservationTimes() {
        val first = availability(enabled = true, granted = true, providers = setOf(FixProvider.GPS), wallMs = 1)
        val later = availability(enabled = true, granted = true, providers = setOf(FixProvider.GPS), wallMs = 2)

        assertTrue(LocationValues.sameAvailability(first, later))
        assertFalse(LocationValues.sameAvailability(first, later.copy(locationEnabled = false)))
        assertFalse(LocationValues.sameAvailability(first, later.copy(preciseLocationGranted = false)))
        assertFalse(LocationValues.sameAvailability(first, later.copy(providers = setOf(FixProvider.GPS, FixProvider.FUSED))))
    }

    private fun availability(enabled: Boolean, granted: Boolean, providers: Set<FixProvider>, wallMs: Long) =
        LocationAvailability(
            locationEnabled = enabled,
            preciseLocationGranted = granted,
            providers = providers,
            observedWallMs = wallMs,
            observedElapsedMs = wallMs,
        )
}
