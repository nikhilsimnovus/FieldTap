package com.fieldtap.platform.telephony

import com.fieldtap.core.input.ListenerOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class PushListenerStateTest {

    @Test
    fun aRegisteredPushListenerStaysRegistered() {
        assertEquals(PushListenerState.REGISTERED, PushListenerState.after(ListenerOutcome.REGISTERED))
    }

    @Test
    fun aMissingPermissionKeepsWaitingSoGrantingItLaterTakesEffect() {
        assertEquals(PushListenerState.WAITING, PushListenerState.after(ListenerOutcome.MISSING_PERMISSION))
        assertEquals(PushListenerState.WAITING, PushListenerState.after(ListenerOutcome.UNREGISTERED))
    }

    @Test
    fun aFailureNoPermissionFixesIsNotRetried() {
        assertEquals(PushListenerState.ABANDONED, PushListenerState.after(ListenerOutcome.FAILED))
        assertEquals(PushListenerState.ABANDONED, PushListenerState.after(ListenerOutcome.REFUSED_BY_PLATFORM))
    }

    @Test
    fun everyOutcomeHasAState() {
        for (outcome in ListenerOutcome.entries) {
            PushListenerState.after(outcome)
        }
    }
}
