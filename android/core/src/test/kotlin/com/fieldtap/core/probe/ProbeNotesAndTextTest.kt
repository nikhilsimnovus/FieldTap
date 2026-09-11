package com.fieldtap.core.probe

import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.RadioListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeNotesAndTextTest {

    @Test
    fun anOrdinaryPhoneGivesNoListenerNotes() {
        val outcomes = RadioListener.entries.associateWith { listener ->
            if (listener in ProbeNotes.RESTRICTED) ListenerOutcome.REFUSED_BY_PLATFORM else ListenerOutcome.REGISTERED
        }

        assertTrue(ProbeNotes.listeners(outcomes).isEmpty())
    }

    @Test
    fun aRestrictedListenerThatRegistersIsNoted() {
        val notes = ProbeNotes.listeners(mapOf(RadioListener.BARRING_INFO to ListenerOutcome.REGISTERED))

        assertEquals(listOf("BarringInfoListener registered, although it normally needs READ_PRECISE_PHONE_STATE."), notes)
    }

    @Test
    fun missingPermissionsAreNotedAndThePushListenerNamesInstantCellUpdates() {
        val notes = ProbeNotes.listeners(
            linkedMapOf(
                RadioListener.CELL_INFO_PUSH to ListenerOutcome.MISSING_PERMISSION,
                RadioListener.CELL_INFO_REQUEST to ListenerOutcome.MISSING_PERMISSION,
                RadioListener.SERVICE_STATE to ListenerOutcome.FAILED,
                RadioListener.DISPLAY_INFO to ListenerOutcome.UNREGISTERED,
            ),
        )

        // RadioListener order, not map order.
        assertEquals(
            listOf(
                "requestCellInfoUpdate did not run: a permission is missing.",
                "CellInfoListener is off: it needs the Phone permission (Instant cell updates) and precise location.",
                "ServiceStateListener failed to register.",
                "DisplayInfoListener never registered.",
            ),
            notes,
        )
    }

    @Test
    fun everyListenerHasADistinctApiName() {
        val names = RadioListener.entries.map { ProbeNotes.apiName(it) }

        assertTrue(names.all { it.isNotBlank() })
        assertEquals(names.size, names.toSet().size)
        assertEquals(
            setOf(RadioListener.PHYSICAL_CHANNEL_CONFIG, RadioListener.BARRING_INFO, RadioListener.REGISTRATION_FAILED),
            ProbeNotes.RESTRICTED,
        )
    }

    @Test
    fun progressTextRoundsElapsedDownAndTheDurationUp() {
        assertEquals("Listening: 12 s of 30 s, 12 answers", ProbeText.progress(12_345, 30_000, 12))
        assertEquals("Listening: 0 s of 30 s, 1 answer", ProbeText.progress(0, 30_000, 1))
        assertEquals("Listening: 30 s of 30 s, 0 answers", ProbeText.progress(40_000, 30_000, 0))
        assertEquals("Listening: 0 s of 2 s, 0 answers", ProbeText.progress(-5, 1_500, 0))
    }
}
