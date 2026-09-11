package com.fieldtap.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDirNameTest {

    @Test
    fun slugsMatchThePythonExamples() {
        assertEquals("Mall-walk-north-path", SessionDirName.slugify("Mall walk (north path)"))
        assertEquals("Caf-lobby", SessionDirName.slugify("Caf" + ch(0xE9) + " " + ch(0x2013) + " lobby"))
        assertEquals("session", SessionDirName.slugify("   "))
    }

    @Test
    fun slugsKeepAllowedCharactersAndStripHyphensAtBothEnds() {
        assertEquals("Hello__World..", SessionDirName.slugify(" --Hello__World.. "))
        assertEquals("a.b_c-d", SessionDirName.slugify("a.b_c-d"))
        assertEquals("moji-walk", SessionDirName.slugify(ch(0xE9) + "moji " + ch(0x1F6B6) + " walk"))
        assertEquals("north-path-B", SessionDirName.slugify("north\tpath\nB"))
        assertEquals("x", SessionDirName.slugify(ch(0x3000) + "x" + ch(0xA0)))
    }

    @Test
    fun anEmptySlugBecomesSession() {
        assertEquals("session", SessionDirName.slugify(""))
        assertEquals("session", SessionDirName.slugify("(!)"))
    }

    @Test
    fun slugsAreCutAt48CharactersEvenWhenThatLeavesAHyphen() {
        assertEquals("a".repeat(48), SessionDirName.slugify("a".repeat(60)))
        val name = "b".repeat(47) + " c" + "d".repeat(11)
        assertEquals(60, name.length)
        assertEquals("b".repeat(47) + "-", SessionDirName.slugify(name))
    }

    @Test
    fun directoryNamesUseTheStartSecondInUtcTruncated() {
        assertEquals(Golden.DIR_NAME, SessionDirName.of(Golden.STARTED_UTC_MS, "Mall walk (north path)"))
        assertEquals(Golden.DIR_NAME, SessionDirName.of(Golden.at(999), "Mall walk (north path)"))
        assertEquals("20260910-143001_session", SessionDirName.of(Golden.at(1_000), "  "))
        assertEquals(Golden.DIR_NAME, Golden.sessionDir.name)
        assertTrue(SessionDirName.PATTERN.matches(SessionDirName.of(Golden.STARTED_UTC_MS, "x".repeat(200))))
    }

    @Test
    fun directoryNamesIgnoreTheDefaultLocale() {
        for (tag in TRICKY_LOCALES) {
            withDefaultLocale(tag) {
                assertEquals(tag, Golden.DIR_NAME, SessionDirName.of(Golden.STARTED_UTC_MS, "Mall walk (north path)"))
                assertEquals(tag, Golden.STARTED_UTC_MS, SessionDirName.startedSecondMs(Golden.DIR_NAME))
            }
        }
    }

    @Test
    fun theStartSecondIsReadBackFromAName() {
        assertEquals(Golden.STARTED_UTC_MS, SessionDirName.startedSecondMs(Golden.DIR_NAME))
        assertEquals(0L, SessionDirName.startedSecondMs("19700101-000000_x"))
        for (ms in listOf(0L, 946_684_800_123L, Golden.at(750), 4_102_444_799_999L)) {
            assertEquals(ms / 1000 * 1000, SessionDirName.startedSecondMs(SessionDirName.of(ms, "walk")))
        }
    }

    @Test
    fun namesThatDoNotMatchHaveNoStartSecond() {
        val refused = listOf(
            "20261310-143000_x",
            "20260230-120000_x",
            "20260910-240000_x",
            "20260910-143000_",
            "20260910-143000_" + "a".repeat(49),
            "20260910-143000_Mall walk",
            "x20260910-143000_a",
            "20260910-143000_a\n",
            "2026910-143000_a",
        )
        for (name in refused) assertNull(name, SessionDirName.startedSecondMs(name))
    }
}
