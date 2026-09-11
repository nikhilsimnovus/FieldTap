package com.fieldtap.format

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionFormatTest {

    @Test
    fun utcValuesHaveMillisecondsAndAnExplicitOffset() {
        val instant = Instant.parse("2026-09-10T08:15:30.042Z")

        assertEquals("2026-09-10T08:15:30.042+00:00", SessionFormat.utc(instant))
    }

    @Test
    fun capabilitiesAlwaysWriteLayer3() {
        val json = Json.encodeToString(Capabilities.serializer(), Capabilities(layer3 = false))

        assertEquals("""{"layer3":false}""", json)
    }

    @Test
    fun utcKeepsMillisecondsOnWholeSeconds() {
        assertEquals("2026-09-10T14:30:00.000+00:00", SessionFormat.utc(Golden.STARTED_UTC_MS))
        assertEquals("1970-01-01T00:00:00.000+00:00", SessionFormat.utc(0L))
        assertEquals(29, SessionFormat.utc(Golden.at(400)).length)
    }

    @Test
    fun utcIgnoresTheDefaultLocale() {
        for (tag in TRICKY_LOCALES) {
            withDefaultLocale(tag) {
                assertEquals(tag, "2026-09-10T14:30:00.400+00:00", SessionFormat.utc(Golden.at(400)))
                assertEquals(tag, "2026-09-10T14:30:00.400+00:00", SessionFormat.utc(Instant.ofEpochMilli(Golden.at(400))))
            }
        }
    }

    @Test
    fun timeEpochIsBuiltFromIntegerMilliseconds() {
        assertEquals("1789050600.400", SessionFormat.timeEpoch(1_789_050_600_400L))
        assertEquals("1789050600.000", SessionFormat.timeEpoch(1_789_050_600_000L))
        assertEquals("1789050600.007", SessionFormat.timeEpoch(1_789_050_600_007L))
        assertEquals("946684800.000", SessionFormat.timeEpoch(946_684_800_000L))
        assertEquals("0.000", SessionFormat.timeEpoch(0L))
    }

    @Test
    fun timeEpochFloorsNegativeMillisecondsLikePythonsDivmod() {
        assertEquals("-1.999", SessionFormat.timeEpoch(-1L))
        assertEquals("-1.000", SessionFormat.timeEpoch(-1_000L))
    }

    @Test
    fun parseUtcReadsTheContractFormAndATrailingZ() {
        assertEquals(1_789_050_600_400L, SessionFormat.parseUtc("2026-09-10T14:30:00.400+00:00"))
        assertEquals(1_789_050_600_400L, SessionFormat.parseUtc("2026-09-10T14:30:00.400Z"))
        assertEquals(-1L, SessionFormat.parseUtc("1969-12-31T23:59:59.999+00:00"))
    }

    @Test
    fun parseUtcRefusesEverythingElse() {
        val refused = listOf(
            "",
            "2026-09-10T14:30:00+00:00",
            "2026-09-10T14:30:00.4+00:00",
            "2026-09-10T14:30:00.400000+00:00",
            "2026-09-10T14:30:00.400+0000",
            "2026-09-10T14:30:00.400+01:00",
            "2026-09-10T14:30:00.400",
            "2026-09-10 14:30:00.400+00:00",
            " 2026-09-10T14:30:00.400+00:00",
            "2026-09-10T14:30:00.400+00:00\n",
            "2026-02-30T14:30:00.400+00:00",
            "2026-09-10T24:00:00.000+00:00",
            "2026-09-10T14:60:00.000+00:00",
            ch(0xFF12) + "026-09-10T14:30:00.400+00:00",
            "1789050600.400",
        )
        for (text in refused) assertNull("'$text'", SessionFormat.parseUtc(text))
    }

    @Test
    fun parseUtcInvertsUtc() {
        for (ms in listOf(0L, 946_684_800_000L, Golden.at(400), 4_102_444_799_999L)) {
            assertEquals(ms, SessionFormat.parseUtc(SessionFormat.utc(ms)))
        }
    }
}
