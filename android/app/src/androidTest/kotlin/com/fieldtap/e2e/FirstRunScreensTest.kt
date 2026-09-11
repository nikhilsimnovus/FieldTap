package com.fieldtap.e2e

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldtap.MainActivity
import com.fieldtap.R
import com.fieldtap.platform.Permissions
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The first-run screens of a freshly cleared app in the variant `-e variant` names: the disclosure, which must show
 * before anything asks for a permission, and the Permissions screen it leads to. Nothing is granted here; the host
 * clears the app's data before each variant and again before the walk.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunScreensTest {
    private val variant = Variant.fromArguments()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(compose).around(FailureCapture(variant.group))

    @Test
    fun disclosureComesBeforeAnyPermissionPrompt() {
        variant.assertApplied()
        val screens = Screens(compose, variant.group)

        screens.awaitText(R.string.disclosure_heading, Screens.LAUNCH_WAIT_MS)
        assertFalse("Precise location is granted on a first run", E2e.granted(Permissions.FINE_LOCATION))
        assertFalse("A permission dialog shows over the disclosure", PermissionDialogs.showing())
        screens.shot("01-disclosure")
        screens.scrollTo(hasText(E2e.string(R.string.disclosure_limits_title)))
        screens.shot("01b-disclosure-limits")

        screens.click(hasText(E2e.string(R.string.disclosure_accept)) and hasClickAction())
        screens.awaitText(R.string.permissions_location_title)
        assertFalse("A permission dialog showed before Allow was tapped", PermissionDialogs.showing())
        assertFalse("Precise location was granted without a prompt", E2e.granted(Permissions.FINE_LOCATION))
        screens.shot("02-permissions")
    }
}
