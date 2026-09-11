package com.fieldtap.core.radio

import com.fieldtap.core.radio.RadioFixtures.displayInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTypeNamesTest {

    @Test
    fun everyNetworkTypeOfTheAndroidJarIsNamedWithoutItsPrefix() {
        val types = constants(TELEPHONY_MANAGER, NETWORK_TYPE_PREFIX)
        assertTrue("NETWORK_TYPE_* constants in ${AndroidSdkConstants.androidJar}: $types", types.size >= 20)
        for ((name, value) in types) {
            assertEquals(name, name.removePrefix(NETWORK_TYPE_PREFIX), NetworkTypeNames.networkType(value))
        }
    }

    @Test
    fun aNetworkTypeTheAndroidJarDoesNotDefineIsUnknown() {
        val defined = constants(TELEPHONY_MANAGER, NETWORK_TYPE_PREFIX).values.toSet()
        for (value in -1..64) {
            if (value !in defined) {
                assertEquals("network type $value", NetworkTypeNames.UNKNOWN, NetworkTypeNames.networkType(value))
            }
        }
        // 19 is the hidden NETWORK_TYPE_LTE_CA, never a public value.
        assertEquals(NetworkTypeNames.UNKNOWN, NetworkTypeNames.networkType(19))
        assertEquals(NetworkTypeNames.UNKNOWN, NetworkTypeNames.networkType(Int.MAX_VALUE))
    }

    @Test
    fun everyOverrideTypeOfTheAndroidJarIsNamedWithoutItsPrefix() {
        val overrides = constants(TELEPHONY_DISPLAY_INFO, OVERRIDE_PREFIX)
        assertTrue("OVERRIDE_NETWORK_TYPE_* constants in ${AndroidSdkConstants.androidJar}: $overrides", overrides.size >= 6)
        for ((name, value) in overrides) {
            assertEquals(name, name.removePrefix(OVERRIDE_PREFIX), NetworkTypeNames.overrideType(value))
        }
        val defined = overrides.values.toSet()
        for (value in -1..32) {
            if (value !in defined) {
                assertEquals("override type $value", NetworkTypeNames.UNKNOWN, NetworkTypeNames.overrideType(value))
            }
        }
    }

    @Test
    fun theNamedConstantsAreAndroidsValues() {
        val types = AndroidSdkConstants.intConstants(TELEPHONY_MANAGER)
        assertEquals(types.getValue("NETWORK_TYPE_LTE"), NetworkTypeNames.NETWORK_TYPE_LTE)
        assertEquals(types.getValue("NETWORK_TYPE_NR"), NetworkTypeNames.NETWORK_TYPE_NR)
        val overrides = AndroidSdkConstants.intConstants(TELEPHONY_DISPLAY_INFO)
        assertEquals(overrides.getValue("OVERRIDE_NETWORK_TYPE_NR_NSA"), NetworkTypeNames.OVERRIDE_NR_NSA)
        assertEquals(overrides.getValue("OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE"), NetworkTypeNames.OVERRIDE_NR_NSA_MMWAVE)
        assertEquals(overrides.getValue("OVERRIDE_NETWORK_TYPE_NR_ADVANCED"), NetworkTypeNames.OVERRIDE_NR_ADVANCED)
    }

    @Test
    fun theNamesAreTheOnesEventDetailsAndTheHandsetUse() {
        assertEquals("UNKNOWN", NetworkTypeNames.networkType(0))
        assertEquals("1xRTT", NetworkTypeNames.networkType(7))
        assertEquals("LTE", NetworkTypeNames.networkType(13))
        assertEquals("TD_SCDMA", NetworkTypeNames.networkType(17))
        assertEquals("IWLAN", NetworkTypeNames.networkType(18))
        assertEquals("NR", NetworkTypeNames.networkType(20))
        assertEquals("NONE", NetworkTypeNames.overrideType(0))
        assertEquals("LTE_ADVANCED_PRO", NetworkTypeNames.overrideType(2))
        assertEquals("NR_NSA", NetworkTypeNames.overrideType(3))
        assertEquals("UNKNOWN", NetworkTypeNames.overrideType(6))
    }

    @Test
    fun theFiveGIconShowsForAnNrOverrideOrAnNrNetwork() {
        for (value in listOf(3, 4, 5)) {
            assertTrue("LTE with override $value", NetworkTypeNames.shows5g(displayInfo(0, 13, value)))
        }
        assertTrue(NetworkTypeNames.shows5g(displayInfo(0, 20, 0)))
        assertTrue(NetworkTypeNames.shows5g(displayInfo(0, 20, 1)))
        for (value in listOf(-1, 0, 1, 2, 6)) {
            assertFalse("LTE with override $value", NetworkTypeNames.shows5g(displayInfo(0, 13, value)))
        }
        assertFalse(NetworkTypeNames.shows5g(displayInfo(0, 0, 0)))
        assertFalse(NetworkTypeNames.shows5g(displayInfo(0, 18, 0)))
    }

    private fun constants(className: String, prefix: String): Map<String, Int> =
        AndroidSdkConstants.intConstants(className).filterKeys { it.startsWith(prefix) }

    private companion object {
        const val TELEPHONY_MANAGER: String = "android.telephony.TelephonyManager"
        const val TELEPHONY_DISPLAY_INFO: String = "android.telephony.TelephonyDisplayInfo"
        const val NETWORK_TYPE_PREFIX: String = "NETWORK_TYPE_"
        const val OVERRIDE_PREFIX: String = "OVERRIDE_NETWORK_TYPE_"
    }
}
