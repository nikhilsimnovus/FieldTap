package com.fieldtap.e2e

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldtap.MainActivity
import com.fieldtap.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The relaunch after android/e2e/run_e2e.sh killed the app in the middle of a session: this process is the first to
 * start since, so its launch recovery must close the session, and Live must say so.
 *
 * `-e recovered_dir` names the session, `-e scenario` the kill (it names the screenshot), and `-e expected_cause`, when
 * given, is the exit token the host derived from `dumpsys activity exit-info` for the killed process.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(compose).around(FailureCapture(GROUP))

    @Test
    fun liveReportsTheInterruptedSession() {
        val dirName = E2e.requireArgument("recovered_dir")
        val scenario = E2e.requireArgument("scenario")
        val expectedCause = E2e.argument("expected_cause")
        val screens = Screens(compose, GROUP)

        screens.await(hasText(E2e.string(R.string.live_recovered_title)), RECOVERY_WAIT_MS, unmerged = true)
        // The banner names the session as the engineer named it, not by its folder.
        val sessionName = runBlocking { E2e.graph.sessions.detail(dirName) }?.meta?.name ?: throw AssertionError("Session $dirName has no name")
        screens.await(hasTextMatching(Regex(Regex.escape(sessionName))), RECOVERY_WAIT_MS, unmerged = true)
        screens.shot("16-live-recovered-$scenario")

        val detail = runBlocking { E2e.graph.sessions.detail(dirName) } ?: throw AssertionError("Session $dirName is gone")
        assertFalse("Session $dirName is still open", detail.summary.recording)
        assertNotNull("Session $dirName has no stop time", detail.summary.stoppedUtcMs)
        val stoppedBy = detail.summary.stoppedBy
        assertNotEquals("Session $dirName still says recording", "recording", stoppedBy)
        if (expectedCause != null) assertEquals("stopped_by of $dirName", expectedCause, stoppedBy)

        val outcome = E2e.graph.recovery.closed.value.firstOrNull { it.dirName == dirName }
            ?: throw AssertionError("Launch recovery did not report $dirName")
        assertTrue("Launch recovery reported $dirName as not interrupted", outcome.interrupted)
        assertEquals(stoppedBy, outcome.stoppedBy)
        E2e.writeResult(
            "recovery-$scenario.json",
            linkedMapOf("dir_name" to dirName, "stopped_by" to stoppedBy, "expected_cause" to expectedCause, "banner" to true),
        )
    }

    private companion object {
        const val GROUP = "recovery"

        /** Launch recovery runs in the background of a cold start. */
        const val RECOVERY_WAIT_MS = 60_000L
    }
}
