package com.fieldtap.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionJsonTest {
    private val bs = "\\"

    /** A session as it is written at start: still recording, nothing measured yet. */
    private val openSession = SessionMeta(
        sessionId = "0b5e2c1d-3a4f-4e6b-8c7d-9e0f1a2b3c4d",
        name = "Lobby \"A\"\nsecond line",
        startedUtcMs = Golden.at(750),
        transport = TransportMeta(appVersion = "0.1.0", versionCode = 1),
        device = DeviceMeta(key = "app:7d0e5b8c-1f2a-4c3d-9e6f-0a1b2c3d4e5f"),
        summary = SummaryMeta(stoppedBy = "recording"),
        privacy = PrivacyMeta(LocationPrecision.FULL, 0, "2026-09-10-draft", "0".repeat(64)),
    )

    /** The smallest session.json decode accepts: only the required keys, and a trailing Z. */
    private val minimalMembers: List<Pair<String, JsonNode>> = listOf(
        "session_id" to JsonStr("0b5e2c1d-3a4f-4e6b-8c7d-9e0f1a2b3c4d"),
        "name" to JsonStr("x"),
        "started_utc" to JsonStr("2026-09-10T14:30:00.000Z"),
        "transport" to JsonObj(listOf("app_version" to JsonStr("0.0.9"), "version_code" to JsonInt(3))),
        "device" to JsonObj(listOf("key" to JsonStr("app:k"))),
        "summary" to JsonObj(listOf("stopped_by" to JsonStr("user"))),
        "privacy" to privacy("approx_110m", JsonInt(2)),
    )

    @Test
    fun goldenSessionJsonDecodesAndEncodesByteForByte() {
        val golden = Golden.text(SessionFile.SESSION_JSON)
        val encoded = SessionJson.encode(SessionJson.decode(golden))
        assertEquals(golden, encoded)
        assertArrayEquals(Golden.bytes(SessionFile.SESSION_JSON), encoded.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun goldenSessionJsonIsWhatTheModelEncodes() {
        assertEquals(Golden.text(SessionFile.SESSION_JSON), SessionJson.encode(GoldenData.meta()))
    }

    @Test
    fun goldenSessionJsonDecodesToTheModel() {
        assertEquals(GoldenData.meta(), SessionJson.decode(Golden.text(SessionFile.SESSION_JSON)))
    }

    @Test
    fun noDefaultLocaleChangesAnyByte() {
        val golden = Golden.text(SessionFile.SESSION_JSON)
        for (tag in TRICKY_LOCALES) {
            withDefaultLocale(tag) {
                assertEquals(tag, golden, SessionJson.encode(GoldenData.meta()))
                assertEquals(tag, golden, SessionJson.encode(SessionJson.decode(golden)))
            }
        }
    }

    @Test
    fun anOpenSessionWritesNullValuesAndEmptyObjectsButNeverNullObjects() {
        val text = SessionJson.encode(openSession)
        assertEquals(expectedOpenSessionText(), text)
        assertFalse(text.startsWith(ch(0xFEFF)))
        assertEquals(openSession, SessionJson.decode(text))
    }

    @Test
    fun handsetKeysWithoutAValueAreLeftOut() {
        val meta = openSession.copy(handset = HandsetMeta(model = "Pixel 8", networkType = "NR", manufacturer = "Google"))
        val handset = parse(SessionJson.encode(meta)).getValue("handset").jsonObject
        assertEquals(listOf("manufacturer", "model", "network_type"), handset.keys.toList())
        assertEquals(meta, SessionJson.decode(SessionJson.encode(meta)))
    }

    @Test
    fun deviceLabelIsWrittenOnlyWhenSet() {
        val labelled = openSession.copy(device = DeviceMeta(key = "app:k", label = "SM-S921U"))
        assertEquals(listOf("key", "label"), parse(SessionJson.encode(labelled)).getValue("device").jsonObject.keys.toList())
        assertEquals(listOf("key"), parse(SessionJson.encode(openSession)).getValue("device").jsonObject.keys.toList())
    }

    @Test
    fun filesAreWrittenInContractOrderAndOnlyForCsvFiles() {
        val meta = openSession.copy(files = listOf(SessionFile.CELLINFO, SessionFile.SESSION_JSON, SessionFile.KPI, SessionFile.KPI))
        val files = parse(SessionJson.encode(meta)).getValue("files").jsonObject
        assertEquals(listOf("kpi", "cellinfo"), files.keys.toList())
        assertEquals("kpi.csv", files.getValue("kpi").jsonPrimitive.content)
        assertEquals(listOf(SessionFile.KPI, SessionFile.CELLINFO), SessionJson.decode(SessionJson.encode(meta)).files)
        val none = openSession.copy(files = SessionFile.CSV - SessionFile.TRACK)
        assertFalse(SessionJson.encode(none).contains("track"))
        assertTrue(SessionJson.encode(openSession.copy(files = emptyList())).contains("  \"files\": {},\n"))
    }

    @Test
    fun percentagesAndGapSecondsHaveOneDecimal() {
        val meta = openSession.copy(
            collection = CollectionMeta(
                medianFreshIntervalMs = 2_000,
                shortIntervalPct = 88.25,
                screenOnPct = 100.0 / 3,
                wifiConnectedPct = 100.0,
                chargingPct = Double.NaN,
                freshSamples = 3,
                repeatsDropped = 0,
                gaps = listOf(GapMeta(Golden.at(1_000), Golden.at(5_135), "app_paused")),
            ),
        )
        val text = SessionJson.encode(meta)
        assertTrue(text.contains("    \"median_fresh_interval_ms\": 2000,\n"))
        assertTrue(text.contains("    \"short_interval_pct\": 88.2,\n"))
        assertTrue(text.contains("    \"screen_on_pct\": 33.3,\n"))
        assertTrue(text.contains("    \"wifi_connected_pct\": 100.0,\n"))
        assertTrue(text.contains("    \"charging_pct\": null,\n"))
        assertTrue(text.contains("        \"seconds\": 4.1,\n"))
        assertNull(SessionJson.decode(text).collection.chargingPct)
    }

    @Test
    fun plmnsKeepTheirOrder() {
        val meta = openSession.copy(summary = SummaryMeta("user", linkedMapOf("311480" to 54, "310260" to 3, "001010" to 1)))
        val decoded = SessionJson.decode(SessionJson.encode(meta))
        assertEquals(listOf("311480", "310260", "001010"), decoded.summary.plmns.keys.toList())
        assertEquals(meta, decoded)
    }

    @Test
    fun everyFieldRoundTrips() {
        val meta = GoldenData.meta().copy(
            groupId = "group-1",
            note = "Line 1\nLine \"2\" " + bs + " " + ch(0xE9) + ch(0x1F6B6) + " /path" + ch(1),
            location = null,
            stoppedUtcMs = null,
            transport = TransportMeta(appVersion = "1.2.3-beta", versionCode = 4_000_000_000L),
            files = SessionFile.CSV - SessionFile.TRACK,
            summary = SummaryMeta("low_memory", mapOf("311480" to 10, "31026" to 2)),
            privacy = PrivacyMeta(LocationPrecision.NONE, 3, "2026-09-10-draft", "f".repeat(64)),
        )
        assertEquals(meta, SessionJson.decode(SessionJson.encode(meta)))
    }

    @Test
    fun keysFollowTheSchemaFieldOrder() {
        val encoded = ArrayList<String>()
        collectPaths("", parse(SessionJson.encode(GoldenData.meta())), encoded)
        val paths = encoded.distinct().filterNot { it.startsWith("files.") || it.startsWith("summary.plmns.") }
        assertEquals(SchemaJson.fields().map { it.str("path") }, paths)
    }

    @Test
    fun decodeFillsDefaultsForOptionalKeysAndIgnoresUnknownOnes() {
        val extra = "extra" to JsonObj(listOf("nested" to JsonArr(listOf(JsonInt(1), JsonNul))))
        val meta = SessionJson.decode(document(minimalMembers + extra))
        val expected = SessionMeta(
            sessionId = "0b5e2c1d-3a4f-4e6b-8c7d-9e0f1a2b3c4d",
            name = "x",
            startedUtcMs = Golden.STARTED_UTC_MS,
            transport = TransportMeta(appVersion = "0.0.9", versionCode = 3),
            device = DeviceMeta(key = "app:k"),
            summary = SummaryMeta(stoppedBy = "user"),
            privacy = PrivacyMeta(LocationPrecision.APPROX_110M, 2, "v1", "ab"),
        )
        assertEquals(expected, meta)
    }

    @Test
    fun nullOptionalValuesTakeTheirDefaults() {
        val nulls = listOf(
            "group_id" to JsonNul,
            "note" to JsonNul,
            "stopped_utc" to JsonNul,
            "handset" to JsonNul,
            "files" to JsonObj(listOf("kpi" to JsonStr("kpi.csv"), "session" to JsonStr("session.json"))),
            "collection" to JsonObj(listOf("median_fresh_interval_ms" to JsonNul, "gaps" to JsonNul)),
            "capabilities" to JsonObj(emptyList()),
        )
        val meta = SessionJson.decode(document(minimalMembers + nulls))
        assertNull(meta.groupId)
        assertNull(meta.note)
        assertNull(meta.stoppedUtcMs)
        assertEquals(HandsetMeta(), meta.handset)
        assertEquals(listOf(SessionFile.KPI), meta.files)
        assertEquals(CollectionMeta.EMPTY, meta.collection)
        assertFalse(meta.capabilities.layer3)
    }

    @Test
    fun decodeRefusesWhatItCannotMap() {
        val cases = listOf(
            "not JSON" to "{\"session_id\": ",
            "empty text" to "",
            "an array" to "[]",
            "missing session_id" to without("session_id"),
            "missing name" to without("name"),
            "missing started_utc" to without("started_utc"),
            "missing transport" to without("transport"),
            "missing device" to without("device"),
            "missing summary" to without("summary"),
            "missing privacy" to without("privacy"),
            "naive started_utc" to replacing("started_utc", JsonStr("2026-09-10T14:30:00.000")),
            "started_utc in another zone" to replacing("started_utc", JsonStr("2026-09-10T15:30:00.000+01:00")),
            "numeric name" to replacing("name", JsonInt(7)),
            "null name" to replacing("name", JsonNul),
            "string version_code" to replacing("transport", JsonObj(listOf("app_version" to JsonStr("1"), "version_code" to JsonStr("3")))),
            "array plmns" to replacing("summary", JsonObj(listOf("stopped_by" to JsonStr("user"), "plmns" to JsonArr(emptyList())))),
            "string plmn count" to replacing("summary", JsonObj(listOf("stopped_by" to JsonStr("user"), "plmns" to JsonObj(listOf("311480" to JsonStr("54")))))),
            "numeric handset value" to replacing("handset", JsonObj(listOf("model" to JsonInt(8)))),
            "unknown precision" to replacing("privacy", privacy("exact", JsonInt(0))),
            "fractional zone_pauses" to replacing("privacy", privacy("full", JsonDec(1.5, 1))),
            "another format" to replacing("format", JsonStr("fieldtap-session/2")),
            "string layer3" to replacing("capabilities", JsonObj(listOf("layer3" to JsonStr("false")))),
            "string percentage" to replacing("collection", JsonObj(listOf("screen_on_pct" to JsonStr("88.3")))),
            "gap without start" to replacing(
                "collection",
                JsonObj(listOf("gaps" to JsonArr(listOf(JsonObj(listOf("stop_utc" to JsonStr("2026-09-10T14:30:00.000+00:00"), "reason" to JsonStr("unknown"))))))),
            ),
            "gap that is not an object" to replacing("collection", JsonObj(listOf("gaps" to JsonArr(listOf(JsonInt(1)))))),
        )
        for ((case, text) in cases) {
            assertThrows(case, SessionJsonException::class.java) { SessionJson.decode(text) }
        }
    }

    @Test
    fun errorsNameTheKey() {
        val missing = assertThrows(SessionJsonException::class.java) {
            SessionJson.decode(
                replacing("privacy", JsonObj(listOf("location_precision" to JsonStr("full"), "zone_pauses" to JsonInt(0), "consent_version" to JsonStr("v")))),
            )
        }
        assertEquals("session.json: privacy.consent_sha256 is missing", missing.message)
        val badGap = assertThrows(SessionJsonException::class.java) {
            SessionJson.decode(
                replacing(
                    "collection",
                    JsonObj(listOf("gaps" to JsonArr(listOf(JsonObj(listOf("start_utc" to JsonStr("yesterday"), "stop_utc" to JsonStr("2026-09-10T14:30:00.000+00:00"), "reason" to JsonStr("unknown"))))))),
                ),
            )
        }
        assertEquals("session.json: collection.gaps[0].start_utc is not a timestamp in the contract form", badGap.message)
    }

    private fun privacy(precision: String, zonePauses: JsonNode): JsonObj = JsonObj(
        listOf(
            "location_precision" to JsonStr(precision),
            "zone_pauses" to zonePauses,
            "consent_version" to JsonStr("v1"),
            "consent_sha256" to JsonStr("ab"),
        ),
    )

    private fun document(members: List<Pair<String, JsonNode>>): String = JsonText.render(JsonObj(members))

    private fun without(key: String): String = document(minimalMembers.filter { it.first != key })

    private fun replacing(key: String, value: JsonNode): String =
        document(minimalMembers.filter { it.first != key } + (key to value))

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun collectPaths(prefix: String, element: JsonElement, out: MutableList<String>) {
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    val path = if (prefix.isEmpty()) key else "$prefix.$key"
                    out.add(path)
                    collectPaths(path, value, out)
                }
            }
            is JsonArray -> {
                for (item in element) {
                    out.add("$prefix[]")
                    collectPaths("$prefix[]", item, out)
                }
            }
            else -> Unit
        }
    }

    private fun expectedOpenSessionText(): String = listOf(
        "{",
        "  \"format\": \"fieldtap-session/1\",",
        "  \"session_id\": \"0b5e2c1d-3a4f-4e6b-8c7d-9e0f1a2b3c4d\",",
        "  \"group_id\": null,",
        "  \"name\": \"Lobby " + bs + "\"A" + bs + "\"" + bs + "nsecond line\",",
        "  \"note\": null,",
        "  \"location\": null,",
        "  \"started_utc\": \"2026-09-10T14:30:00.750+00:00\",",
        "  \"stopped_utc\": null,",
        "  \"transport\": {",
        "    \"transport\": \"android-api\",",
        "    \"app\": \"5gto6G FieldTap\",",
        "    \"app_version\": \"0.1.0\",",
        "    \"version_code\": 1",
        "  },",
        "  \"handset\": {},",
        "  \"device\": {",
        "    \"key\": \"app:7d0e5b8c-1f2a-4c3d-9e6f-0a1b2c3d4e5f\"",
        "  },",
        "  \"modem\": {},",
        "  \"log_mask\": {},",
        "  \"files\": {",
        "    \"kpi\": \"kpi.csv\",",
        "    \"track\": \"track.csv\",",
        "    \"events\": \"events.csv\",",
        "    \"traffic\": \"traffic.csv\",",
        "    \"cells\": \"cells.csv\",",
        "    \"cellinfo\": \"cellinfo.csv\"",
        "  },",
        "  \"summary\": {",
        "    \"stopped_by\": \"recording\",",
        "    \"plmns\": {}",
        "  },",
        "  \"capabilities\": {",
        "    \"layer3\": false",
        "  },",
        "  \"collection\": {",
        "    \"median_fresh_interval_ms\": null,",
        "    \"short_interval_pct\": null,",
        "    \"screen_on_pct\": null,",
        "    \"wifi_connected_pct\": null,",
        "    \"charging_pct\": null,",
        "    \"fresh_samples\": 0,",
        "    \"repeats_dropped\": 0,",
        "    \"gaps\": []",
        "  },",
        "  \"privacy\": {",
        "    \"data_class\": \"kpi\",",
        "    \"location_precision\": \"full\",",
        "    \"zone_pauses\": 0,",
        "    \"consent_version\": \"2026-09-10-draft\",",
        "    \"consent_sha256\": \"" + "0".repeat(64) + "\"",
        "  }",
        "}",
    ).joinToString("\n", postfix = "\n")
}
