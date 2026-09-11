package com.fieldtap.platform.device

import android.content.Intent
import android.os.BatteryManager
import android.view.Display
import com.fieldtap.core.input.DeviceConditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceValuesTest {

    @Test
    fun theScreenIsOnOnlyWhileADisplayIsFullyOn() {
        // Display: 1 off, 2 on, 3 doze, 4 doze suspend, 6 on suspend.
        assertTrue(DeviceValues.screenOn(listOf(Display.STATE_ON)))
        assertTrue(DeviceValues.screenOn(listOf(Display.STATE_OFF, Display.STATE_ON)))
        // In a call the proximity sensor turns the display off while the phone stays interactive: Android is at 10 s.
        assertFalse(DeviceValues.screenOn(listOf(Display.STATE_OFF)))
        assertFalse(DeviceValues.screenOn(listOf(Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND, Display.STATE_ON_SUSPEND)))
        assertFalse(DeviceValues.screenOn(emptyList()))
    }

    @Test
    fun chargingFollowsAndroidsChargingBroadcastsAndNotThePlug() {
        val start = DeviceConditions(screenOn = true, charging = false, wifiConnected = true)

        assertEquals(start.copy(charging = true), DeviceValues.afterBroadcast(BatteryManager.ACTION_CHARGING, start))
        assertEquals(start, DeviceValues.afterBroadcast(BatteryManager.ACTION_DISCHARGING, start.copy(charging = true)))
        // Plugged in below 90 %, Android counts the phone as charging only once the battery level rises.
        assertNull(DeviceValues.afterBroadcast(Intent.ACTION_POWER_CONNECTED, start))
        assertNull(DeviceValues.afterBroadcast(Intent.ACTION_POWER_DISCONNECTED, start.copy(charging = true)))
    }

    @Test
    fun otherBroadcastsChangeNothing() {
        val start = DeviceConditions(screenOn = true, charging = true, wifiConnected = false)

        assertNull(DeviceValues.afterBroadcast(Intent.ACTION_SCREEN_OFF, start))
        assertNull(DeviceValues.afterBroadcast(Intent.ACTION_BATTERY_CHANGED, start))
        assertNull(DeviceValues.afterBroadcast("com.example.OTHER", start))
    }

    @Test
    fun theBroadcastActionsAndDisplayStatesAreAndroids() {
        assertEquals("android.os.action.CHARGING", BatteryManager.ACTION_CHARGING)
        assertEquals("android.os.action.DISCHARGING", BatteryManager.ACTION_DISCHARGING)
        assertEquals(2, Display.STATE_ON)
        assertEquals(1, Display.STATE_OFF)
    }
}
