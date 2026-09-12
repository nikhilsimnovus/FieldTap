package com.fieldtap.core.capability

import com.fieldtap.format.HandsetMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole capability chain on the JVM, from raw command strings to the exported JSON, for the two honest
 * outcomes the CI emulator can produce (a userdebug image with no `/dev/diag`). This mirrors what
 * `AndroidCapabilityInspector.checkWithRoot` assembles, so the correctness of the parse → verdict → message →
 * export path does not depend on the emulator — it is proved here.
 *
 * Owner: workstream `capability-core`.
 */
class CapabilityPipelineTest {

    /**
     * A rooted userdebug device with no diag node (what a rooted phone, or the emulator if its `su` grants root,
     * looks like): `id` shows uid=0, `getenforce` a mode, `ls -l /dev/diag` "No such file", and `/proc/config.gz`
     * is unreadable. The honest verdict is "layer-3 not possible on this phone" with the laptop-over-USB path.
     */
    @Test
    fun rootedWithNoDiagNodeIsNotPossibleAllTheWayToJson() {
        val root = RootDetector.assess(passive(managers = listOf("com.topjohnwu.magisk")))
        val probe = probeFromRawOutput(
            root = root,
            id = "uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0",
            getenforce = "Permissive",
            diagLs = "ls: /dev/diag: No such file or directory",
            kernel = KernelConfigProbe.CONFIG_UNAVAILABLE,
        )

        assertEquals(SuStatus.GRANTED, probe.suStatus)
        assertTrue(probe.isRoot)
        assertEquals(SelinuxMode.PERMISSIVE, probe.selinux)
        assertEquals(DiagDevice.ABSENT, probe.diagDevice)
        assertEquals(Layer3OnDevice.NOT_POSSIBLE, probe.layer3)

        val usb = UsbDebugState(adbEnabled = true, wirelessDebugEnabled = false, developerOptionsEnabled = false)
        val cellular = cellular(phone = false)
        val verdict = CapabilityVerdict.withRootProbe(root, usb, cellular, probe)
        assertEquals(CaptureAnswer.YES, verdict.publicApiMeasurements)
        assertEquals(CaptureAnswer.NO, verdict.pushCellUpdates)
        assertEquals(Layer3OnDevice.NOT_POSSIBLE, verdict.layer3Signalling)
        assertNotNull(verdict.laptopPath)

        val message = CapabilityMessages.rootProbeMessage(root, probe, usb)
        assertTrue(message, message.contains("not possible on this phone"))
        assertTrue(message, message.contains("/dev/diag is absent"))
        assertTrue(message, message.contains("on a laptop with this phone connected over USB"))
        assertTrue(message, message.contains("USB debugging"))

        val json = CapabilityJson.encode(report(root, usb, cellular, probe))
        assertTrue(json, json.contains("\"format\": \"fieldtap-capability/1\""))
        assertTrue(json, json.contains("\"layer3_signalling\": \"NOT_POSSIBLE\""))
        assertTrue(json, json.contains("\"diag_device\": \"ABSENT\""))
        assertTrue(json, json.contains("\"public_api_measurements\": \"YES\""))
        assertTrue(json, json.contains("\"push_cell_updates\": \"NO\""))
        assertTrue(json, json.contains("\"adb_enabled\": true"))
    }

    /**
     * A device whose `su` refuses the app (the emulator's most likely answer): the honest layer-3 answer is
     * UNKNOWN — the device could not be tested — never a claim, and the laptop path is still offered.
     */
    @Test
    fun suDeniedIsHonestlyUnknownWithTheLaptopPath() {
        val root = RootDetector.assess(passive())
        val probe = RootProbeResult(
            suStatus = SuStatus.DENIED,
            isRoot = SuOutputParser.isRoot("su: uid 10123 not allowed to su"),
            selinux = SelinuxParser.parse(null),
            diagDevice = DiagParser.device(null),
            kernelDiag = KernelConfigProbe.CONFIG_UNAVAILABLE,
            layer3 = Layer3OnDevice.UNKNOWN,
            elapsedMs = 12,
            message = "",
        ).let { it.copy(layer3 = CapabilityVerdict.layer3(root, it)) }

        assertEquals(Layer3OnDevice.UNKNOWN, probe.layer3)
        val usb = UsbDebugState(adbEnabled = true, wirelessDebugEnabled = false, developerOptionsEnabled = false)
        val verdict = CapabilityVerdict.withRootProbe(root, usb, cellular(phone = false), probe)
        assertEquals(Layer3OnDevice.UNKNOWN, verdict.layer3Signalling)
        assertNotNull(verdict.laptopPath)
        assertEquals(
            "Root access was not granted, so the diag device could not be tested.",
            CapabilityMessages.rootProbeMessage(root, probe, usb),
        )
    }

    /** Builds a [RootProbeResult] from raw command strings the same way the app's inspector does. */
    private fun probeFromRawOutput(
        root: RootSignals,
        id: String,
        getenforce: String,
        diagLs: String,
        kernel: KernelConfigProbe,
    ): RootProbeResult {
        val provisional = RootProbeResult(
            suStatus = if (SuOutputParser.isRoot(id)) SuStatus.GRANTED else SuStatus.DENIED,
            isRoot = SuOutputParser.isRoot(id),
            selinux = SelinuxParser.parse(getenforce),
            diagDevice = DiagParser.device(diagLs),
            kernelDiag = kernel,
            layer3 = Layer3OnDevice.UNKNOWN,
            elapsedMs = 42,
            message = "",
        )
        return provisional.copy(layer3 = CapabilityVerdict.layer3(root, provisional))
    }

    private fun report(root: RootSignals, usb: UsbDebugState, cellular: CellularReadout, probe: RootProbeResult): CapabilityReport =
        CapabilityReports.build(
            createdUtcMs = 1_789_050_600_000L,
            appVersion = "1.0.0",
            versionCode = 2,
            sdkInt = 36,
            handset = HandsetMeta(manufacturer = "Google", model = "sdk_gphone64_x86_64"),
            snapshot = CapabilitySnapshot(root, usb, cellular, CapabilityVerdict.snapshot(root, usb, cellular)),
            rootProbe = probe,
        )

    private fun passive(managers: List<String> = emptyList()): PassiveInputs = PassiveInputs(
        props = emptyMap(),
        buildTags = "release-keys",
        suBinariesPresent = emptyList(),
        rootManagerPackages = managers,
        writableSystemPaths = emptyList(),
        usb = UsbDebugState(adbEnabled = true, wirelessDebugEnabled = false, developerOptionsEnabled = false),
        cellular = cellular(phone = false),
    )

    private fun cellular(phone: Boolean): CellularReadout = CellularReadout(
        readPhoneStateGranted = phone,
        preciseLocationGranted = false,
        locationServicesEnabled = false,
        simReady = false,
        mockLocationAppSet = false,
        buildAcceptsMockLocations = false,
    )
}
