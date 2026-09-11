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
 * A screenshot of every screen after the walk, in the variant `-e variant` names: Live with its serving cell, the Start
 * dialog, Sessions, the walk's detail and its Share card, Readiness, Probe, Settings and About. The disclosure and
 * Permissions screens are taken on a first run by [FirstRunScreensTest]. `-e dir_name` is the walk's session.
 */
@RunWith(AndroidJUnit4::class)
class ScreenTourTest {
    private val variant = Variant.fromArguments()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(compose).around(FailureCapture(variant.group))

    @Test
    fun everyScreenAfterARecordedSession() {
        variant.assertApplied()
        val screens = Screens(compose, variant.group)
        val dirName = E2e.requireArgument("dir_name")
        val sessionName = runBlocking { E2e.graph.sessions.detail(dirName) }?.meta?.name
            ?: throw AssertionError("Session $dirName is missing or unreadable")

        screens.awaitLiveRadio(E2e.expectLteNr())
        screens.shot("03-live")
        screens.click(hasText(E2e.string(R.string.live_start)) and hasClickAction())
        screens.awaitText(R.string.live_start_dialog_title)
        Espresso.closeSoftKeyboard()
        screens.shot("03b-start-dialog")
        screens.click(hasText(E2e.string(R.string.action_cancel)) and hasClickAction() and hasAnyAncestor(isDialog()))

        screens.click(hasContentDescription(E2e.string(R.string.live_action_sessions)) and hasClickAction())
        val row = hasText(sessionName) and hasClickAction()
        screens.await(row)
        screens.shot("04-sessions")
        screens.click(row)
        screens.awaitText(R.string.detail_section_overview)
        screens.shot("05-session-detail")
        screens.scrollTo(hasText(E2e.string(R.string.detail_section_share)))
        screens.shot("05b-session-share")
        screens.back()
        screens.await(row)
        screens.back()
        screens.awaitText(R.string.live_title)

        visit(screens, R.string.live_menu_readiness, R.string.readiness_checks_title, "06-readiness")
        visit(screens, R.string.live_menu_probe, R.string.probe_run, "07-probe")
        visit(screens, R.string.live_menu_settings, R.string.settings_section_tests, "08-settings")
        visit(screens, R.string.live_menu_about, R.string.about_account_title, "09-about")
    }

    /** Opens [menuItem] from Live, waits for [shows], takes [shot] and returns to Live. */
    private fun visit(screens: Screens, @StringRes menuItem: Int, @StringRes shows: Int, shot: String) {
        screens.openMenuItem(menuItem)
        screens.awaitText(shows)
        screens.shot(shot)
        screens.back()
        screens.awaitText(R.string.live_title)
    }
}
