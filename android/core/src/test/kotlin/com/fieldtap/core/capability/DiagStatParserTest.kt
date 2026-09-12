package com.fieldtap.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagStatParserTest {

    @Test
    fun parsesACharacterDeviceStatLine() {
        val node = DiagStatParser.parse("660 radio radio character special file")

        assertEquals("/dev/diag", node.path)
        assertTrue(node.exists)
        assertTrue(node.charDevice)
        assertEquals("660", node.octalMode)
        assertEquals("radio", node.ownerUser)
        assertEquals("radio", node.ownerGroup)
    }

    @Test
    fun noSuchFileIsAbsent() {
        for (line in listOf(
            "stat: '/dev/diag': No such file or directory",
            "stat: cannot stat '/dev/diag': No such file or directory",
            "ls: cannot access '/dev/diag': No such file or directory",
        )) {
            val node = DiagStatParser.parse(line)
            assertFalse(line, node.exists)
            assertNull(node.octalMode)
            assertNull(node.ownerUser)
        }
    }

    @Test
    fun permissionDeniedKeepsExistsButHidesTheMode() {
        val node = DiagStatParser.parse("stat: '/dev/diag': Permission denied")

        assertTrue(node.exists)
        assertNull("a denial hides the mode", node.octalMode)
        assertNull(node.ownerUser)
    }

    @Test
    fun emptyOrGarbageIsAbsent() {
        assertFalse(DiagStatParser.parse(null).exists)
        assertFalse(DiagStatParser.parse("").exists)
        assertFalse(DiagStatParser.parse("something unexpected").exists)
    }

    @Test
    fun aBlockDeviceStatIsNotACharDevice() {
        val node = DiagStatParser.parse("660 root system block special file")

        assertTrue(node.exists)
        assertFalse(node.charDevice)
        assertEquals("660", node.octalMode)
    }
}
