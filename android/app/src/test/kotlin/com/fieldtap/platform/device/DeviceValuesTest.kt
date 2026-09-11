package com.fieldtap.platform.device

import android.content.Intent
import com.fieldtap.core.input.DeviceConditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceValuesTest {

    @Test
    fun chargingMeansChargingOrFullOrPluggedIn() {
        // BatteryManager: status 1 unknown, 2 charging, 3 discharging, 4 not charging, 5 full;
        // plugged 0 none, 1 AC, 2 USB, 4 wireless.
        assertTrue(DeviceValues.charging(status = 2, plugged = 0))
        assertTrue(DeviceValues.charging(status = 5, plugged = 0))
        assertTrue(DeviceValues.charging(status = 4, plugged = 1))
        assertTrue(DeviceValues.charging(status = 3, plugged = 2))
        assertTrue(DeviceValues.charging(status = 3, plugged = 4))
        assertFalse(DeviceValues.charging(status = 3, plugged = 0))
        assertFalse(DeviceValues.charging(status = 1, plugged = 0))
        assertFalse(DeviceValues.charging(status = -1, plugged = 0))
    }

    @Test
    fun screenAndPowerBroadcastsChangeOnlyTheirOwnField() {
        val start = DeviceConditions(screenOn = false, charging = false, wifiConnected = true)

        assertEquals(start.copy(screenOn = true), DeviceValues.afterBroadcast(Intent.ACTION_SCREEN_ON, start))
        assertEquals(start, DeviceValues.afterBroadcast(Intent.ACTION_SCREEN_OFF, start.copy(screenOn = true)))
        assertEquals(start.copy(charging = true), DeviceValues.afterBroadcast(Intent.ACTION_POWER_CONNECTED, start))
        assertEquals(start, DeviceValues.afterBroadcast(Intent.ACTION_POWER_DISCONNECTED, start.copy(charging = true)))
    }

    @Test
    fun otherBroadcastsChangeNothing() {
        val start = DeviceConditions(screenOn = true, charging = true, wifiConnected = false)

        assertNull(DeviceValues.afterBroadcast(Intent.ACTION_BATTERY_CHANGED, start))
        assertNull(DeviceValues.afterBroadcast("com.example.OTHER", start))
    }

    @Test
    fun theBroadcastActionsAreAndroidsStrings() {
        assertEquals("android.intent.action.SCREEN_ON", Intent.ACTION_SCREEN_ON)
        assertEquals("android.intent.action.SCREEN_OFF", Intent.ACTION_SCREEN_OFF)
        assertEquals("android.intent.action.ACTION_POWER_CONNECTED", Intent.ACTION_POWER_CONNECTED)
        assertEquals("android.intent.action.ACTION_POWER_DISCONNECTED", Intent.ACTION_POWER_DISCONNECTED)
    }
}
