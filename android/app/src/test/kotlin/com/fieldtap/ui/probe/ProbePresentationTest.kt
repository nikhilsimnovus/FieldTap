package com.fieldtap.ui.probe

import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.RadioListener
import com.fieldtap.ui.theme.StatusTone
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbePresentationTest {

    @Test
    fun theThreeRestrictedListenersRefusedAreRefusedAsExpected() {
        listOf(RadioListener.PHYSICAL_CHANNEL_CONFIG, RadioListener.BARRING_INFO, RadioListener.REGISTRATION_FAILED).forEach { listener ->
            assertEquals(ListenerWord.REFUSED_EXPECTED, ProbePresentation.listenerWord(listener, ListenerOutcome.REFUSED_BY_PLATFORM))
            assertEquals(ListenerWord.REFUSED_EXPECTED, ProbePresentation.listenerWord(listener, ListenerOutcome.UNREGISTERED))
            assertEquals(ListenerWord.REGISTERED, ProbePresentation.listenerWord(listener, ListenerOutcome.REGISTERED))
        }
        assertEquals(StatusTone.NEUTRAL, ListenerWord.REFUSED_EXPECTED.tone)
    }

    @Test
    fun otherListenersAreWordedAndTonedByOutcome() {
        assertEquals(ListenerWord.REGISTERED, ProbePresentation.listenerWord(RadioListener.SERVICE_STATE, ListenerOutcome.REGISTERED))
        assertEquals(ListenerWord.MISSING_PERMISSION, ProbePresentation.listenerWord(RadioListener.CELL_INFO_PUSH, ListenerOutcome.MISSING_PERMISSION))
        assertEquals(ListenerWord.REFUSED, ProbePresentation.listenerWord(RadioListener.SIGNAL_STRENGTHS, ListenerOutcome.REFUSED_BY_PLATFORM))
        assertEquals(ListenerWord.NOT_REGISTERED, ProbePresentation.listenerWord(RadioListener.DISPLAY_INFO, ListenerOutcome.UNREGISTERED))
        assertEquals(ListenerWord.FAILED, ProbePresentation.listenerWord(RadioListener.BARRING_INFO, ListenerOutcome.FAILED))
        assertEquals(StatusTone.SUCCESS, ListenerWord.REGISTERED.tone)
        assertEquals(StatusTone.WARNING, ListenerWord.MISSING_PERMISSION.tone)
        assertEquals(StatusTone.ERROR, ListenerWord.REFUSED.tone)
        assertEquals(StatusTone.ERROR, ListenerWord.FAILED.tone)
    }

    @Test
    fun everyListenerIsListedInOrderAndAMissingOneNeverRegistered() {
        val rows = ProbePresentation.listenerRows(mapOf(RadioListener.SERVICE_STATE to ListenerOutcome.REGISTERED))
        assertEquals(RadioListener.entries.toList(), rows.map { it.first })
        assertEquals(ListenerOutcome.REGISTERED, rows.single { it.first == RadioListener.SERVICE_STATE }.second)
        assertEquals(
            RadioListener.entries.size - 1,
            rows.count { it.second == ListenerOutcome.UNREGISTERED },
        )
    }

    @Test
    fun timestampsAnswerYesNoOrNotEnough() {
        assertEquals(ProbeAnswer.YES, ProbePresentation.timestamps(true))
        assertEquals(ProbeAnswer.NO, ProbePresentation.timestamps(false))
        assertEquals(ProbeAnswer.NOT_ENOUGH, ProbePresentation.timestamps(null))
    }

    @Test
    fun aRangeNeedsBothEnds() {
        assertNull(ProbePresentation.range(null, null))
        assertNull(ProbePresentation.range(-110, null))
        assertNull(ProbePresentation.range(null, -70))
        assertEquals(-110..-70, ProbePresentation.range(-110, -70))
        assertEquals(-3..-3, ProbePresentation.range(-3, -3))
        assertEquals(-110..-70, ProbePresentation.range(-70, -110))
    }

    @Test
    fun secondsHaveOneDecimalInTheLocale() {
        assertEquals("30.0", ProbePresentation.seconds(30_000, Locale.UK))
        assertEquals("1.5", ProbePresentation.seconds(1_500, Locale.UK))
        assertEquals("0.1", ProbePresentation.seconds(50, Locale.UK))
        assertEquals("1,5", ProbePresentation.seconds(1_500, Locale.GERMANY))
    }

    @Test
    fun permissionsAreNamedByWhatTheyAllow() {
        assertEquals(ProbePermissionLabel.PRECISE_LOCATION, ProbePresentation.permissionLabel("android.permission.ACCESS_FINE_LOCATION"))
        assertEquals(ProbePermissionLabel.APPROXIMATE_LOCATION, ProbePresentation.permissionLabel("android.permission.ACCESS_COARSE_LOCATION"))
        assertEquals(ProbePermissionLabel.NOTIFICATIONS, ProbePresentation.permissionLabel("android.permission.POST_NOTIFICATIONS"))
        assertEquals(ProbePermissionLabel.PHONE, ProbePresentation.permissionLabel("android.permission.READ_PHONE_STATE"))
        assertEquals(ProbePermissionLabel.OTHER, ProbePresentation.permissionLabel("android.permission.CAMERA"))
    }

    @Test
    fun valuesAreJoinedWithAMiddleDotOrNotAtAll() {
        assertNull(ProbePresentation.joined(emptyList()))
        assertNull(ProbePresentation.joined(listOf(" ", "")))
        assertEquals("LTE", ProbePresentation.joined(listOf("LTE")))
        assertEquals("LTE · NR", ProbePresentation.joined(listOf("LTE", "", "NR")))
    }
}
