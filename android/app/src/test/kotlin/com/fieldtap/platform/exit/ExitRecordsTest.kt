package com.fieldtap.platform.exit

import com.fieldtap.core.session.ExitRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitRecordsTest {

    @Test
    fun newestComesFirst() {
        val older = ExitRecord(pid = 100, timestampWallMs = 1_000, reason = 3, description = "low memory")
        val newest = ExitRecord(pid = 300, timestampWallMs = 3_000, reason = 14, description = null)
        val middle = ExitRecord(pid = 200, timestampWallMs = 2_000, reason = 10, description = "user request")

        assertEquals(listOf(newest, middle, older), ExitRecords.newestFirst(listOf(older, newest, middle)))
    }

    @Test
    fun equalTimestampsKeepAndroidsOrder() {
        val first = ExitRecord(pid = 1, timestampWallMs = 5_000, reason = 13, description = null)
        val second = ExitRecord(pid = 2, timestampWallMs = 5_000, reason = 4, description = null)

        assertEquals(listOf(first, second), ExitRecords.newestFirst(listOf(first, second)))
    }

    @Test
    fun noRecordsGiveNoRecords() {
        assertTrue(ExitRecords.newestFirst(emptyList()).isEmpty())
    }
}
