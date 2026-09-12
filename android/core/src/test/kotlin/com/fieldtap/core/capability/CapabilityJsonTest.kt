package com.fieldtap.core.capability

import com.fieldtap.format.HandsetMeta
import com.fieldtap.format.JsonObj
import com.fieldtap.format.JsonStr
import com.fieldtap.format.JsonText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CapabilityJsonTest {

    @Test
    fun keysAreSnakeCaseInReportFieldOrder() {
        val tree = CapabilityJson.tree(report())

        assertEquals(
            listOf(
                "format", "created_utc_ms", "app_version", "version_code", "sdk_int", "handset",
                "root", "root_probe", "usb", "cellular", "verdict", "notes",
            ),
            tree.members.map { it.first },
        )
        assertEquals(JsonStr("fieldtap-capability/1"), member(tree, "format"))
    }

    @Test
    fun handsetLeavesOutKeysWithNoValue() {
        val handset = member(CapabilityJson.tree(report()), "handset") as JsonObj

        assertEquals(
            JsonObj(
                listOf(
                    "manufacturer" to JsonStr("OnePlus"),
                    "model" to JsonStr("CPH2581"),
                    "android_version" to JsonStr("14"),
                ),
            ),
            handset,
        )
    }

    @Test
    fun encodeRendersTheTreeWithJsonText() {
        val report = report()

        assertEquals(JsonText.render(CapabilityJson.tree(report)), CapabilityJson.encode(report))
    }

    @Test
    fun theExportIsExactlyThisDocument() {
        val expected = listOf(
            "{",
            "  \"format\": \"fieldtap-capability/1\",",
            "  \"created_utc_ms\": 1789050630000,",
            "  \"app_version\": \"1.0.0\",",
            "  \"version_code\": 2,",
            "  \"sdk_int\": 34,",
            "  \"handset\": {",
            "    \"manufacturer\": \"OnePlus\",",
            "    \"model\": \"CPH2581\",",
            "    \"android_version\": \"14\"",
            "  },",
            "  \"root\": {",
            "    \"confidence\": \"HIGH\",",
            "    \"su_binaries_present\": [",
            "      \"/system/xbin/su\"",
            "    ],",
            "    \"root_manager_packages\": [",
            "      \"com.topjohnwu.magisk\"",
            "    ],",
            "    \"build_tags_test_keys\": false,",
            "    \"debuggable\": false,",
            "    \"secure_off\": false,",
            "    \"writable_system_paths\": []",
            "  },",
            "  \"root_probe\": {",
            "    \"su_status\": \"GRANTED\",",
            "    \"is_root\": true,",
            "    \"selinux\": \"ENFORCING\",",
            "    \"diag_device\": \"ABSENT\",",
            "    \"kernel_diag\": \"DIAG_ABSENT\",",
            "    \"layer3\": \"NOT_POSSIBLE\",",
            "    \"elapsed_ms\": 420",
            "  },",
            "  \"usb\": {",
            "    \"adb_enabled\": false,",
            "    \"wireless_debug_enabled\": false,",
            "    \"developer_options_enabled\": true",
            "  },",
            "  \"cellular\": {",
            "    \"read_phone_state_granted\": false,",
            "    \"precise_location_granted\": true,",
            "    \"location_services_enabled\": true,",
            "    \"sim_ready\": true,",
            "    \"mock_location_app_set\": false,",
            "    \"build_accepts_mock_locations\": false",
            "  },",
            "  \"verdict\": {",
            "    \"public_api_measurements\": \"YES\",",
            "    \"push_cell_updates\": \"NO\",",
            "    \"layer3_signalling\": \"NOT_POSSIBLE\"",
            "  },",
            "  \"notes\": [",
            "    \"A note.\"",
            "  ]",
            "}",
        ).joinToString(separator = "\n", postfix = "\n")

        assertEquals(expected, CapabilityJson.encode(report()))
    }

    @Test
    fun rootProbeIsNullWhenTheCheckNeverRan() {
        val tree = CapabilityJson.tree(report(rootProbe = null))

        assertEquals("null", JsonText.render(member(tree, "root_probe")).trimEnd())
    }

    @Test
    fun theExportHoldsNoIdentifierKeys() {
        val text = CapabilityJson.encode(report())

        for (key in listOf("imei", "imsi", "iccid", "serial", "android_id", "phone_number", "lat", "lon")) {
            assertFalse(key, text.contains("\"$key\""))
        }
    }

    @Test
    fun theFileNameCarriesMakerModelAndUtcTime() {
        assertEquals("capability-OnePlus-CPH2581-20260910-143030.json", CapabilityJson.fileName(report()))

        val spaced = report(handset = HandsetMeta(manufacturer = "OnePlus", model = "CPH2581 (NA)"))
        assertEquals("capability-OnePlus-CPH2581-NA-20260910-143030.json", CapabilityJson.fileName(spaced))
    }

    @Test
    fun unsafeOrMissingNamesAreCleanedUp() {
        val blank = report(handset = HandsetMeta(manufacturer = "  ", model = null))
        assertEquals("capability-unknown-unknown-20260910-143030.json", CapabilityJson.fileName(blank))

        val odd = report(handset = HandsetMeta(manufacturer = "Café – Lab/β", model = "A".repeat(60)))
        assertEquals("capability-Caf-Lab-${"A".repeat(40)}-20260910-143030.json", CapabilityJson.fileName(odd))
    }

    private fun member(obj: JsonObj, key: String) = obj.members.first { it.first == key }.second

    private val sampleRoot = RootSignals(
        suBinariesPresent = listOf("/system/xbin/su"),
        rootManagerPackages = listOf("com.topjohnwu.magisk"),
        buildTagsTestKeys = false,
        debuggable = false,
        secureOff = false,
        writableSystemPaths = emptyList(),
        confidence = RootConfidence.HIGH,
        caveat = RootDetector.CAVEAT,
    )

    private val sampleProbe = RootProbeResult(
        suStatus = SuStatus.GRANTED,
        isRoot = true,
        selinux = SelinuxMode.ENFORCING,
        diagDevice = DiagDevice.ABSENT,
        kernelDiag = KernelConfigProbe.DIAG_ABSENT,
        layer3 = Layer3OnDevice.NOT_POSSIBLE,
        elapsedMs = 420,
        message = "Layer-3 capture is not possible on this phone.",
    )

    private val sampleUsb = UsbDebugState(adbEnabled = false, wirelessDebugEnabled = false, developerOptionsEnabled = true)

    private val sampleCellular = CellularReadout(
        readPhoneStateGranted = false,
        preciseLocationGranted = true,
        locationServicesEnabled = true,
        simReady = true,
        mockLocationAppSet = false,
        buildAcceptsMockLocations = false,
    )

    private val sampleVerdict = CaptureVerdict(
        publicApiMeasurements = CaptureAnswer.YES,
        pushCellUpdates = CaptureAnswer.NO,
        layer3Signalling = Layer3OnDevice.NOT_POSSIBLE,
        lines = listOf("Tier 1.", "Tier 2.", "Tier 3."),
        laptopPath = "USB debugging is off.",
    )

    private fun report(
        handset: HandsetMeta = HandsetMeta(manufacturer = "OnePlus", model = "CPH2581", androidVersion = "14"),
        rootProbe: RootProbeResult? = sampleProbe,
    ): CapabilityReport = CapabilityReport(
        createdUtcMs = 1_789_050_630_000L,
        appVersion = "1.0.0",
        versionCode = 2L,
        sdkInt = 34,
        handset = handset,
        root = sampleRoot,
        rootProbe = rootProbe,
        usb = sampleUsb,
        cellular = sampleCellular,
        verdict = sampleVerdict,
        notes = listOf("A note."),
    )
}
