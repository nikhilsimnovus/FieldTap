package com.fieldtap.core.capability

import com.fieldtap.format.HandsetMeta
import com.fieldtap.format.JsonObj
import com.fieldtap.format.JsonStr
import com.fieldtap.format.JsonText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityJsonTest {

    @Test
    fun keysAreSnakeCaseInReportFieldOrder() {
        val tree = CapabilityJson.tree(report())

        assertEquals(
            listOf(
                "format", "created_utc_ms", "app_version", "version_code", "sdk_int", "handset",
                "root", "root_probe", "usb", "cellular", "verdict", "on_device_layer3", "notes",
            ),
            tree.members.map { it.first },
        )
        assertEquals(JsonStr("fieldtap-capability/2"), member(tree, "format"))
    }

    @Test
    fun everyV1KeyIsStillPresentWithTheSameName() {
        // /2 is a strict superset of /1: a reader that keyed the /1 names still finds every one of them.
        val text = CapabilityJson.encode(report())
        val v1Keys = listOf(
            "\"format\"", "\"created_utc_ms\"", "\"app_version\"", "\"version_code\"", "\"sdk_int\"",
            "\"handset\"", "\"root\"", "\"confidence\"", "\"su_binaries_present\"", "\"root_manager_packages\"",
            "\"build_tags_test_keys\"", "\"debuggable\"", "\"secure_off\"", "\"writable_system_paths\"",
            "\"root_probe\"", "\"su_status\"", "\"is_root\"", "\"selinux\"", "\"diag_device\"", "\"kernel_diag\"",
            "\"layer3\"", "\"elapsed_ms\"", "\"usb\"", "\"adb_enabled\"", "\"wireless_debug_enabled\"",
            "\"developer_options_enabled\"", "\"cellular\"", "\"read_phone_state_granted\"",
            "\"precise_location_granted\"", "\"location_services_enabled\"", "\"sim_ready\"",
            "\"mock_location_app_set\"", "\"build_accepts_mock_locations\"", "\"verdict\"",
            "\"public_api_measurements\"", "\"push_cell_updates\"", "\"layer3_signalling\"", "\"notes\"",
        )
        for (key in v1Keys) {
            assertTrue("missing /1 key $key", text.contains(key))
        }
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
            "  \"format\": \"fieldtap-capability/2\",",
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
            "    \"writable_system_paths\": [],",
            "    \"root_manager_versions\": [",
            "      {",
            "        \"pkg\": \"com.topjohnwu.magisk\",",
            "        \"version_name\": \"27.0\"",
            "      }",
            "    ]",
            "  },",
            "  \"root_probe\": {",
            "    \"su_status\": \"GRANTED\",",
            "    \"is_root\": true,",
            "    \"selinux\": \"ENFORCING\",",
            "    \"diag_device\": \"ABSENT\",",
            "    \"kernel_diag\": \"DIAG_ABSENT\",",
            "    \"layer3\": \"NOT_POSSIBLE\",",
            "    \"elapsed_ms\": 420,",
            "    \"deep\": null",
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
            "  \"on_device_layer3\": {",
            "    \"outcome\": \"NOT_POSSIBLE\",",
            "    \"viable\": false,",
            "    \"reason\": \"it is rooted, but its kernel has no diag device (/dev/diag is absent, and the kernel config reports no diag support)\",",
            "    \"laptop_path\": \"Use 5gto6G FieldTap on a laptop with this phone connected over USB.\",",
            "    \"usb_debugging_on\": false",
            "  },",
            "  \"notes\": [",
            "    \"A note.\"",
            "  ]",
            "}",
        ).joinToString(separator = "\n", postfix = "\n")

        assertEquals(expected, CapabilityJson.encode(report()))
    }

    @Test
    fun theDeepBlockIsRenderedWithSnakeCaseKeysAndNoContent() {
        val text = CapabilityJson.encode(report(rootProbe = sampleProbe.copy(deep = sampleDeep)))

        // The nested deep members render, keyed snake_case, enums by name.
        for (fragment in listOf(
            "\"deep\": {",
            "\"kernel\": {",
            "\"release\": \"5.10.101-android12-9\"",
            "\"architecture\": \"aarch64\"",
            "\"smp\": true",
            "\"preempt\": true",
            "\"redacted_version\": \"Linux version 5.10.101-android12-9 SMP PREEMPT\"",
            "\"selinux\": {",
            "\"mode\": \"ENFORCING\"",
            "\"blocks_app_diag_path\": true",
            "\"diag_nodes\": {",
            "\"primary\": {",
            "\"path\": \"/dev/diag\"",
            "\"exists\": false",
            "\"char_device\": false",
            "\"octal_mode\": null",
            "\"others\": []",
            "\"kernel_diag_config\": \"DIAG_ABSENT\"",
            "\"modem_interfaces\": {",
            "\"count\": 3",
            "\"rmnet_data0\"",
            "\"capture_tooling\": {",
            "\"tcpdump_present\": false",
            "\"pcap_capable_interface_present\": true",
            "\"radio_log\": {",
            "\"readable\": true",
            "\"line_count\": 5",
        )) {
            assertTrue(fragment, text.contains(fragment))
        }
    }

    @Test
    fun theDeepExportCarriesNoIdentifierNoRawLogAndNoDiagContent() {
        val text = CapabilityJson.encode(report(rootProbe = sampleProbe.copy(deep = sampleDeep)))

        // No identifier keys or values.
        for (key in listOf("imei", "imsi", "iccid", "serial", "android_id", "phone_number", "tmsi", "lat", "lon")) {
            assertFalse(key, text.contains("\"$key\""))
        }
        // No raw radio-log line ever reaches the export — only the readable flag and an integer count.
        assertFalse("no logcat line marker", text.contains("logcat"))
        // The redacted kernel version never carries a build stamp (user@host) or a build path.
        assertFalse("no build-host stamp", text.contains("@"))
    }

    @Test
    fun rootManagerVersionsIsAnEmptyArrayWhenNoneAreFound() {
        val text = CapabilityJson.encode(report(root = sampleRoot.copy(rootManagerVersions = emptyList())))

        assertTrue(text, text.contains("\"root_manager_versions\": []"))
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
        rootManagerVersions = listOf(RootManagerInfo("com.topjohnwu.magisk", "27.0")),
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

    private val sampleDeep = DeepDiagnostics(
        kernel = KernelInfo(
            release = "5.10.101-android12-9",
            architecture = "aarch64",
            smp = true,
            preempt = true,
            redactedVersion = "Linux version 5.10.101-android12-9 SMP PREEMPT",
        ),
        selinux = SelinuxAssessment(
            mode = SelinuxMode.ENFORCING,
            blocksAppDiagPath = true,
            consequence = "SELinux is enforcing, which normally blocks an app's own path to the diag device even on a rooted phone.",
        ),
        diagNodes = DiagNodes(
            primary = DiagNodeStat("/dev/diag", exists = false, charDevice = false, octalMode = null, ownerUser = null, ownerGroup = null),
            others = emptyList(),
        ),
        kernelDiagConfig = KernelConfigProbe.DIAG_ABSENT,
        modemInterfaces = ModemInterfaces(count = 3, names = listOf("rmnet_data0", "rmnet_data1", "qmux0")),
        captureTooling = CaptureTooling(tcpdumpPresent = false, tcpdumpPaths = emptyList(), pcapCapableInterfacePresent = true),
        radioLog = RadioLogReadout(readable = true, lineCount = 5),
    )

    private val sampleOnDeviceLayer3 = OnDeviceLayer3Verdict(
        outcome = Layer3OnDevice.NOT_POSSIBLE,
        viable = false,
        reason = "it is rooted, but its kernel has no diag device (/dev/diag is absent, and the kernel config reports no diag support)",
        laptopPath = "Use 5gto6G FieldTap on a laptop with this phone connected over USB.",
        usbDebuggingOn = false,
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
        root: RootSignals = sampleRoot,
    ): CapabilityReport = CapabilityReport(
        createdUtcMs = 1_789_050_630_000L,
        appVersion = "1.0.0",
        versionCode = 2L,
        sdkInt = 34,
        handset = handset,
        root = root,
        rootProbe = rootProbe,
        usb = sampleUsb,
        cellular = sampleCellular,
        verdict = sampleVerdict,
        onDeviceLayer3 = sampleOnDeviceLayer3,
        notes = listOf("A note."),
    )
}
