package com.fieldtap.core.capability

import com.fieldtap.format.HandsetMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityMessagesTest {

    @Test
    fun theOnePlusVerdictIsWordedExactly() {
        val root = RootDetector.assess(
            passive(managers = listOf("com.topjohnwu.magisk")),
        )
        val probe = RootProbeResult(
            suStatus = SuStatus.GRANTED,
            isRoot = true,
            selinux = SelinuxMode.ENFORCING,
            diagDevice = DiagDevice.ABSENT,
            kernelDiag = KernelConfigProbe.DIAG_ABSENT,
            layer3 = Layer3OnDevice.NOT_POSSIBLE,
            elapsedMs = 420,
            message = "",
        )
        val expected =
            "Layer-3 capture is not possible on this phone: it is rooted, but its kernel has no diag device " +
                "(/dev/diag is absent, and the kernel config reports no diag support). Capture layer-3 signalling " +
                "with 5gto6G FieldTap on a laptop with this phone connected over USB. " +
                "Turn on USB debugging (Settings ▸ Developer options) so the laptop tool can capture over ADB."

        assertEquals(expected, CapabilityMessages.rootProbeMessage(root, probe, usb(adb = false, dev = true)))
    }

    @Test
    fun layer3PossibleNamesTheDiagToolAndDisclaimsDecoding() {
        val root = RootDetector.assess(passive(managers = listOf("com.topjohnwu.magisk")))
        val probe = RootProbeResult(
            suStatus = SuStatus.GRANTED,
            isRoot = true,
            selinux = SelinuxMode.PERMISSIVE,
            diagDevice = DiagDevice.PRESENT,
            kernelDiag = KernelConfigProbe.DIAG_PRESENT,
            layer3 = Layer3OnDevice.POSSIBLE,
            elapsedMs = 300,
            message = "",
        )
        val message = CapabilityMessages.rootProbeMessage(root, probe, usb())

        assertTrue(message, message.contains("has a diag device (/dev/diag)"))
        assertTrue(message, message.contains("5gto6G FieldTap itself does not decode signalling"))
    }

    @Test
    fun suOutcomeMessages() {
        val root = RootDetector.assess(passive())
        assertEquals(
            "Root access was not granted, so the diag device could not be tested.",
            CapabilityMessages.rootProbeMessage(root, probe(SuStatus.DENIED), usb()),
        )
        assertEquals(
            "The superuser prompt was not answered in time, so the diag device was not tested.",
            CapabilityMessages.rootProbeMessage(root, probe(SuStatus.TIMED_OUT), usb()),
        )
        assertEquals(
            "No su was found to run, so this phone has no working root.",
            CapabilityMessages.rootProbeMessage(root, probe(SuStatus.NOT_PRESENT), usb()),
        )
    }

    @Test
    fun looksRootedSentenceNamesTheStrongestSignalAndPointsAtTheCheck() {
        val root = RootDetector.assess(passive(managers = listOf("com.topjohnwu.magisk")))
        val line = CapabilityMessages.tier3(Layer3OnDevice.UNKNOWN, root, probe = null)

        assertTrue(line, line.contains("Magisk is installed"))
        assertTrue(line, line.contains("Tap Check with root"))
    }

    @Test
    fun noRootLayer3LineCarriesTheCaveat() {
        val root = RootDetector.assess(passive())
        val line = CapabilityMessages.tier3(Layer3OnDevice.NOT_POSSIBLE, root, probe = null)

        assertTrue(line, line.contains("no root was detected"))
        assertTrue(line, line.contains(RootDetector.CAVEAT))
    }

    @Test
    fun everyNoRootSummaryCarriesTheCaveat() {
        for (confidence in listOf(RootConfidence.NONE, RootConfidence.LOW)) {
            val report = report(rootConfidence = confidence, rootProbe = null)
            val summary = CapabilityMessages.notes(report).first()
            assertTrue(summary, summary.contains(RootDetector.CAVEAT))
        }
        // A probe that could not confirm root also carries it.
        for (status in listOf(SuStatus.DENIED, SuStatus.TIMED_OUT, SuStatus.NOT_PRESENT, SuStatus.ERROR)) {
            val report = report(rootConfidence = RootConfidence.HIGH, rootProbe = probe(status))
            val summary = CapabilityMessages.notes(report).first()
            assertTrue("$status: $summary", summary.contains(RootDetector.CAVEAT))
        }
    }

    @Test
    fun notesAreOrderedRootUsbThenCellular() {
        val report = report(rootConfidence = RootConfidence.NONE, rootProbe = null)
        val notes = CapabilityMessages.notes(report)

        assertEquals(6, notes.size)
        assertTrue(notes[0], notes[0].contains("No root was detected"))
        assertTrue(notes[1], notes[1].contains("FieldTap on a laptop captures diag over ADB"))
        assertTrue(notes[2], notes[2].contains("Phone permission"))
        assertTrue(notes[3], notes[3].contains("location"))
        assertTrue(notes[4], notes[4].contains("SIM"))
        assertTrue(notes[5], notes[5].contains("mock-location"))
    }

    @Test
    fun noAppCapabilityCopyEverImpliesDecodingSignalling() {
        val root = RootDetector.assess(passive(managers = listOf("com.topjohnwu.magisk")))
        val messages = buildList {
            add(CapabilityMessages.tier1())
            add(CapabilityMessages.tier2(true))
            add(CapabilityMessages.tier2(false))
            add(CapabilityMessages.whyUsbMatters())
            add(CapabilityMessages.laptopPath(usb(adb = true, dev = true)))
            add(CapabilityMessages.laptopPath(usb(adb = false, dev = false)))
            for (l3 in Layer3OnDevice.entries) {
                add(CapabilityMessages.tier3(l3, root, probe = null))
            }
            for (status in SuStatus.entries) {
                add(CapabilityMessages.rootProbeMessage(root, probe(status, isRoot = status == SuStatus.GRANTED), usb()))
            }
            addAll(CapabilityMessages.notes(report(rootConfidence = RootConfidence.NONE, rootProbe = null)))
            addAll(report(rootConfidence = RootConfidence.HIGH, rootProbe = null).verdict.lines)
        }

        for (message in messages) {
            assertTrue("must not name RRC as an app capability: $message", !message.contains("RRC"))
            assertTrue("must not name NAS as an app capability: $message", !message.contains("NAS"))
            assertTrue("must not name SIB as an app capability: $message", !message.contains("SIB"))
            if (message.contains("decode")) {
                assertTrue("the only mention of decode must be the disclaimer: $message", message.contains("does not decode"))
            }
        }
    }

    private fun report(rootConfidence: RootConfidence, rootProbe: RootProbeResult?): CapabilityReport {
        val root = RootDetector.assess(
            passive(managers = if (rootConfidence == RootConfidence.HIGH) listOf("com.topjohnwu.magisk") else emptyList()),
        ).copy(confidence = rootConfidence)
        val snapshot = CapabilitySnapshot(
            root = root,
            usb = usb(),
            cellular = cellular(),
            verdict = CapabilityVerdict.snapshot(root, usb(), cellular()),
        )
        return CapabilityReports.build(
            createdUtcMs = 0,
            appVersion = "1.0.0",
            versionCode = 2,
            sdkInt = 34,
            handset = HandsetMeta(manufacturer = "Test"),
            snapshot = snapshot,
            rootProbe = rootProbe,
        )
    }

    private fun probe(status: SuStatus, isRoot: Boolean = false): RootProbeResult = RootProbeResult(
        suStatus = status,
        isRoot = isRoot,
        selinux = SelinuxMode.UNKNOWN,
        diagDevice = DiagDevice.UNKNOWN,
        kernelDiag = KernelConfigProbe.CONFIG_UNAVAILABLE,
        layer3 = Layer3OnDevice.UNKNOWN,
        elapsedMs = 10,
        message = "",
    )

    private fun usb(adb: Boolean = false, wifi: Boolean = false, dev: Boolean = false): UsbDebugState =
        UsbDebugState(adbEnabled = adb, wirelessDebugEnabled = wifi, developerOptionsEnabled = dev)

    private fun cellular(): CellularReadout = CellularReadout(
        readPhoneStateGranted = false,
        preciseLocationGranted = false,
        locationServicesEnabled = false,
        simReady = false,
        mockLocationAppSet = false,
        buildAcceptsMockLocations = false,
    )

    private fun passive(managers: List<String> = emptyList()): PassiveInputs = PassiveInputs(
        props = emptyMap(),
        buildTags = "release-keys",
        suBinariesPresent = emptyList(),
        rootManagerPackages = managers,
        writableSystemPaths = emptyList(),
        usb = usb(),
        cellular = cellular(),
    )
}
