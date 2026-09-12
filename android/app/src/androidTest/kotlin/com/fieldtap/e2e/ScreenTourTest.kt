package com.fieldtap.e2e

import androidx.annotation.StringRes
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldtap.MainActivity
import com.fieldtap.R
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Every screen after the walk, at every scroll position ([Screens.shotFull]), in the variant `-e variant` names: Live with
 * its serving cell, the Start dialog, Sessions, the walk's detail, Readiness, Probe, Settings, its Test targets and About.
 * An upright variant also turns the phone for Live, the screen a car mount holds; the landscape variant takes every screen
 * turned. On a phone-sized screen upright at font scale 1.0, Live's 5-minute chart must lie wholly on the first screen.
 * The disclosure and Permissions screens are taken on a first run by [FirstRunScreensTest]. `-e dir_name` is the walk's
 * session.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTourTest {
    private val variant = Variant.fromArguments()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(compose).around(FailureCapture(variant.group))

    @Test
    fun everyScreenAfterARecordedSession() {
        variant.apply()
        val screens = Screens(compose, variant.group)
        val dirName = E2e.requireArgument("dir_name")
        val sessionName = runBlocking { E2e.graph.sessions.detail(dirName) }?.meta?.name
            ?: throw AssertionError("Session $dirName is missing or unreadable")

        val expectLteNr = E2e.expectLteNr()
        screens.awaitLiveRadio(expectLteNr)
        if (expectLteNr && !variant.landscape && variant.fontScale == 1.0f && E2e.phoneSizeScreen()) {
            screens.assertChartOnFirstScreen()
        }
        screens.shotFull("03-live")
        if (!variant.landscape) {
            // A phone in landscape: two panes, with the session buttons beside them instead of under them.
            screens.inLandscape {
                screens.awaitLive()
                screens.await(E2e.startButton())
                screens.shotFull("03e-live-landscape")
            }
        }
        screens.awaitLive()
        screens.click(E2e.startButton())
        screens.awaitText(R.string.live_start_dialog_title)
        Espresso.closeSoftKeyboard()
        screens.shot("03b-start-dialog")
        // Cancel upright; in landscape the dialog fills the screen and closes with an icon described "Cancel".
        val cancel = E2e.string(R.string.action_cancel)
        screens.click((hasText(cancel) or hasContentDescription(cancel)) and hasClickAction() and hasAnyAncestor(isDialog()))

        screens.click(hasContentDescription(E2e.string(R.string.live_action_sessions)) and hasClickAction())
        val row = hasText(sessionName) and hasClickAction()
        screens.await(row)
        screens.shotFull("04-sessions")
        screens.click(row)
        screens.awaitText(R.string.detail_section_overview)
        screens.shotFull("05-session-detail")
        screens.back()
        screens.await(row)
        screens.back()
        screens.awaitLive()

        visit(screens, R.string.live_menu_readiness, R.string.readiness_checks_title, "06-readiness")
        visitProbe(screens)
        visitSettings(screens)
        visit(screens, R.string.live_menu_about, R.string.about_account_title, "09-about")
    }

    /** Opens [menuItem] from Live, waits for [shows], takes [shot] at every scroll position and returns to Live. */
    private fun visit(screens: Screens, @StringRes menuItem: Int, @StringRes shows: Int, shot: String) {
        screens.openMenuItem(menuItem)
        screens.awaitText(shows)
        screens.shotFull(shot)
        screens.back()
        screens.awaitLive()
    }

    /**
     * The Capability screen at every scroll position, then its explicit read-only "Check with root" run. The check
     * gains no root and never hangs (a hard timeout in the runner); on the CI emulator it settles quickly, and the
     * SELinux/`/dev/diag`/kernel-config rows it fills in appear whatever `su` allowed. Both looks are captured full
     * length, so the artifact shows the honest per-phone verdict the panel reaches.
     */
    private fun visitProbe(screens: Screens) {
        screens.openMenuItem(R.string.live_menu_probe)
        screens.awaitText(R.string.probe_run)
        // The Root & diagnostics card is present once the passive read has loaded; its button sits inside it.
        screens.awaitText(R.string.probe_root_title)
        screens.shotFull("07-probe")
        val checkRoot = hasText(E2e.string(R.string.probe_check_root)) and hasClickAction()
        screens.scrollTo(checkRoot)
        screens.click(checkRoot)
        // The result rows (SELinux, Diag device, Kernel diag support) render once the check has settled.
        screens.awaitText(R.string.probe_root_selinux, timeoutMs = ROOT_CHECK_MS)
        screens.shotFull("07c-probe-root-check")
        screens.back()
        screens.awaitLive()
    }

    /** Settings, then the Test targets screen its tests card opens, each at every scroll position. */
    private fun visitSettings(screens: Screens) {
        screens.openMenuItem(R.string.live_menu_settings)
        screens.awaitText(R.string.settings_section_measurement)
        screens.shotFull("08-settings")
        val targets = hasText(E2e.string(R.string.settings_test_targets)) and hasClickAction()
        screens.scrollTo(targets)
        screens.click(targets)
        screens.awaitText(R.string.settings_ping_heading)
        screens.shotFull("08b-test-targets")
        screens.back()
        screens.awaitText(R.string.settings_title)
        screens.back()
        screens.awaitLive()
    }

    private companion object {
        /** Room for the root check to settle: the runner's own su timeout is 6 s, plus slack on a busy emulator. */
        const val ROOT_CHECK_MS: Long = 30_000
    }
}
