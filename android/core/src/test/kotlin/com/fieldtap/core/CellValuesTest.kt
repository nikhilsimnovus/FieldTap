package com.fieldtap.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CellValuesTest {

    @Test
    fun unavailableIsWrittenBlankNeverAsTheSentinel() {
        assertEquals("", CellValues.csvCell(2147483647))
        assertEquals("-97", CellValues.csvCell(-97))
    }
}
