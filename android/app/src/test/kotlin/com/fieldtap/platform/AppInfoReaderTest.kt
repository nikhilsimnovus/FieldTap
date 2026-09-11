package com.fieldtap.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class AppInfoReaderTest {

    @Test
    fun aDeclaredVersionNameIsKeptAsIs() {
        assertEquals("0.1.0", AppInfoReader.versionNameOf("0.1.0"))
        assertEquals("1.2.0-beta 3", AppInfoReader.versionNameOf("1.2.0-beta 3"))
    }

    @Test
    fun aMissingOrBlankVersionNameIsUnknownSoAppVersionIsNeverBlank() {
        for (raw in listOf(null, "", "   ")) {
            assertEquals(AppInfoReader.UNKNOWN_VERSION, AppInfoReader.versionNameOf(raw))
        }
        assertEquals("unknown", AppInfoReader.UNKNOWN_VERSION)
    }
}
