package com.fieldtap.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootDetectorTest {

    @Test
    fun suBinaryAloneIsHigh() {
        val signals = RootDetector.assess(inputs(suBinaries = listOf("/system/xbin/su")))

        assertEquals(RootConfidence.HIGH, signals.confidence)
        assertEquals(listOf("/system/xbin/su"), signals.suBinariesPresent)
    }

    @Test
    fun managerPackageAloneIsHigh() {
        val signals = RootDetector.assess(inputs(managers = listOf("com.topjohnwu.magisk")))

        assertEquals(RootConfidence.HIGH, signals.confidence)
        assertEquals(listOf("com.topjohnwu.magisk"), signals.rootManagerPackages)
    }

    @Test
    fun testKeysWithDebuggableIsMedium() {
        val signals = RootDetector.assess(inputs(buildTags = "test-keys", props = mapOf("ro.debuggable" to "1")))

        assertEquals(RootConfidence.MEDIUM, signals.confidence)
        assertTrue(signals.buildTagsTestKeys)
        assertTrue(signals.debuggable)
    }

    @Test
    fun testKeysWithSecureOffIsMedium() {
        val signals = RootDetector.assess(inputs(buildTags = "test-keys", props = mapOf("ro.secure" to "0")))

        assertEquals(RootConfidence.MEDIUM, signals.confidence)
        assertTrue(signals.secureOff)
    }

    @Test
    fun writablePathIsMedium() {
        val signals = RootDetector.assess(inputs(writable = listOf("/system")))

        assertEquals(RootConfidence.MEDIUM, signals.confidence)
        assertEquals(listOf("/system"), signals.writableSystemPaths)
    }

    @Test
    fun testKeysAloneIsLow() {
        val signals = RootDetector.assess(inputs(buildTags = "test-keys"))

        assertEquals(RootConfidence.LOW, signals.confidence)
        assertTrue(signals.buildTagsTestKeys)
        assertFalse(signals.debuggable)
    }

    @Test
    fun debuggableAloneIsLow() {
        val signals = RootDetector.assess(inputs(props = mapOf("ro.debuggable" to "1")))

        assertEquals(RootConfidence.LOW, signals.confidence)
        assertTrue(signals.debuggable)
    }

    @Test
    fun nothingIsNone() {
        val signals = RootDetector.assess(inputs())

        assertEquals(RootConfidence.NONE, signals.confidence)
        assertFalse(signals.buildTagsTestKeys)
        assertFalse(signals.debuggable)
        assertFalse(signals.secureOff)
    }

    @Test
    fun theCaveatIsAlwaysPresent() {
        for (signals in listOf(
            RootDetector.assess(inputs(suBinaries = listOf("/sbin/su"))),
            RootDetector.assess(inputs(buildTags = "test-keys")),
            RootDetector.assess(inputs()),
        )) {
            assertEquals(RootDetector.CAVEAT, signals.caveat)
            assertTrue(signals.caveat.contains("DenyList"))
            assertTrue(signals.caveat.contains("not proof"))
        }
    }

    @Test
    fun debuggableIsOnlyTrueForExactlyOne() {
        assertFalse(RootDetector.assess(inputs(props = mapOf("ro.debuggable" to "0"))).debuggable)
        assertFalse(RootDetector.assess(inputs(props = mapOf("ro.secure" to "1"))).secureOff)
    }

    private fun inputs(
        props: Map<String, String> = emptyMap(),
        buildTags: String = "release-keys",
        suBinaries: List<String> = emptyList(),
        managers: List<String> = emptyList(),
        writable: List<String> = emptyList(),
    ): PassiveInputs = PassiveInputs(
        props = props,
        buildTags = buildTags,
        suBinariesPresent = suBinaries,
        rootManagerPackages = managers,
        writableSystemPaths = writable,
        usb = UsbDebugState(adbEnabled = false, wirelessDebugEnabled = false, developerOptionsEnabled = false),
        cellular = CellularReadout(
            readPhoneStateGranted = false,
            preciseLocationGranted = false,
            locationServicesEnabled = false,
            simReady = false,
            mockLocationAppSet = false,
            buildAcceptsMockLocations = false,
        ),
    )
}
