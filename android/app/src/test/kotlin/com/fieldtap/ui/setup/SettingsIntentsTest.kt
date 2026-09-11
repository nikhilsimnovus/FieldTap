package com.fieldtap.ui.setup

import com.fieldtap.core.readiness.SettingsTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The action strings are the values javap reports for platforms;android-37.0, written out so a renamed constant fails. */
class SettingsIntentsTest {
    private val appDetails = SettingsIntentSpec("android.settings.APPLICATION_DETAILS_SETTINGS", packageUri = true)

    @Test
    fun appDetailsOpensThisAppsDetailsScreen() {
        assertEquals(listOf(appDetails), SettingsIntents.candidates(SettingsTarget.APP_DETAILS))
    }

    @Test
    fun locationOpensTheLocationSwitchThenFallsBackToAppDetails() {
        assertEquals(
            listOf(SettingsIntentSpec("android.settings.LOCATION_SOURCE_SETTINGS"), appDetails),
            SettingsIntents.candidates(SettingsTarget.LOCATION_SOURCE),
        )
    }

    @Test
    fun notificationsOpenThisAppsNotificationSettingsByPackage() {
        assertEquals(
            listOf(SettingsIntentSpec("android.settings.APP_NOTIFICATION_SETTINGS", appPackageExtra = true), appDetails),
            SettingsIntents.candidates(SettingsTarget.APP_NOTIFICATIONS),
        )
    }

    @Test
    fun batteryOpensTheOptimisationListNeverTheExemptionRequest() {
        assertEquals(
            listOf(SettingsIntentSpec("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"), appDetails),
            SettingsIntents.candidates(SettingsTarget.BATTERY_OPTIMISATION),
        )
        val everyAction = SettingsTarget.entries.flatMap { target -> SettingsIntents.candidates(target) }.map { it.action }
        assertFalse(everyAction.contains("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
    }

    @Test
    fun wifiOpensThePanelThenTheWifiListThenAppDetails() {
        assertEquals(
            listOf(
                SettingsIntentSpec("android.settings.panel.action.WIFI"),
                SettingsIntentSpec("android.settings.WIFI_SETTINGS"),
                appDetails,
            ),
            SettingsIntents.candidates(SettingsTarget.WIFI),
        )
    }

    @Test
    fun noneHasNoScreenAndEveryOtherTargetEndsAtAppDetails() {
        assertTrue(SettingsIntents.candidates(SettingsTarget.NONE).isEmpty())
        SettingsTarget.entries.filter { it != SettingsTarget.NONE }.forEach { target ->
            assertEquals(target.name, appDetails, SettingsIntents.candidates(target).last())
        }
    }

    @Test
    fun onlyAppDetailsCarriesThePackageUri() {
        val specs = SettingsTarget.entries.flatMap { SettingsIntents.candidates(it) }
        specs.filter { it.packageUri }.forEach { assertEquals(appDetails, it) }
    }
}
