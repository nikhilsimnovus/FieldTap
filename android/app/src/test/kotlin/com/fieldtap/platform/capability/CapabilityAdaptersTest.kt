package com.fieldtap.platform.capability

import com.fieldtap.core.capability.RootDetector
import com.fieldtap.core.capability.UsbDebugState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the pure mapping helpers of the `:app` capability adapters (no Android calls). */
class CapabilityAdaptersTest {

    // ---- PropReader.parse ----

    @Test
    fun propReaderKeepsOnlyWantedKeysAndParsesBracketedValues() {
        val raw = listOf(
            "[ro.debuggable]: [1]",
            "[ro.secure]: [0]",
            "[ro.build.tags]: [release-keys]",
            "[ro.product.model]: [Pixel 8]",
            "[persist.sys.something]: [foo]",
        ).joinToString("\n")

        val props = PropReader.parse(raw, RootDetector.PropKeys)

        assertEquals("1", props["ro.debuggable"])
        assertEquals("0", props["ro.secure"])
        assertEquals("release-keys", props["ro.build.tags"])
        assertFalse(props.containsKey("ro.product.model"))
        assertFalse(props.containsKey("persist.sys.something"))
    }

    @Test
    fun propReaderHandlesEmptyValuesAndKeepsTheFirstOfADuplicate() {
        val raw = listOf(
            "[ro.build.tags]: []",
            "[ro.debuggable]: [1]",
            "[ro.debuggable]: [0]",
            "garbage line",
        ).joinToString("\n")

        val props = PropReader.parse(raw, RootDetector.PropKeys)

        assertEquals("", props["ro.build.tags"])
        assertEquals("1", props["ro.debuggable"])
    }

    // ---- SuBinaryScanner.debugRamSuCandidates ----

    @Test
    fun debugRamCandidatesMatchThePrefixOnly() {
        val entries = listOf("debug_ram", "debug_ram2", "debug_ramoops", "data", "system", "sbin")

        assertEquals(
            listOf("/debug_ram/su", "/debug_ram2/su", "/debug_ramoops/su"),
            SuBinaryScanner.debugRamSuCandidates(entries),
        )
        assertEquals(emptyList<String>(), SuBinaryScanner.debugRamSuCandidates(listOf("data", "system")))
    }

    // ---- SettingsReader.usbState / mockLocationSet ----

    @Test
    fun usbStateMapsEachIntToOnlyExactlyOne() {
        assertEquals(UsbDebugState(true, true, true), SettingsReader.usbState(1, 1, 1))
        assertEquals(UsbDebugState(false, false, false), SettingsReader.usbState(0, 0, 0))
        assertEquals(UsbDebugState(true, false, false), SettingsReader.usbState(1, 0, 0))
        // Anything other than exactly 1 is off.
        assertEquals(UsbDebugState(false, false, false), SettingsReader.usbState(2, -1, 7))
    }

    @Test
    fun mockLocationSetIsNonZero() {
        assertFalse(SettingsReader.mockLocationSet(0))
        assertTrue(SettingsReader.mockLocationSet(1))
        assertTrue(SettingsReader.mockLocationSet(2))
    }

    // ---- PackageScanner.scan / WritablePathProbe.scan ----

    @Test
    fun packageScanKeepsThePresentOnesInOrder() {
        val candidates = listOf("a.pkg", "b.pkg", "c.pkg")
        val present = setOf("a.pkg", "c.pkg")

        assertEquals(listOf("a.pkg", "c.pkg"), PackageScanner.scan(candidates) { it in present })
        assertEquals(emptyList<String>(), PackageScanner.scan(candidates) { false })
    }

    @Test
    fun writablePathScanKeepsTheWritableOnesInOrder() {
        val candidates = listOf("/system", "/vendor", "/data/local")
        val writable = setOf("/data/local")

        assertEquals(listOf("/data/local"), WritablePathProbe.scan(candidates) { it in writable })
        assertEquals(emptyList<String>(), WritablePathProbe.scan(candidates) { false })
    }
}
