package com.fieldtap.core

import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.radio.AndroidSdkConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CellValuesTest {

    @Test
    fun unavailableIsWrittenBlankNeverAsTheSentinel() {
        assertEquals("", CellValues.csvCell(2147483647))
        assertEquals("-97", CellValues.csvCell(-97))
    }

    @Test
    fun intOrNullDropsBothTelephonySentinels() {
        assertNull(CellValues.intOrNull(Int.MAX_VALUE))
        assertNull(CellValues.intOrNull(Int.MIN_VALUE))
        assertEquals(-97, CellValues.intOrNull(-97))
        assertEquals(0, CellValues.intOrNull(0))
        assertEquals(Int.MAX_VALUE - 1, CellValues.intOrNull(Int.MAX_VALUE - 1))
        assertEquals(Int.MIN_VALUE + 1, CellValues.intOrNull(Int.MIN_VALUE + 1))
    }

    @Test
    fun longOrNullDropsTheUnavailableNciAndTheIntSentinel() {
        assertNull(CellValues.longOrNull(Long.MAX_VALUE))
        assertNull(CellValues.longOrNull(Int.MAX_VALUE.toLong()))
        assertEquals(68_719_476_735L, CellValues.longOrNull(68_719_476_735L))
        assertEquals(0L, CellValues.longOrNull(0L))
        assertEquals(Int.MAX_VALUE.toLong() + 1, CellValues.longOrNull(Int.MAX_VALUE.toLong() + 1))
        assertEquals(Int.MIN_VALUE.toLong(), CellValues.longOrNull(Int.MIN_VALUE.toLong()))
    }

    @Test
    fun sentinelsAndConnectionStatusesMatchTheAndroidJar() {
        val ints = AndroidSdkConstants.intConstants("android.telephony.CellInfo")
        assertEquals(ints.getValue("UNAVAILABLE"), CellValues.UNAVAILABLE)
        assertEquals(ints.getValue("CONNECTION_UNKNOWN"), CellValues.UNAVAILABLE)
        assertEquals(ints.getValue("CONNECTION_NONE"), CellSnapshot.CONNECTION_NONE)
        assertEquals(ints.getValue("CONNECTION_PRIMARY_SERVING"), CellSnapshot.CONNECTION_PRIMARY_SERVING)
        assertEquals(ints.getValue("CONNECTION_SECONDARY_SERVING"), CellSnapshot.CONNECTION_SECONDARY_SERVING)
        val longs = AndroidSdkConstants.longConstants("android.telephony.CellInfo")
        assertEquals(longs.getValue("UNAVAILABLE_LONG"), CellValues.UNAVAILABLE_LONG)
    }
}
