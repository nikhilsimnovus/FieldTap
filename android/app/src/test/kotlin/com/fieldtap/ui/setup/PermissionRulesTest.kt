package com.fieldtap.ui.setup

import com.fieldtap.ui.components.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRulesTest {

    @Test
    fun requestStatusFollowsGrantRationaleAndWhetherThisScreenAsked() {
        assertEquals(PermissionStatus.GRANTED, PermissionRules.requestStatus(granted = true, requestedBefore = true, showRationale = true))
        assertEquals(PermissionStatus.GRANTED, PermissionRules.requestStatus(granted = true, requestedBefore = false, showRationale = false))
        assertEquals(PermissionStatus.NOT_REQUESTED, PermissionRules.requestStatus(granted = false, requestedBefore = false, showRationale = false))
        assertEquals(PermissionStatus.DENIED, PermissionRules.requestStatus(granted = false, requestedBefore = false, showRationale = true))
        assertEquals(PermissionStatus.DENIED, PermissionRules.requestStatus(granted = false, requestedBefore = true, showRationale = true))
        assertEquals(PermissionStatus.DENIED_PERMANENTLY, PermissionRules.requestStatus(granted = false, requestedBefore = true, showRationale = false))
    }

    @Test
    fun preciseLocationIsGrantedOnlyWithFineLocation() {
        assertEquals(
            PermissionUi(PermissionStatus.GRANTED, PermissionAction.NONE),
            PermissionRules.location(fineGranted = true, coarseGranted = true, requestedBefore = false, showRationale = false),
        )
        val approximate = PermissionRules.location(fineGranted = false, coarseGranted = true, requestedBefore = false, showRationale = false)
        assertEquals(PermissionStatus.NOT_REQUESTED, approximate.status)
        assertEquals(PermissionAction.REQUEST, approximate.action)
        assertTrue(approximate.approximateOnly)
    }

    @Test
    fun aRefusedLocationAsksAgainUntilAndroidStopsAskingThenOpensAppSettings() {
        val once = PermissionRules.location(fineGranted = false, coarseGranted = false, requestedBefore = true, showRationale = true)
        assertEquals(PermissionUi(PermissionStatus.DENIED, PermissionAction.REQUEST), once)
        assertFalse(once.approximateOnly)

        val never = PermissionRules.location(fineGranted = false, coarseGranted = false, requestedBefore = true, showRationale = false)
        assertEquals(PermissionUi(PermissionStatus.DENIED_PERMANENTLY, PermissionAction.OPEN_APP_SETTINGS), never)
    }

    @Test
    fun belowAndroid13NotificationsAreTheAppsSwitchOnly() {
        assertEquals(
            PermissionUi(PermissionStatus.GRANTED, PermissionAction.NONE),
            PermissionRules.notifications(sdkInt = 31, permissionGranted = false, notificationsEnabled = true, requestedBefore = false, showRationale = false),
        )
        assertEquals(
            PermissionUi(PermissionStatus.DENIED_PERMANENTLY, PermissionAction.OPEN_NOTIFICATION_SETTINGS, turnedOffInSettings = true),
            PermissionRules.notifications(sdkInt = 32, permissionGranted = false, notificationsEnabled = false, requestedBefore = false, showRationale = false),
        )
    }

    @Test
    fun fromAndroid13ThePermissionComesFirstThenTheSwitch() {
        assertEquals(
            PermissionUi(PermissionStatus.NOT_REQUESTED, PermissionAction.REQUEST),
            PermissionRules.notifications(sdkInt = 33, permissionGranted = false, notificationsEnabled = false, requestedBefore = false, showRationale = false),
        )
        assertEquals(
            PermissionUi(PermissionStatus.DENIED, PermissionAction.REQUEST),
            PermissionRules.notifications(sdkInt = 36, permissionGranted = false, notificationsEnabled = false, requestedBefore = true, showRationale = true),
        )
        assertEquals(
            PermissionUi(PermissionStatus.DENIED_PERMANENTLY, PermissionAction.OPEN_NOTIFICATION_SETTINGS),
            PermissionRules.notifications(sdkInt = 36, permissionGranted = false, notificationsEnabled = false, requestedBefore = true, showRationale = false),
        )
        assertEquals(
            PermissionUi(PermissionStatus.DENIED_PERMANENTLY, PermissionAction.OPEN_NOTIFICATION_SETTINGS, turnedOffInSettings = true),
            PermissionRules.notifications(sdkInt = 36, permissionGranted = true, notificationsEnabled = false, requestedBefore = false, showRationale = false),
        )
        assertEquals(
            PermissionUi(PermissionStatus.GRANTED, PermissionAction.NONE),
            PermissionRules.notifications(sdkInt = 36, permissionGranted = true, notificationsEnabled = true, requestedBefore = false, showRationale = false),
        )
    }

    @Test
    fun thePhonePermissionIsAskedForUntilRefusedForGood() {
        assertEquals(PermissionUi(PermissionStatus.GRANTED, PermissionAction.NONE), PermissionRules.phone(granted = true, requestedBefore = false, showRationale = false))
        assertEquals(PermissionUi(PermissionStatus.NOT_REQUESTED, PermissionAction.REQUEST), PermissionRules.phone(granted = false, requestedBefore = false, showRationale = false))
        assertEquals(PermissionUi(PermissionStatus.DENIED, PermissionAction.REQUEST), PermissionRules.phone(granted = false, requestedBefore = true, showRationale = true))
        assertEquals(
            PermissionUi(PermissionStatus.DENIED_PERMANENTLY, PermissionAction.OPEN_APP_SETTINGS),
            PermissionRules.phone(granted = false, requestedBefore = true, showRationale = false),
        )
    }
}
