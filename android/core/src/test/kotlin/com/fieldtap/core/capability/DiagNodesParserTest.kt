package com.fieldtap.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagNodesParserTest {

    @Test
    fun parsesMultipleCharDevicesWithMetadataOnly() {
        val ls = listOf(
            "crw-rw---- 1 radio radio 10, 60 2026-09-11 08:00 /dev/diag_mdlog",
            "crw------- 1 root root 240, 0 2026-09-11 08:00 /dev/ttyGS0",
        ).joinToString("\n")

        val nodes = DiagNodesParser.parse(ls)

        assertEquals(2, nodes.size)
        assertEquals("/dev/diag_mdlog", nodes[0].path)
        assertTrue(nodes[0].charDevice)
        assertEquals("660", nodes[0].octalMode)
        assertEquals("radio", nodes[0].ownerUser)
        assertEquals("radio", nodes[0].ownerGroup)
        assertEquals("/dev/ttyGS0", nodes[1].path)
        assertEquals("600", nodes[1].octalMode)
        assertEquals("root", nodes[1].ownerUser)
    }

    @Test
    fun theExactPrimaryNodeIsFilteredOut() {
        // /dev/diag is reported separately via DiagStatParser; it is dropped from the others list.
        val ls = listOf(
            "crw-rw---- 1 radio radio 10, 60 2026-09-11 08:00 /dev/diag",
            "crw-rw---- 1 radio radio 10, 61 2026-09-11 08:00 /dev/diag_router",
        ).joinToString("\n")

        val nodes = DiagNodesParser.parse(ls)

        assertEquals(1, nodes.size)
        assertEquals("/dev/diag_router", nodes[0].path)
    }

    @Test
    fun errorAndNoMatchLinesAreSkipped() {
        val ls = listOf(
            "ls: /dev/qcqmi*: No such file or directory",
            "ls: cannot access '/dev/ttyGS*': No such file or directory",
        ).joinToString("\n")

        assertEquals(emptyList<DiagNodeStat>(), DiagNodesParser.parse(ls))
        assertEquals(emptyList<DiagNodeStat>(), DiagNodesParser.parse(null))
        assertEquals(emptyList<DiagNodeStat>(), DiagNodesParser.parse(""))
    }

    @Test
    fun symbolicModeConvertsToOctal() {
        assertEquals("660", DiagNodesParser.symbolicToOctal("crw-rw----"))
        assertEquals("777", DiagNodesParser.symbolicToOctal("crwxrwxrwx"))
        assertEquals("644", DiagNodesParser.symbolicToOctal("-rw-r--r--"))
        // setuid/setgid/sticky map to the leading special digit.
        assertEquals("4755", DiagNodesParser.symbolicToOctal("-rwsr-xr-x"))
        assertEquals("1777", DiagNodesParser.symbolicToOctal("drwxrwxrwt"))
    }

    @Test
    fun aNodeStatNeverCarriesContent() {
        // The type has no field a byte of node content could be stored in — only metadata.
        val fields = DiagNodeStat::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("path", "exists", "charDevice", "octalMode", "ownerUser", "ownerGroup"), fields)
        assertFalse(fields.any { it.contains("content", ignoreCase = true) || it.contains("data", ignoreCase = true) })
    }
}
