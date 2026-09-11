package com.fieldtap.format

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
}
