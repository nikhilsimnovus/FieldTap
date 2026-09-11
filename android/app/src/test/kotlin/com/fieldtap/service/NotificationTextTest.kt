package com.fieldtap.service

import com.fieldtap.app.SessionStatus
import com.fieldtap.app.SoakState
import com.fieldtap.core.session.RecorderSnapshot
import com.fieldtap.core.session.StartRequest
import com.fieldtap.core.soak.SoakResult
import com.fieldtap.format.ServingRat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextTest {

    @Test
    fun savingWinsOverPausedWhichWinsOverRecording() {
        assertEquals(RecordingHeadline.RECORDING, NotificationText.headline(snapshot()))
        assertEquals(RecordingHeadline.PAUSED, NotificationText.headline(snapshot(paused = true)))
        assertEquals(RecordingHeadline.SAVING, NotificationText.headline(snapshot(paused = true, stopping = true)))
    }

    @Test
    fun locationOffAndWaitingForAFixAreSaidInsteadOfARecordingThatCollectsNothing() {
        assertEquals(RecordingHeadline.LOCATION_OFF, NotificationText.headline(snapshot().copy(locationEnabled = false)))
        assertEquals(
            RecordingHeadline.LOCATION_OFF,
            NotificationText.headline(snapshot(paused = true).copy(locationEnabled = false, waitingForLocation = true)),
        )
        assertEquals(RecordingHeadline.WAITING_FOR_LOCATION, NotificationText.headline(snapshot().copy(waitingForLocation = true)))
        assertEquals(RecordingHeadline.WAITING_FOR_LOCATION, NotificationText.headline(snapshot(paused = true).copy(waitingForLocation = true)))
        assertEquals(RecordingHeadline.SAVING, NotificationText.headline(snapshot(stopping = true).copy(locationEnabled = false)))
    }

    @Test
    fun ratLabelsAreUpperCase() {
        assertEquals("LTE", NotificationText.ratLabel(ServingRat.LTE))
        assertEquals("NR", NotificationText.ratLabel(ServingRat.NR))
    }

    @Test
    fun ageHasOneDecimalBelowTenSecondsThenWholeSecondsThenMinutes() {
        assertEquals("0.0 s", NotificationText.age(0, Locale.US))
        assertEquals("1.4 s", NotificationText.age(1_449, Locale.US))
        assertEquals("9.9 s", NotificationText.age(9_949, Locale.US))
        assertEquals("10 s", NotificationText.age(9_950, Locale.US))
        assertEquals("14 s", NotificationText.age(14_400, Locale.US))
        assertEquals("119 s", NotificationText.age(119_000, Locale.US))
        assertEquals("2 min", NotificationText.age(120_000, Locale.US))
        assertEquals("0.0 s", NotificationText.age(-5, Locale.US))
    }

    @Test
    fun ageFollowsTheLocaleDecimalSeparator() {
        assertEquals("1,4 s", NotificationText.age(1_400, Locale.GERMANY))
    }

    @Test
    fun durationsAreMinutesAndSeconds() {
        assertEquals("0:00", NotificationText.minutesSeconds(0))
        assertEquals("1:05", NotificationText.minutesSeconds(65_999))
        assertEquals("10:00", NotificationText.minutesSeconds(600_000))
        assertEquals("0:00", NotificationText.minutesSeconds(-1))
    }

    @Test
    fun theModelFollowsTheSessionFirstThenTheSoakTest() {
        val recording = snapshot()

        assertEquals(NotificationModel.Starting, NotificationModel.of(SessionStatus.Starting(StartRequest("walk")), SoakState.Idle))
        assertEquals(NotificationModel.Recording(recording), NotificationModel.of(SessionStatus.Recording(recording), SoakState.Idle))
        assertEquals(
            NotificationModel.Recording(recording.copy(stopping = true)),
            NotificationModel.of(SessionStatus.Stopping(recording), SoakState.Idle),
        )
        assertEquals(
            NotificationModel.Soak(30_000, 600_000),
            NotificationModel.of(SessionStatus.Idle, SoakState.Running(30_000, 600_000)),
        )
        assertEquals(NotificationModel.None, NotificationModel.of(SessionStatus.Idle, SoakState.Idle))
        val done = SoakState.Done(SoakResult(600_000, 600, 600, 10, 5, 0))
        assertEquals(NotificationModel.None, NotificationModel.of(SessionStatus.Idle, done))
    }

    private fun snapshot(paused: Boolean = false, stopping: Boolean = false) = RecorderSnapshot(
        dirName = "20260910-143000_walk",
        startedUtcMs = 1_789_050_600_000L,
        elapsedMs = 12_000,
        servingRat = ServingRat.LTE,
        servingRsrpDbm = -97,
        newestSampleAgeMs = 1_400,
        paused = paused,
        freshSamples = 6,
        repeatsDropped = 6,
        eventsWritten = 1,
        trackRows = 12,
        hasRecentFix = true,
        stopping = stopping,
    )
}
