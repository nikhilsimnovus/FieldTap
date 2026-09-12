package com.fieldtap.ui.probe

import com.fieldtap.core.capability.CapabilityMessages
import com.fieldtap.core.capability.CaptureAnswer
import com.fieldtap.core.capability.CaptureTooling
import com.fieldtap.core.capability.DiagDevice
import com.fieldtap.core.capability.DiagNodeStat
import com.fieldtap.core.capability.Layer3OnDevice
import com.fieldtap.core.capability.OnDeviceLayer3
import com.fieldtap.core.capability.RadioLogReadout
import com.fieldtap.core.capability.RootConfidence
import com.fieldtap.core.capability.RootManagerInfo
import com.fieldtap.core.capability.UsbDebugState
import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.RadioListener
import com.fieldtap.ui.setup.SetupSamples
import com.fieldtap.ui.theme.StatusTone
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbePresentationTest {

    @Test
    fun theThreeRestrictedListenersRefusedAreRefusedAsExpected() {
        listOf(RadioListener.PHYSICAL_CHANNEL_CONFIG, RadioListener.BARRING_INFO, RadioListener.REGISTRATION_FAILED).forEach { listener ->
            assertEquals(ListenerWord.REFUSED_EXPECTED, ProbePresentation.listenerWord(listener, ListenerOutcome.REFUSED_BY_PLATFORM))
            assertEquals(ListenerWord.REFUSED_EXPECTED, ProbePresentation.listenerWord(listener, ListenerOutcome.UNREGISTERED))
            assertEquals(ListenerWord.REGISTERED, ProbePresentation.listenerWord(listener, ListenerOutcome.REGISTERED))
        }
        assertEquals(StatusTone.NEUTRAL, ListenerWord.REFUSED_EXPECTED.tone)
    }

    @Test
    fun otherListenersAreWordedAndTonedByOutcome() {
        assertEquals(ListenerWord.REGISTERED, ProbePresentation.listenerWord(RadioListener.SERVICE_STATE, ListenerOutcome.REGISTERED))
        assertEquals(ListenerWord.MISSING_PERMISSION, ProbePresentation.listenerWord(RadioListener.CELL_INFO_PUSH, ListenerOutcome.MISSING_PERMISSION))
        assertEquals(ListenerWord.REFUSED, ProbePresentation.listenerWord(RadioListener.SIGNAL_STRENGTHS, ListenerOutcome.REFUSED_BY_PLATFORM))
        assertEquals(ListenerWord.NOT_REGISTERED, ProbePresentation.listenerWord(RadioListener.DISPLAY_INFO, ListenerOutcome.UNREGISTERED))
        assertEquals(ListenerWord.FAILED, ProbePresentation.listenerWord(RadioListener.BARRING_INFO, ListenerOutcome.FAILED))
        assertEquals(StatusTone.SUCCESS, ListenerWord.REGISTERED.tone)
        assertEquals(StatusTone.WARNING, ListenerWord.MISSING_PERMISSION.tone)
        assertEquals(StatusTone.ERROR, ListenerWord.REFUSED.tone)
        assertEquals(StatusTone.ERROR, ListenerWord.FAILED.tone)
    }

    @Test
    fun everyListenerIsListedInOrderAndAMissingOneNeverRegistered() {
        val rows = ProbePresentation.listenerRows(mapOf(RadioListener.SERVICE_STATE to ListenerOutcome.REGISTERED))
        assertEquals(RadioListener.entries.toList(), rows.map { it.first })
        assertEquals(ListenerOutcome.REGISTERED, rows.single { it.first == RadioListener.SERVICE_STATE }.second)
        assertEquals(
            RadioListener.entries.size - 1,
            rows.count { it.second == ListenerOutcome.UNREGISTERED },
        )
    }

    @Test
    fun timestampsAnswerYesNoOrNotEnough() {
        assertEquals(ProbeAnswer.YES, ProbePresentation.timestamps(true))
        assertEquals(ProbeAnswer.NO, ProbePresentation.timestamps(false))
        assertEquals(ProbeAnswer.NOT_ENOUGH, ProbePresentation.timestamps(null))
    }

    @Test
    fun aRangeNeedsBothEnds() {
        assertNull(ProbePresentation.range(null, null))
        assertNull(ProbePresentation.range(-110, null))
        assertNull(ProbePresentation.range(null, -70))
        assertEquals(-110..-70, ProbePresentation.range(-110, -70))
        assertEquals(-3..-3, ProbePresentation.range(-3, -3))
        assertEquals(-110..-70, ProbePresentation.range(-70, -110))
    }

    @Test
    fun secondsHaveOneDecimalInTheLocale() {
        assertEquals("30.0", ProbePresentation.seconds(30_000, Locale.UK))
        assertEquals("1.5", ProbePresentation.seconds(1_500, Locale.UK))
        assertEquals("0.1", ProbePresentation.seconds(50, Locale.UK))
        assertEquals("1,5", ProbePresentation.seconds(1_500, Locale.GERMANY))
    }

    @Test
    fun permissionsAreNamedByWhatTheyAllow() {
        assertEquals(ProbePermissionLabel.PRECISE_LOCATION, ProbePresentation.permissionLabel("android.permission.ACCESS_FINE_LOCATION"))
        assertEquals(ProbePermissionLabel.APPROXIMATE_LOCATION, ProbePresentation.permissionLabel("android.permission.ACCESS_COARSE_LOCATION"))
        assertEquals(ProbePermissionLabel.NOTIFICATIONS, ProbePresentation.permissionLabel("android.permission.POST_NOTIFICATIONS"))
        assertEquals(ProbePermissionLabel.PHONE, ProbePresentation.permissionLabel("android.permission.READ_PHONE_STATE"))
        assertEquals(ProbePermissionLabel.OTHER, ProbePresentation.permissionLabel("android.permission.CAMERA"))
    }

    @Test
    fun valuesAreJoinedWithAMiddleDotOrNotAtAll() {
        assertNull(ProbePresentation.joined(emptyList()))
        assertNull(ProbePresentation.joined(listOf(" ", "")))
        assertEquals("LTE", ProbePresentation.joined(listOf("LTE")))
        assertEquals("LTE · NR", ProbePresentation.joined(listOf("LTE", "", "NR")))
    }

    @Test
    fun theVerdictIsThePassiveSnapshotUntilARootCheckFoldsIn() {
        val snapshot = SetupSamples.capabilitySnapshot(rootManagerPackages = listOf("com.topjohnwu.magisk"))
        // A rooted phone with no root check yet cannot say whether it has a diag device.
        assertEquals(Layer3OnDevice.UNKNOWN, ProbePresentation.verdict(snapshot, null).layer3Signalling)
        assertEquals(snapshot.verdict, ProbePresentation.verdict(snapshot, null))

        // The OnePlus case: rooted, but /dev/diag absent -> not possible on this phone.
        val probe = SetupSamples.rootProbeResult()
        assertEquals(Layer3OnDevice.NOT_POSSIBLE, ProbePresentation.verdict(snapshot, probe).layer3Signalling)
    }

    @Test
    fun theTopChipWarnsOnlyWhenMeasurementWouldReturnNothing() {
        assertTrue(ProbePresentation.canMeasureNow(SetupSamples.capabilitySnapshot().cellular))
        val noLocation = SetupSamples.capabilitySnapshot(preciseLocationGranted = false).cellular
        assertEquals(false, ProbePresentation.canMeasureNow(noLocation))
        val servicesOff = SetupSamples.capabilitySnapshot(locationServicesEnabled = false).cellular
        assertEquals(false, ProbePresentation.canMeasureNow(servicesOff))
    }

    @Test
    fun tierAndLayer3TonesFollowTheAnswer() {
        assertEquals(StatusTone.SUCCESS, ProbePresentation.captureAnswerTone(CaptureAnswer.YES))
        assertEquals(StatusTone.WARNING, ProbePresentation.captureAnswerTone(CaptureAnswer.NO))
        assertEquals(StatusTone.NEUTRAL, ProbePresentation.captureAnswerTone(CaptureAnswer.UNKNOWN))
        // Layer-3 being impossible is a normal fact, not a fault: grey, never red.
        assertEquals(StatusTone.SUCCESS, ProbePresentation.layer3Tone(Layer3OnDevice.POSSIBLE))
        assertEquals(StatusTone.NEUTRAL, ProbePresentation.layer3Tone(Layer3OnDevice.NOT_POSSIBLE))
        assertEquals(StatusTone.NEUTRAL, ProbePresentation.layer3Tone(Layer3OnDevice.UNKNOWN))
    }

    @Test
    fun theRootConfidenceStaysCalm() {
        assertEquals(StatusTone.INFO, ProbePresentation.rootConfidenceTone(RootConfidence.HIGH))
        assertEquals(StatusTone.INFO, ProbePresentation.rootConfidenceTone(RootConfidence.MEDIUM))
        assertEquals(StatusTone.NEUTRAL, ProbePresentation.rootConfidenceTone(RootConfidence.LOW))
        assertEquals(StatusTone.NEUTRAL, ProbePresentation.rootConfidenceTone(RootConfidence.NONE))
    }

    @Test
    fun theLayer3DetailAddsTheUsbLineOnlyWhenCaptureIsNotPossible() {
        val snapshot = SetupSamples.capabilitySnapshot(rootManagerPackages = listOf("com.topjohnwu.magisk"))

        val notPossible = ProbePresentation.verdict(snapshot, SetupSamples.rootProbeResult())
        assertEquals(Layer3OnDevice.NOT_POSSIBLE, notPossible.layer3Signalling)
        val laptop = notPossible.laptopPath!!
        assertTrue(ProbePresentation.layer3Detail(notPossible).contains(laptop))

        val possible = ProbePresentation.verdict(
            snapshot,
            SetupSamples.rootProbeResult(diagDevice = DiagDevice.PRESENT, layer3 = Layer3OnDevice.POSSIBLE),
        )
        assertEquals(Layer3OnDevice.POSSIBLE, possible.layer3Signalling)
        assertNull(possible.laptopPath)
        assertEquals(possible.lines.last(), ProbePresentation.layer3Detail(possible))
    }

    // ---- Deep diagnostics (deep-root-spec §5) ----

    @Test
    fun deepRowsMapEveryFactInSpecOrderNeutralExceptRootManager() {
        val rows = ProbePresentation.deepRows(SetupSamples.deepDiagnostics(), listOf(SetupSamples.rootManager()), USB_ON, LABELS)
        assertEquals(9, rows.size)
        assertEquals(ProbePresentation.DeepRow("Root manager", "Magisk 27.0", StatusTone.INFO), rows[0])
        assertEquals(ProbePresentation.DeepRow("Kernel", "5.10.101-android12-9-g0 · aarch64 · SMP PREEMPT", StatusTone.NEUTRAL), rows[1])
        assertEquals(ProbePresentation.DeepRow("SELinux", "Enforcing", StatusTone.NEUTRAL), rows[2])
        assertEquals(ProbePresentation.DeepRow("Diag device", "Absent", StatusTone.NEUTRAL), rows[3])
        assertEquals(ProbePresentation.DeepRow("Kernel diag support", "Not in kernel config", StatusTone.NEUTRAL), rows[4])
        assertEquals(ProbePresentation.DeepRow("Modem interfaces", "3 — rmnet_data0, rmnet_data1, qmux0", StatusTone.NEUTRAL), rows[5])
        assertEquals(ProbePresentation.DeepRow("Capture tooling", "Not present", StatusTone.NEUTRAL), rows[6])
        assertEquals(ProbePresentation.DeepRow("Radio log", "Readable (5 lines sampled)", StatusTone.NEUTRAL), rows[7])
        assertEquals(ProbePresentation.DeepRow("USB debugging", "On", StatusTone.NEUTRAL), rows[8])
        // Only a found root manager is INFO; every other fact stays neutral (facts, not faults).
        assertEquals(listOf(StatusTone.INFO), rows.map { it.tone }.filter { it != StatusTone.NEUTRAL })
    }

    @Test
    fun deepRowsFormatPresentAndDeniedNodesCapturePathsAndUsbStates() {
        val present = SetupSamples.deepDiagnostics(
            diagPrimary = DiagNodeStat("/dev/diag", exists = true, charDevice = true, octalMode = "660", ownerUser = "radio", ownerGroup = "radio"),
            capture = CaptureTooling(tcpdumpPresent = true, tcpdumpPaths = listOf("/system/bin/tcpdump"), pcapCapableInterfacePresent = true),
            radioLog = RadioLogReadout(readable = false, lineCount = null),
        )
        val rows = ProbePresentation.deepRows(present, emptyList(), USB_OFF_DEV_OFF, LABELS)
        // No root manager found stays neutral, not INFO.
        assertEquals(ProbePresentation.DeepRow("Root manager", "None found", StatusTone.NEUTRAL), rows[0])
        assertEquals("Present · 660 radio:radio", rows[3].value)
        assertEquals("tcpdump present (/system/bin/tcpdump)", rows[6].value)
        assertEquals("Not readable", rows[7].value)
        assertEquals("Off — developer options off", rows[8].value)

        val denied = SetupSamples.deepDiagnostics(
            diagPrimary = DiagNodeStat("/dev/diag", exists = true, charDevice = true, octalMode = null, ownerUser = null, ownerGroup = null),
        )
        assertEquals("Present, access denied", ProbePresentation.deepRows(denied, emptyList(), USB_ON, LABELS)[3].value)
        // Developer options on but adb off: plain "Off", not the developer-options note.
        assertEquals("Off", ProbePresentation.deepRows(SetupSamples.deepDiagnostics(), emptyList(), USB_OFF_DEV_ON, LABELS)[8].value)
    }

    @Test
    fun rootManagerLineNamesKnownManagersAndFallsBackToTheId() {
        assertEquals("None found", ProbePresentation.rootManagerLine(emptyList(), "None found"))
        assertEquals("Magisk 27.0", ProbePresentation.rootManagerLine(listOf(RootManagerInfo("com.topjohnwu.magisk", "27.0")), "None found"))
        assertEquals("Magisk", ProbePresentation.rootManagerLine(listOf(RootManagerInfo("com.topjohnwu.magisk", null)), "None found"))
        assertEquals(
            "Magisk 27.0, KernelSU 0.9.5",
            ProbePresentation.rootManagerLine(
                listOf(RootManagerInfo("com.topjohnwu.magisk", "27.0"), RootManagerInfo("me.weishu.kernelsu", "0.9.5")),
                "None found",
            ),
        )
        assertEquals("com.acme.root 1", ProbePresentation.rootManagerLine(listOf(RootManagerInfo("com.acme.root", "1")), "None found"))
    }

    @Test
    fun theKernelRowFallsBackWhenReleaseIsUnknownAndDropsEmptyFlags() {
        val unknown = SetupSamples.deepDiagnostics(kernelRelease = null, architecture = null, smp = false, preempt = false)
        assertEquals("Unknown", ProbePresentation.deepRows(unknown, emptyList(), USB_ON, LABELS)[1].value)
        val onlySmp = SetupSamples.deepDiagnostics(kernelRelease = "5.10", architecture = null, smp = true, preempt = false)
        assertEquals("5.10 · SMP", ProbePresentation.deepRows(onlySmp, emptyList(), USB_ON, LABELS)[1].value)
    }

    @Test
    fun deepPassiveRowsAreRootManagerAndUsbOnly() {
        val rows = ProbePresentation.deepPassiveRows(listOf(SetupSamples.rootManager()), USB_ON, LABELS)
        assertEquals(listOf("Root manager", "USB debugging"), rows.map { it.label })
        assertEquals("Magisk 27.0", rows[0].value)
        assertEquals("On", rows[1].value)
    }

    @Test
    fun theLayer3SubVerdictMarkIsGreyUnlessViable() {
        // "Not viable" is a normal fact, never an error: grey, never red.
        assertEquals(StatusTone.SUCCESS, ProbePresentation.layer3SubVerdictTone(Layer3OnDevice.POSSIBLE))
        assertEquals(StatusTone.NEUTRAL, ProbePresentation.layer3SubVerdictTone(Layer3OnDevice.NOT_POSSIBLE))
        assertEquals(StatusTone.NEUTRAL, ProbePresentation.layer3SubVerdictTone(Layer3OnDevice.UNKNOWN))
    }

    @Test
    fun theLayer3SubVerdictDetailAddsTheLaptopPathAndUsbLineOnlyWhenNotViable() {
        val notViable = OnDeviceLayer3.verdict(SetupSamples.rootProbeResult(), USB_ON)
        assertFalse(notViable.viable)
        val detail = ProbePresentation.layer3SubVerdictDetail(notViable, USB_ON)
        assertTrue(detail, detail.first().isUpperCase())
        assertTrue(detail, detail.contains(notViable.laptopPath))
        assertTrue(detail, detail.contains(CapabilityMessages.laptopPath(USB_ON)))

        val viable = OnDeviceLayer3.verdict(
            SetupSamples.rootProbeResult(diagDevice = DiagDevice.PRESENT, layer3 = Layer3OnDevice.POSSIBLE),
            USB_ON,
        )
        assertTrue(viable.viable)
        val viableDetail = ProbePresentation.layer3SubVerdictDetail(viable, USB_ON)
        assertFalse(viableDetail, viableDetail.contains(viable.laptopPath))
        assertTrue(viableDetail, viableDetail.endsWith("."))
    }

    private companion object {
        val USB_ON = UsbDebugState(adbEnabled = true, wirelessDebugEnabled = false, developerOptionsEnabled = true)
        val USB_OFF_DEV_OFF = UsbDebugState(adbEnabled = false, wirelessDebugEnabled = false, developerOptionsEnabled = false)
        val USB_OFF_DEV_ON = UsbDebugState(adbEnabled = false, wirelessDebugEnabled = false, developerOptionsEnabled = true)

        /** The structural labels and value words the screen resolves from resources, mirrored for the pure mapping. */
        val LABELS = ProbePresentation.DeepDiagnosticsLabels(
            rootManager = "Root manager",
            kernel = "Kernel",
            selinux = "SELinux",
            diagDevice = "Diag device",
            kernelDiagConfig = "Kernel diag support",
            modemInterfaces = "Modem interfaces",
            captureTooling = "Capture tooling",
            radioLog = "Radio log",
            usbDebugging = "USB debugging",
            rootManagerNone = "None found",
            unknown = "Unknown",
            smp = "SMP",
            preempt = "PREEMPT",
            selinuxEnforcing = "Enforcing",
            selinuxPermissive = "Permissive",
            selinuxDisabled = "Disabled",
            selinuxUnknown = "Unknown",
            diagAbsent = "Absent",
            diagPresentMeta = "Present · %1\$s",
            diagPresentDenied = "Present, access denied",
            kernelDiagPresent = "In kernel config",
            kernelDiagAbsent = "Not in kernel config",
            kernelDiagUnavailable = "Config unreadable",
            modemNone = "None",
            modemValue = "%1\$d — %2\$s",
            capturePresent = "tcpdump present (%1\$s)",
            captureNone = "Not present",
            radioReadable = "Readable (%1\$d lines sampled)",
            radioNotReadable = "Not readable",
            usbOn = "On",
            usbOffDevOptions = "Off — developer options off",
            usbOff = "Off",
        )
    }
}
