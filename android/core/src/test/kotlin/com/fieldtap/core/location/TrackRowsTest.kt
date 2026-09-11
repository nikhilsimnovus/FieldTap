package com.fieldtap.core.location

import com.fieldtap.format.FixProvider
import com.fieldtap.format.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackRowsTest {

    @Test
    fun aRowCarriesTheFixTimeNotTheObservedTime() {
        val fix = fix(5_000, lat = 38.8895123, lon = -77.0353456, provider = FixProvider.FUSED, deliveryDelayMs = 700)
        val row = TrackRows.of(fix)
        assertEquals(fix.wallMs, row.timeUtcMs)
        assertEquals(LatLon(38.8895123, -77.0353456), row.position)
        assertEquals(FixProvider.FUSED, row.provider)
        assertEquals(5.0, row.accuracyM!!, 0.0)
        assertEquals(18.0, row.altitudeM!!, 0.0)
        assertEquals(1.4, row.speedMps!!, 0.0)
    }

    @Test
    fun unknownValuesStayBlank() {
        val row = TrackRows.of(fix(0, accuracyM = null, altitudeM = null, speedMps = null))
        assertNull(row.accuracyM)
        assertNull(row.altitudeM)
        assertNull(row.speedMps)
    }

    @Test
    fun accuracyMustBeAFiniteNumberNotBelowZero() {
        assertNull(TrackRows.of(fix(0, accuracyM = -1.0)).accuracyM)
        assertNull(TrackRows.of(fix(0, accuracyM = Double.NaN)).accuracyM)
        assertNull(TrackRows.of(fix(0, accuracyM = Double.POSITIVE_INFINITY)).accuracyM)
        assertEquals(0.0, TrackRows.of(fix(0, accuracyM = 0.0)).accuracyM!!, 0.0)
        assertEquals(4.25, TrackRows.of(fix(0, accuracyM = 4.25)).accuracyM!!, 0.0)
    }

    @Test
    fun altitudeOutsideItsRangeIsBlankNeverClipped() {
        assertEquals(-1_000.0, TrackRows.of(fix(0, altitudeM = -1_000.0)).altitudeM!!, 0.0)
        assertNull(TrackRows.of(fix(0, altitudeM = -1_000.1)).altitudeM)
        assertEquals(20_000.0, TrackRows.of(fix(0, altitudeM = 20_000.0)).altitudeM!!, 0.0)
        assertNull(TrackRows.of(fix(0, altitudeM = 20_000.5)).altitudeM)
        assertNull(TrackRows.of(fix(0, altitudeM = Double.NaN)).altitudeM)
    }

    @Test
    fun speedOutsideItsRangeIsBlankNeverClipped() {
        assertEquals(0.0, TrackRows.of(fix(0, speedMps = 0.0)).speedMps!!, 0.0)
        assertNull(TrackRows.of(fix(0, speedMps = -0.1)).speedMps)
        assertEquals(400.0, TrackRows.of(fix(0, speedMps = 400.0)).speedMps!!, 0.0)
        assertNull(TrackRows.of(fix(0, speedMps = 400.01)).speedMps)
        assertNull(TrackRows.of(fix(0, speedMps = Double.NaN)).speedMps)
    }
}
