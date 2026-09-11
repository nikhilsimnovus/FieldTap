package com.fieldtap.core.radio

import com.fieldtap.core.input.DeviceConditions
import com.fieldtap.format.Schema
import org.junit.Assert.assertEquals
import org.junit.Test

class CadencePolicyTest {

    private data class Row(val screenOn: Boolean, val charging: Boolean, val wifiConnected: Boolean, val short: Boolean)

    @Test
    fun allEightCombinationsOfScreenChargingAndWifi() {
        val table = listOf(
            Row(screenOn = false, charging = false, wifiConnected = false, short = false),
            Row(screenOn = false, charging = false, wifiConnected = true, short = false),
            Row(screenOn = false, charging = true, wifiConnected = false, short = false),
            Row(screenOn = false, charging = true, wifiConnected = true, short = false),
            Row(screenOn = true, charging = false, wifiConnected = false, short = true),
            Row(screenOn = true, charging = false, wifiConnected = true, short = false),
            Row(screenOn = true, charging = true, wifiConnected = false, short = true),
            Row(screenOn = true, charging = true, wifiConnected = true, short = true),
        )
        for (row in table) {
            val conditions = DeviceConditions(row.screenOn, row.charging, row.wifiConnected)
            assertEquals("$row", row.short, CadencePolicy.isShortInterval(conditions))
            assertEquals("$row", if (row.short) 2_000L else 10_000L, CadencePolicy.intervalMs(conditions))
            assertEquals("$row", if (row.short) 2_500L else 11_000L, CadencePolicy.maxKpiAgeMs(conditions))
        }
    }

    @Test
    fun theLongAgeLimitIsTheSchemaLimit() {
        assertEquals(Schema.KPI_MAX_AGE_MS, CadencePolicy.LONG_MAX_AGE_MS)
        assertEquals(1_000L, CadencePolicy.REQUEST_PERIOD_MS)
    }
}
