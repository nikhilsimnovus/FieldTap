package com.fieldtap.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioLogParserTest {

    @Test
    fun readableWithACount() {
        val readout = RadioLogParser.parse("RLOGOK", "42")

        assertTrue(readout.readable)
        assertEquals(42, readout.lineCount)
    }

    @Test
    fun notReadableHasNoCount() {
        val readout = RadioLogParser.parse("RLOGNO", "0")

        assertFalse(readout.readable)
        assertNull("no count when not readable", readout.lineCount)
    }

    @Test
    fun readableButNonNumericCountIsNull() {
        val readout = RadioLogParser.parse("RLOGOK", "wc: not found")

        assertTrue(readout.readable)
        assertNull(readout.lineCount)
    }

    @Test
    fun negativeCountIsRejected() {
        assertNull(RadioLogParser.parse("RLOGOK", "-3").lineCount)
    }

    @Test
    fun whitespaceIsTolerated() {
        val readout = RadioLogParser.parse("  RLOGOK\n", " 5 ")

        assertTrue(readout.readable)
        assertEquals(5, readout.lineCount)
    }

    @Test
    fun nullFlagIsNotReadable() {
        assertFalse(RadioLogParser.parse(null, "5").readable)
    }

    @Test
    fun theReadoutTypeCannotHoldALogLine() {
        // Privacy invariant, enforced by the type: only a Boolean and an Int? — no field a log line fits in.
        val fields = RadioLogReadout::class.java.declaredFields.associate { it.name to it.type.simpleName }
        assertEquals(mapOf("readable" to "boolean", "lineCount" to "Integer"), fields)
    }
}
