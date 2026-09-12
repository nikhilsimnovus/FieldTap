package com.fieldtap.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpdumpParserTest {

    @Test
    fun presentFromAWhichHit() {
        val tooling = TcpdumpParser.parse("/system/bin/tcpdump\n", emptyList(), anyCaptureInterface = true)

        assertTrue(tooling.tcpdumpPresent)
        assertEquals(listOf("/system/bin/tcpdump"), tooling.tcpdumpPaths)
        assertTrue(tooling.pcapCapableInterfacePresent)
    }

    @Test
    fun presentFromAKnownPathHitAlone() {
        val tooling = TcpdumpParser.parse(null, listOf("/data/local/tmp/tcpdump"), anyCaptureInterface = false)

        assertTrue(tooling.tcpdumpPresent)
        assertEquals(listOf("/data/local/tmp/tcpdump"), tooling.tcpdumpPaths)
        assertFalse(tooling.pcapCapableInterfacePresent)
    }

    @Test
    fun pathsAreTheDeDuplicatedUnion() {
        val tooling = TcpdumpParser.parse(
            whichOutput = "/system/bin/tcpdump\n/vendor/bin/tcpdump",
            knownPathHits = listOf("/system/bin/tcpdump", "/data/local/tmp/tcpdump"),
            anyCaptureInterface = true,
        )

        assertEquals(
            listOf("/system/bin/tcpdump", "/vendor/bin/tcpdump", "/data/local/tmp/tcpdump"),
            tooling.tcpdumpPaths,
        )
    }

    @Test
    fun notPresentWhenNothingResolves() {
        val tooling = TcpdumpParser.parse("tcpdump: not found\n", emptyList(), anyCaptureInterface = true)

        assertFalse(tooling.tcpdumpPresent)
        assertEquals(emptyList<String>(), tooling.tcpdumpPaths)
    }

    @Test
    fun pcapCapableComesStraightFromTheInterfaceFlag() {
        assertFalse(TcpdumpParser.parse(null, emptyList(), anyCaptureInterface = false).pcapCapableInterfacePresent)
        assertTrue(TcpdumpParser.parse(null, emptyList(), anyCaptureInterface = true).pcapCapableInterfacePresent)
    }
}
