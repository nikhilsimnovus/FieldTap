package com.fieldtap.platform.telephony

import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.RadioListener
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerReportGateTest {

    @Test
    fun theFirstOutcomeOfEveryListenerIsReported() {
        val gate = ListenerReportGate()

        for (listener in RadioListener.entries) {
            assertTrue(listener.name, gate.shouldReport(listener, ListenerOutcome.REGISTERED, null))
        }
    }

    @Test
    fun anUnchangedOutcomeIsNotRepeatedEveryPeriod() {
        val gate = ListenerReportGate()

        assertTrue(gate.shouldReport(RadioListener.CELL_INFO_REQUEST, ListenerOutcome.MISSING_PERMISSION, "precise location not granted"))
        repeat(5) {
            assertFalse(gate.shouldReport(RadioListener.CELL_INFO_REQUEST, ListenerOutcome.MISSING_PERMISSION, "precise location not granted"))
        }
    }

    @Test
    fun aChangedOutcomeOrDetailIsReportedOnce() {
        val gate = ListenerReportGate()
        val push = RadioListener.CELL_INFO_PUSH

        assertTrue(gate.shouldReport(push, ListenerOutcome.MISSING_PERMISSION, "needs the Phone permission and precise location"))
        // The user turns on Instant cell updates.
        assertTrue(gate.shouldReport(push, ListenerOutcome.REGISTERED, null))
        assertFalse(gate.shouldReport(push, ListenerOutcome.REGISTERED, null))
        // Same outcome, different detail.
        assertTrue(gate.shouldReport(push, ListenerOutcome.FAILED, "IllegalStateException"))
        assertTrue(gate.shouldReport(push, ListenerOutcome.FAILED, "IllegalStateException: telephony service is null"))
        assertFalse(gate.shouldReport(push, ListenerOutcome.FAILED, "IllegalStateException: telephony service is null"))
        // Back to an outcome seen before still counts as a change.
        assertTrue(gate.shouldReport(push, ListenerOutcome.REGISTERED, null))
    }

    @Test
    fun listenersAreTrackedSeparately() {
        val gate = ListenerReportGate()

        assertTrue(gate.shouldReport(RadioListener.SERVICE_STATE, ListenerOutcome.REGISTERED, null))
        assertTrue(gate.shouldReport(RadioListener.DISPLAY_INFO, ListenerOutcome.REGISTERED, null))
        assertFalse(gate.shouldReport(RadioListener.SERVICE_STATE, ListenerOutcome.REGISTERED, null))
        assertTrue(gate.shouldReport(RadioListener.DATA_CONNECTION_STATE, ListenerOutcome.REGISTERED, null))
    }
}
