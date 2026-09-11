package com.fieldtap.ui.setup

import com.fieldtap.ui.components.PermissionStatus

/** What a permission's button does. */
internal enum class PermissionAction {
    /** Nothing to do: the permission is granted. */
    NONE,

    /** Ask Android with the runtime permission dialog. */
    REQUEST,

    /** Android will not ask again: open the app's details screen. */
    OPEN_APP_SETTINGS,

    /** Open the app's notification settings, where notifications are switched on. */
    OPEN_NOTIFICATION_SETTINGS,
}

/**
 * Where a permission stands on screen.
 *
 * @param approximateOnly location only: the user allowed approximate but not precise location, which returns no
 *   cell information.
 * @param turnedOffInSettings notifications only: the permission is not the problem, the app's notifications are
 *   switched off in Android's settings.
 */
internal data class PermissionUi(
    val status: PermissionStatus,
    val action: PermissionAction,
    val approximateOnly: Boolean = false,
    val turnedOffInSettings: Boolean = false,
)

/**
 * The permission decisions of the Permissions and Settings screens, pure so they are unit-tested.
 *
 * Android does not say whether a permission was ever asked for. `shouldShowRequestPermissionRationale` is true
 * after one refusal and false both before the first request and after a permanent refusal. So a refusal counts as
 * permanent only when this screen has already asked ([requestStatus]'s `requestedBefore`) and Android shows no
 * rationale. A permission refused permanently in an earlier launch therefore first shows "Allow"; the tap returns
 * at once without a dialog, and the button then opens settings.
 *
 * Owner: workstream `ui-setup`.
 */
internal object PermissionRules {
    /** Android 13, from which POST_NOTIFICATIONS is a runtime permission. */
    const val NOTIFICATIONS_RUNTIME_SDK: Int = 33

    fun requestStatus(granted: Boolean, requestedBefore: Boolean, showRationale: Boolean): PermissionStatus = when {
        granted -> PermissionStatus.GRANTED
        showRationale -> PermissionStatus.DENIED
        requestedBefore -> PermissionStatus.DENIED_PERMANENTLY
        else -> PermissionStatus.NOT_REQUESTED
    }

    /**
     * Precise location, requested as fine and coarse together. Granted only with fine location; coarse alone is
     * [PermissionUi.approximateOnly] and asks again, which Android shows as the upgrade to precise.
     */
    fun location(
        fineGranted: Boolean,
        coarseGranted: Boolean,
        requestedBefore: Boolean,
        showRationale: Boolean,
    ): PermissionUi {
        if (fineGranted) return PermissionUi(PermissionStatus.GRANTED, PermissionAction.NONE)
        val status = requestStatus(granted = false, requestedBefore = requestedBefore, showRationale = showRationale)
        val action = if (status == PermissionStatus.DENIED_PERMANENTLY) PermissionAction.OPEN_APP_SETTINGS else PermissionAction.REQUEST
        return PermissionUi(status, action, approximateOnly = coarseGranted)
    }

    /**
     * Notifications. Below Android 13 there is no runtime permission, only the app's notification switch. From
     * Android 13 the permission comes first; with it granted, the switch still decides. A switched-off app, or a
     * permanent refusal, links to the app's notification settings.
     */
    fun notifications(
        sdkInt: Int,
        permissionGranted: Boolean,
        notificationsEnabled: Boolean,
        requestedBefore: Boolean,
        showRationale: Boolean,
    ): PermissionUi {
        if (sdkInt < NOTIFICATIONS_RUNTIME_SDK || permissionGranted) {
            return if (notificationsEnabled) {
                PermissionUi(PermissionStatus.GRANTED, PermissionAction.NONE)
            } else {
                PermissionUi(
                    PermissionStatus.DENIED_PERMANENTLY,
                    PermissionAction.OPEN_NOTIFICATION_SETTINGS,
                    turnedOffInSettings = true,
                )
            }
        }
        val status = requestStatus(granted = false, requestedBefore = requestedBefore, showRationale = showRationale)
        val action = if (status == PermissionStatus.DENIED_PERMANENTLY) {
            PermissionAction.OPEN_NOTIFICATION_SETTINGS
        } else {
            PermissionAction.REQUEST
        }
        return PermissionUi(status, action)
    }

    /** The Phone permission behind "Instant cell updates" (android/ARCHITECTURE.md decision 9). */
    fun phone(granted: Boolean, requestedBefore: Boolean, showRationale: Boolean): PermissionUi {
        val status = requestStatus(granted, requestedBefore, showRationale)
        val action = when (status) {
            PermissionStatus.GRANTED -> PermissionAction.NONE
            PermissionStatus.DENIED_PERMANENTLY -> PermissionAction.OPEN_APP_SETTINGS
            PermissionStatus.NOT_REQUESTED, PermissionStatus.DENIED -> PermissionAction.REQUEST
        }
        return PermissionUi(status, action)
    }
}
