package com.fieldtap.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModemInterfaceParserTest {

    @Test
    fun keepsOnlyRmnetAndQmuxAndQmi() {
        val list = "lo\nrmnet_data0\nrmnet_data1\nrmnet0\nqmux0\nqmi0\nwlan0\neth0\ndummy0"

        val modem = ModemInterfaceParser.parse(list)

        assertEquals(listOf("rmnet_data0", "rmnet_data1", "rmnet0", "qmux0", "qmi0"), modem.names)
        assertEquals(5, modem.count)
    }

    @Test
    fun recognisesMediaTekCcmni() {
        val modem = ModemInterfaceParser.parse("lo\nccmni0\nccmni1\nwlan0")

        assertEquals(listOf("ccmni0", "ccmni1"), modem.names)
        assertEquals(2, modem.count)
    }

    @Test
    fun spaceSeparatedListingAlsoWorksAndDeDuplicates() {
        val modem = ModemInterfaceParser.parse("rmnet_data0 rmnet_data0 qmux0")

        assertEquals(listOf("rmnet_data0", "qmux0"), modem.names)
        assertEquals(2, modem.count)
    }

    @Test
    fun emptyOrNullIsNone() {
        assertEquals(0, ModemInterfaceParser.parse(null).count)
        assertEquals(0, ModemInterfaceParser.parse("").count)
        assertEquals(0, ModemInterfaceParser.parse("lo\nwlan0\neth0").count)
    }

    @Test
    fun namesNeverCarryAnAddress() {
        val modem = ModemInterfaceParser.parse("rmnet_data0\nqmux0")
        for (name in modem.names) {
            assertFalse(name, name.contains(":"))
            assertFalse(name, name.contains("."))
            assertFalse(name, name.any { it == ' ' })
        }
    }

    @Test
    fun captureInterfaceIsAnyModemOrWlan() {
        assertTrue(ModemInterfaceParser.anyCaptureInterface("lo\nwlan0"))
        assertTrue(ModemInterfaceParser.anyCaptureInterface("lo\nrmnet_data0"))
        assertFalse(ModemInterfaceParser.anyCaptureInterface("lo\neth0"))
        assertFalse(ModemInterfaceParser.anyCaptureInterface(null))
    }
}
