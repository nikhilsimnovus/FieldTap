package com.fieldtap.e2e

import android.os.SystemClock
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldtap.MainActivity
import com.fieldtap.R
import com.fieldtap.app.SessionStatus
import com.fieldtap.core.privacy.PrivacyZone
import com.fieldtap.core.session.RecorderSnapshot
import com.fieldtap.debug.DebugAutomation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Location services switched off while a session records, with a privacy zone 10 km from the walk the host feeds.
 *
 * 1. The session starts through [DebugAutomation] and records fixes from the walk.
 * 2. Location goes off (`cmd location set-location-enabled false`): Live and the notification say so, and the files get
 *    `gps_lost` with the detail "Location services turned off".
 * 3. No fix shows where the phone is, so inputs wait for one: a marker tapped on Live is said to wait.
 * 4. A minute after the last fix outside the zone, logging pauses and drops the marker: Live and the notification say it
 *    was not saved.
 * 5. Location comes back on: the first fix resumes logging (10 km leaves no time to have visited the zone) and writes
 *    `gps_restored`.
 * 6. Stopped, the Session detail screen says a marker was not saved.
 *
 * The times of the switches and what each screen said go to `e2e/location-off-result.json`, which android/e2e/check_e2e.py
 * reads with the pulled session. The host grants the permissions beforehand and switches location back on afterwards,
 * whatever happens here; the zone is removed here.
 */
@RunWith(AndroidJUnit4::class)
class LocationOffTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(compose).around(FailureCapture(GROUP))

    private val screens = Screens(compose, GROUP)
    private val result = linkedMapOf<String, Any?>()

    @Test
    fun locationSwitchedOffIsRecordedAndADroppedMarkerIsShown() {
        runBlocking { E2e.graph.settings.update { it.copy(zones = listOf(FAR_ZONE)) } }
        try {
            recordWithLocationSwitchedOff()
        } finally {
            setLocationEnabled(true)
            runBlocking { E2e.graph.settings.update { it.copy(zones = emptyList()) } }
        }
    }

    private fun recordWithLocationSwitchedOff() {
        screens.awaitText(R.string.live_title, Screens.LAUNCH_WAIT_MS)
        val started = runBlocking {
            DebugAutomation.start(
                context = E2e.context,
                name = SESSION_NAME,
                note = null,
                location = null,
                tests = false,
                acceptConsent = true,
                markReady = true,
                timeoutMs = START_TIMEOUT_MS,
            )
        }
        assertTrue("The session did not start: ${started.toJson()}", started.ok)
        result["dir_name"] = started.dirName
        save()
        screens.waitFor("fixes from the walk in the session", FIX_WAIT_MS) {
            snapshot()?.let { it.trackRows >= MIN_TRACK_ROWS && it.hasRecentFix } == true
        }

        result["location_off_utc_ms"] = E2e.graph.clock.wallMillis()
        save()
        setLocationEnabled(false)
        screens.await(hasText(E2e.string(R.string.live_location_off_recording)), SWITCH_WAIT_MS)
        result["live_location_off_banner"] = true
        screens.waitFor("the notification to say location is off", NOTIFICATION_WAIT_MS) {
            E2e.notificationTitle(E2e.sessionNotification()) == E2e.string(R.string.notification_location_off_title)
        }
        result["notification_location_off"] = true
        save()
        screens.shot("20-live-location-off")

        // No fix shows where the phone is any more, so a marker waits for one.
        screens.waitFor("inputs to wait for a location fix", HOLD_WAIT_MS) { snapshot()?.holdingInputs == true }
        val inDialog = hasAnyAncestor(isDialog())
        screens.click(hasText(E2e.string(R.string.live_mark)) and hasClickAction() and !inDialog)
        screens.awaitText(R.string.live_mark_dialog_title)
        screens.click(hasText(E2e.string(R.string.live_mark_confirm)) and hasClickAction() and inDialog)
        screens.awaitText(R.string.live_message_mark_held, SNACKBAR_WAIT_MS)
        result["mark_held_message"] = true
        save()

        // A minute after the last fix outside the zone, logging pauses and drops what waited, the marker with it.
        screens.waitFor("logging to pause for want of a fix", PAUSE_WAIT_MS) {
            snapshot()?.let { it.paused && it.waitingForLocation && it.markersDropped == 1 } == true
        }
        screens.awaitText(R.string.live_message_mark_dropped, SNACKBAR_WAIT_MS)
        result["mark_dropped_message"] = true
        val droppedText = E2e.context.resources.getQuantityString(R.plurals.notification_markers_dropped, 1, 1)
        screens.waitFor("the notification to say the marker was not saved", NOTIFICATION_WAIT_MS) {
            E2e.notificationText(E2e.sessionNotification()) == droppedText
        }
        result["notification_markers_dropped"] = true
        save()
        screens.shot("21-live-marker-dropped")

        result["location_on_utc_ms"] = E2e.graph.clock.wallMillis()
        save()
        setLocationEnabled(true)
        screens.waitFor("logging to resume at a fix", RESUME_WAIT_MS) { snapshot()?.let { !it.paused && it.hasRecentFix } == true }
        SystemClock.sleep(AFTER_RESUME_MS)

        val stopped = runBlocking { DebugAutomation.stop(E2e.context, STOP_TIMEOUT_MS) }
        assertTrue("The session did not stop: ${stopped.toJson()}", stopped.ok)
        val outcome = checkNotNull(E2e.graph.sessionControl.lastOutcome.value) { "Stop left no session outcome" }
        assertEquals(started.dirName, outcome.dirName)
        result["outcome_markers_dropped"] = outcome.markersDropped
        save()

        screens.click(hasContentDescription(E2e.string(R.string.live_action_sessions)) and hasClickAction())
        val row = hasText(SESSION_NAME) and hasClickAction()
        screens.await(row)
        screens.click(row)
        screens.awaitText(R.string.detail_section_overview)
        screens.await(hasText(E2e.context.resources.getQuantityString(R.plurals.detail_markers_dropped, 1, 1)))
        result["detail_markers_dropped_banner"] = true
        save()
        screens.shotFull("22-session-detail-marker-dropped")
        screens.back()
        screens.await(row)
        screens.back()
        screens.awaitText(R.string.live_title)
    }

    private fun snapshot(): RecorderSnapshot? = (E2e.graph.sessionControl.status.value as? SessionStatus.Recording)?.snapshot

    private fun setLocationEnabled(enabled: Boolean) {
        E2e.shell("cmd location set-location-enabled $enabled")
    }

    private fun save() {
        E2e.writeResult(RESULT_FILE, result)
    }

    private companion object {
        const val GROUP = "location-off"
        const val RESULT_FILE = "location-off-result.json"
        const val SESSION_NAME = "E2E location off"

        /**
         * 0.09 degrees, 10 km, north of where android/e2e/check_e2e.py starts the walk: after a minute without a fix, the
         * first fix still leaves no time to have been inside, so logging resumes at once.
         */
        val FAR_ZONE = PrivacyZone(id = "e2e-far-zone", label = "E2E zone 10 km north", lat = 13.0616, lon = 77.5946, radiusM = 100.0)

        const val MIN_TRACK_ROWS = 5L
        const val START_TIMEOUT_MS = 45_000L
        const val STOP_TIMEOUT_MS = 30_000L
        const val FIX_WAIT_MS = 60_000L
        const val SWITCH_WAIT_MS = 20_000L
        const val NOTIFICATION_WAIT_MS = 15_000L

        /** Inputs wait from 5 s after the last fix outside the zone. */
        const val HOLD_WAIT_MS = 30_000L
        const val SNACKBAR_WAIT_MS = 10_000L

        /** Logging pauses once no fix outside the zone came for 60 s. */
        const val PAUSE_WAIT_MS = 100_000L
        const val RESUME_WAIT_MS = 60_000L

        /** A few more fixes and cell answers written after logging resumed. */
        const val AFTER_RESUME_MS = 6_000L
    }
}
