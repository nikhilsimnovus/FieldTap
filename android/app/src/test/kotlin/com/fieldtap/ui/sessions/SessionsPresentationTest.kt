package com.fieldtap.ui.sessions

import com.fieldtap.core.session.StoragePolicy
import com.fieldtap.core.session.StorageStatus
import com.fieldtap.ui.theme.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsPresentationTest {
    private val policy = StoragePolicy()

    private fun storage(usedBytes: Long, freeBytes: Long = 6_900_000_000) = StorageStatus(usedBytes, freeBytes, policy)

    @Test
    fun storageIsOneLineUntilItNeedsAttention() {
        assertFalse("59.5 kB of 2 GB", SessionsPresentation.storageCardShown(storage(59_500)))
        assertFalse("exactly 80 % of the cap", SessionsPresentation.storageCardShown(storage(policy.capBytes * 8 / 10)))
        assertTrue("past 80 % of the cap", SessionsPresentation.storageCardShown(storage(policy.capBytes * 8 / 10 + 1_000_000)))
        assertTrue("the phone is nearly full, so sessions cannot start", SessionsPresentation.storageCardShown(storage(59_500, freeBytes = policy.minFreeBytes - 1)))
    }

    @Test
    fun aShareBelow105OverTenPercentWarns() {
        assertNull(SessionsPresentation.belowFairTone(0.0))
        assertNull(SessionsPresentation.belowFairTone(10.0))
        assertEquals(StatusTone.WARNING, SessionsPresentation.belowFairTone(10.1))
    }

    @Test
    fun anySamplingGapIsAnError() {
        assertNull(SessionsPresentation.gapsTone(0))
        assertEquals(StatusTone.ERROR, SessionsPresentation.gapsTone(1))
    }
}
