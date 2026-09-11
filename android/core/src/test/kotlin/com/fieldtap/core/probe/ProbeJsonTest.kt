package com.fieldtap.core.probe

import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.RadioListener
import com.fieldtap.format.HandsetMeta
import com.fieldtap.format.JsonArr
import com.fieldtap.format.JsonBool
import com.fieldtap.format.JsonInt
import com.fieldtap.format.JsonNul
import com.fieldtap.format.JsonObj
import com.fieldtap.format.JsonStr
import com.fieldtap.format.JsonText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProbeJsonTest {

    @Test
    fun keysAreSnakeCaseInReportFieldOrder() {
        val tree = ProbeJson.tree(report())

        assertEquals(
            listOf(
                "format", "created_utc_ms", "duration_ms", "app_version", "version_code", "sdk_int", "handset",
                "permissions", "listeners", "cell_info", "service_state", "display_overrides_seen", "notes",
            ),
            tree.members.map { it.first },
        )
        assertEquals(JsonStr("fieldtap-probe/1"), member(tree, "format"))
        assertEquals(JsonInt(36), member(tree, "sdk_int"))
    }

    @Test
    fun handsetLeavesOutKeysWithNoValue() {
        val handset = member(ProbeJson.tree(report()), "handset") as JsonObj

        assertEquals(
            JsonObj(
                listOf(
                    "manufacturer" to JsonStr("Google"),
                    "model" to JsonStr("Pixel 8"),
                    "android_version" to JsonStr("16"),
                    "network_type" to JsonStr("NR"),
                ),
            ),
            handset,
        )
    }

    @Test
    fun listenersAreWrittenInEnumOrderByName() {
        val listeners = member(ProbeJson.tree(report()), "listeners") as JsonObj

        assertEquals(
            listOf("CELL_INFO_REQUEST" to JsonStr("REGISTERED"), "PHYSICAL_CHANNEL_CONFIG" to JsonStr("REFUSED_BY_PLATFORM")),
            listeners.members,
        )
    }

    @Test
    fun missingCellValuesAreNull() {
        val cellInfo = member(ProbeJson.tree(report()), "cell_info") as JsonObj

        assertEquals(JsonNul, member(cellInfo, "sinr_min"))
        assertEquals(JsonNul, member(cellInfo, "sinr_max"))
        assertEquals(JsonBool(true), member(cellInfo, "timestamps_advance"))
        assertEquals(JsonInt(10_000), member(cellInfo, "min_fresh_interval_ms"))
        assertEquals(JsonArr(listOf(JsonStr("lte"), JsonStr("nr"))), member(cellInfo, "serving_rats"))

        val unknown = report(cellInfo = sampleCellInfo.copy(timestampsAdvance = null, minFreshIntervalMs = null, rsrpMin = null))
        val unknownTree = member(ProbeJson.tree(unknown), "cell_info") as JsonObj
        assertEquals(JsonNul, member(unknownTree, "timestamps_advance"))
        assertEquals(JsonNul, member(unknownTree, "min_fresh_interval_ms"))
        assertEquals(JsonNul, member(unknownTree, "rsrp_min"))
    }

    @Test
    fun encodeRendersTheTreeWithJsonText() {
        val report = report()

        assertEquals(JsonText.render(ProbeJson.tree(report)), ProbeJson.encode(report))
    }

    @Test
    fun theExportIsExactlyThisDocument() {
        val expected = listOf(
            "{",
            "  \"format\": \"fieldtap-probe/1\",",
            "  \"created_utc_ms\": 1789050630000,",
            "  \"duration_ms\": 30012,",
            "  \"app_version\": \"0.1.0\",",
            "  \"version_code\": 1,",
            "  \"sdk_int\": 36,",
            "  \"handset\": {",
            "    \"manufacturer\": \"Google\",",
            "    \"model\": \"Pixel 8\",",
            "    \"android_version\": \"16\",",
            "    \"network_type\": \"NR\"",
            "  },",
            "  \"permissions\": {",
            "    \"android.permission.ACCESS_FINE_LOCATION\": true,",
            "    \"android.permission.READ_PHONE_STATE\": false",
            "  },",
            "  \"listeners\": {",
            "    \"CELL_INFO_REQUEST\": \"REGISTERED\",",
            "    \"PHYSICAL_CHANNEL_CONFIG\": \"REFUSED_BY_PLATFORM\"",
            "  },",
            "  \"cell_info\": {",
            "    \"requests\": 31,",
            "    \"answers\": 31,",
            "    \"errors\": 0,",
            "    \"max_cells_per_answer\": 1,",
            "    \"neighbours_seen\": false,",
            "    \"serving_rats\": [",
            "      \"lte\",",
            "      \"nr\"",
            "    ],",
            "    \"band_lists_present\": true,",
            "    \"connection_status_reported\": true,",
            "    \"nsa_secondary_seen\": false,",
            "    \"timestamps_advance\": true,",
            "    \"min_fresh_interval_ms\": 10000,",
            "    \"rsrp_min\": -73,",
            "    \"rsrp_max\": -44,",
            "    \"sinr_min\": null,",
            "    \"sinr_max\": null",
            "  },",
            "  \"service_state\": {",
            "    \"snapshots\": 2,",
            "    \"operator_numeric_present\": true,",
            "    \"emergency_only_seen\": false,",
            "    \"states\": [",
            "      \"IN_SERVICE\"",
            "    ]",
            "  },",
            "  \"display_overrides_seen\": [],",
            "  \"notes\": [",
            "    \"No neighbour cells were reported.\"",
            "  ]",
            "}",
        ).joinToString(separator = "\n", postfix = "\n")

        assertEquals(expected, ProbeJson.encode(report()))
    }

    @Test
    fun theExportHoldsNoIdentifierKeys() {
        val text = ProbeJson.encode(report())

        for (key in listOf("imei", "imsi", "iccid", "serial", "android_id", "phone_number", "lat", "lon")) {
            assertFalse(key, text.contains("\"$key\""))
        }
    }

    @Test
    fun theFileNameCarriesMakerModelAndUtcTime() {
        assertEquals("probe-Google-Pixel-8-20260910-143030.json", ProbeJson.fileName(report()))

        val spaced = report(handset = HandsetMeta(manufacturer = "OnePlus", model = "CPH2581 (NA)"))
        assertEquals("probe-OnePlus-CPH2581-NA-20260910-143030.json", ProbeJson.fileName(spaced))
    }

    @Test
    fun unsafeOrMissingNamesAreCleanedUp() {
        val blank = report(handset = HandsetMeta(manufacturer = "  ", model = null))
        assertEquals("probe-unknown-unknown-20260910-143030.json", ProbeJson.fileName(blank))

        val odd = report(handset = HandsetMeta(manufacturer = "Café – Lab/β", model = "A".repeat(60)))
        assertEquals("probe-Caf-Lab-${"A".repeat(40)}-20260910-143030.json", ProbeJson.fileName(odd))
    }

    private fun member(obj: JsonObj, key: String) = obj.members.first { it.first == key }.second

    private val sampleCellInfo = CellInfoProbe(
        requests = 31,
        answers = 31,
        errors = 0,
        maxCellsPerAnswer = 1,
        neighboursSeen = false,
        servingRats = listOf("lte", "nr"),
        bandListsPresent = true,
        connectionStatusReported = true,
        nsaSecondarySeen = false,
        timestampsAdvance = true,
        minFreshIntervalMs = 10_000L,
        rsrpMin = -73,
        rsrpMax = -44,
        sinrMin = null,
        sinrMax = null,
    )

    private fun report(
        handset: HandsetMeta = HandsetMeta(manufacturer = "Google", model = "Pixel 8", androidVersion = "16", networkType = "NR"),
        cellInfo: CellInfoProbe = sampleCellInfo,
    ): ProbeReport = ProbeReport(
        createdUtcMs = 1_789_050_630_000L,
        durationMs = 30_012L,
        appVersion = "0.1.0",
        versionCode = 1L,
        sdkInt = 36,
        handset = handset,
        permissions = linkedMapOf(
            "android.permission.ACCESS_FINE_LOCATION" to true,
            "android.permission.READ_PHONE_STATE" to false,
        ),
        // Inserted out of enum order on purpose.
        listeners = linkedMapOf(
            RadioListener.PHYSICAL_CHANNEL_CONFIG to ListenerOutcome.REFUSED_BY_PLATFORM,
            RadioListener.CELL_INFO_REQUEST to ListenerOutcome.REGISTERED,
        ),
        cellInfo = cellInfo,
        serviceState = ServiceStateProbe(
            snapshots = 2,
            operatorNumericPresent = true,
            emergencyOnlySeen = false,
            states = listOf("IN_SERVICE"),
        ),
        displayOverridesSeen = emptyList(),
        notes = listOf("No neighbour cells were reported."),
    )
}
