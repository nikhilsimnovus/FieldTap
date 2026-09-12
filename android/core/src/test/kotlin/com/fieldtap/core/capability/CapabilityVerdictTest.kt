package com.fieldtap.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilityVerdictTest {

    // ---- layer3(): every row of the §3.5 table ----

    @Test
    fun noProbeHighIsUnknown() {
        assertEquals(Layer3OnDevice.UNKNOWN, CapabilityVerdict.layer3(root(RootConfidence.HIGH), null))
    }

    @Test
    fun noProbeMediumLowNoneAreNotPossible() {
        assertEquals(Layer3OnDevice.NOT_POSSIBLE, CapabilityVerdict.layer3(root(RootConfidence.MEDIUM), null))
        assertEquals(Layer3OnDevice.NOT_POSSIBLE, CapabilityVerdict.layer3(root(RootConfidence.LOW), null))
        assertEquals(Layer3OnDevice.NOT_POSSIBLE, CapabilityVerdict.layer3(root(RootConfidence.NONE), null))
    }

    @Test
    fun grantedRootWithPresentNodeIsPossibleEvenUnderEnforcing() {
        val probe = probe(
            suStatus = SuStatus.GRANTED,
            isRoot = true,
            diag = DiagDevice.PRESENT,
            kernel = KernelConfigProbe.DIAG_PRESENT,
            selinux = SelinuxMode.ENFORCING,
        )
        assertEquals(Layer3OnDevice.POSSIBLE, CapabilityVerdict.layer3(root(RootConfidence.HIGH), probe))
    }

    @Test
    fun grantedRootWithAbsentNodeAndNoKernelDiagIsNotPossible_theOnePlusCase() {
        val probe = probe(
            suStatus = SuStatus.GRANTED,
            isRoot = true,
            diag = DiagDevice.ABSENT,
            kernel = KernelConfigProbe.DIAG_ABSENT,
            selinux = SelinuxMode.ENFORCING,
        )
        assertEquals(Layer3OnDevice.NOT_POSSIBLE, CapabilityVerdict.layer3(root(RootConfidence.HIGH), probe))
    }

    @Test
    fun grantedRootWithAbsentNodeAndConfigUnavailableIsNotPossible() {
        val probe = probe(suStatus = SuStatus.GRANTED, isRoot = true, diag = DiagDevice.ABSENT, kernel = KernelConfigProbe.CONFIG_UNAVAILABLE)
        assertEquals(Layer3OnDevice.NOT_POSSIBLE, CapabilityVerdict.layer3(root(RootConfidence.HIGH), probe))
    }

    @Test
    fun grantedRootWithPermissionDeniedNodeIsNotPossible() {
        val probe = probe(
            suStatus = SuStatus.GRANTED,
            isRoot = true,
            diag = DiagDevice.PERMISSION_DENIED,
            kernel = KernelConfigProbe.DIAG_PRESENT,
            selinux = SelinuxMode.ENFORCING,
        )
        assertEquals(Layer3OnDevice.NOT_POSSIBLE, CapabilityVerdict.layer3(root(RootConfidence.HIGH), probe))
    }

    @Test
    fun grantedRootWithUnknownNodeFollowsKernelConfig() {
        val absent = probe(suStatus = SuStatus.GRANTED, isRoot = true, diag = DiagDevice.UNKNOWN, kernel = KernelConfigProbe.DIAG_ABSENT)
        assertEquals(Layer3OnDevice.NOT_POSSIBLE, CapabilityVerdict.layer3(root(RootConfidence.HIGH), absent))

        val present = probe(suStatus = SuStatus.GRANTED, isRoot = true, diag = DiagDevice.UNKNOWN, kernel = KernelConfigProbe.DIAG_PRESENT)
        assertEquals(Layer3OnDevice.UNKNOWN, CapabilityVerdict.layer3(root(RootConfidence.HIGH), present))

        val unread = probe(suStatus = SuStatus.GRANTED, isRoot = true, diag = DiagDevice.UNKNOWN, kernel = KernelConfigProbe.CONFIG_UNAVAILABLE)
        assertEquals(Layer3OnDevice.UNKNOWN, CapabilityVerdict.layer3(root(RootConfidence.HIGH), unread))
    }

    @Test
    fun deniedAndTimedOutAndErrorAreUnknown() {
        assertEquals(Layer3OnDevice.UNKNOWN, CapabilityVerdict.layer3(root(RootConfidence.HIGH), probe(suStatus = SuStatus.DENIED)))
        assertEquals(Layer3OnDevice.UNKNOWN, CapabilityVerdict.layer3(root(RootConfidence.HIGH), probe(suStatus = SuStatus.TIMED_OUT)))
        assertEquals(Layer3OnDevice.UNKNOWN, CapabilityVerdict.layer3(root(RootConfidence.HIGH), probe(suStatus = SuStatus.ERROR)))
    }

    @Test
    fun suNotPresentIsNotPossible() {
        assertEquals(Layer3OnDevice.NOT_POSSIBLE, CapabilityVerdict.layer3(root(RootConfidence.HIGH), probe(suStatus = SuStatus.NOT_PRESENT)))
    }

    @Test
    fun grantedButNotRootIsNotPossible() {
        val probe = probe(suStatus = SuStatus.GRANTED, isRoot = false, diag = DiagDevice.PRESENT, kernel = KernelConfigProbe.DIAG_PRESENT)
        assertEquals(Layer3OnDevice.NOT_POSSIBLE, CapabilityVerdict.layer3(root(RootConfidence.HIGH), probe))
    }

    // ---- snapshot / withRootProbe tiers ----

    @Test
    fun tier1IsAlwaysYesAndThreeLines() {
        val verdict = CapabilityVerdict.snapshot(root(RootConfidence.NONE), usb(), cellular(phone = false))

        assertEquals(CaptureAnswer.YES, verdict.publicApiMeasurements)
        assertEquals(3, verdict.lines.size)
    }

    @Test
    fun tier2FlipsOnReadPhoneState() {
        assertEquals(CaptureAnswer.NO, CapabilityVerdict.snapshot(root(RootConfidence.NONE), usb(), cellular(phone = false)).pushCellUpdates)
        assertEquals(CaptureAnswer.YES, CapabilityVerdict.snapshot(root(RootConfidence.NONE), usb(), cellular(phone = true)).pushCellUpdates)
    }

    @Test
    fun laptopPathPresentExactlyWhenLayer3IsNotPossible() {
        // POSSIBLE -> no laptop path.
        val possibleProbe = probe(suStatus = SuStatus.GRANTED, isRoot = true, diag = DiagDevice.PRESENT, kernel = KernelConfigProbe.DIAG_PRESENT)
        val possible = CapabilityVerdict.withRootProbe(root(RootConfidence.HIGH), usb(), cellular(), possibleProbe)
        assertEquals(Layer3OnDevice.POSSIBLE, possible.layer3Signalling)
        assertNull(possible.laptopPath)

        // NOT_POSSIBLE (no root) -> laptop path present.
        val notPossible = CapabilityVerdict.snapshot(root(RootConfidence.NONE), usb(), cellular())
        assertEquals(Layer3OnDevice.NOT_POSSIBLE, notPossible.layer3Signalling)
        assertNotNull(notPossible.laptopPath)

        // UNKNOWN (HIGH, no probe) -> laptop path present.
        val unknown = CapabilityVerdict.snapshot(root(RootConfidence.HIGH), usb(), cellular())
        assertEquals(Layer3OnDevice.UNKNOWN, unknown.layer3Signalling)
        assertNotNull(unknown.laptopPath)
    }

    @Test
    fun laptopPathReflectsAdbAndDeveloperOptions() {
        val adbOn = CapabilityVerdict.snapshot(root(RootConfidence.NONE), usb(adb = true, dev = true), cellular()).laptopPath
        assertNotNull(adbOn)
        assertEquals(true, adbOn!!.contains("USB debugging is on"))

        val adbOff = CapabilityVerdict.snapshot(root(RootConfidence.NONE), usb(adb = false, dev = false), cellular()).laptopPath!!
        assertEquals(true, adbOff.contains("Developer options are off."))
        assertEquals(true, adbOff.contains("Turn on USB debugging"))
    }

    private fun root(confidence: RootConfidence): RootSignals = RootSignals(
        suBinariesPresent = emptyList(),
        rootManagerPackages = if (confidence == RootConfidence.HIGH) listOf("com.topjohnwu.magisk") else emptyList(),
        buildTagsTestKeys = false,
        debuggable = false,
        secureOff = false,
        writableSystemPaths = emptyList(),
        confidence = confidence,
        caveat = RootDetector.CAVEAT,
    )

    private fun probe(
        suStatus: SuStatus,
        isRoot: Boolean = false,
        diag: DiagDevice = DiagDevice.UNKNOWN,
        kernel: KernelConfigProbe = KernelConfigProbe.CONFIG_UNAVAILABLE,
        selinux: SelinuxMode = SelinuxMode.UNKNOWN,
    ): RootProbeResult = RootProbeResult(
        suStatus = suStatus,
        isRoot = isRoot,
        selinux = selinux,
        diagDevice = diag,
        kernelDiag = kernel,
        layer3 = Layer3OnDevice.UNKNOWN,
        elapsedMs = 100,
        message = "",
    )

    private fun usb(adb: Boolean = false, wifi: Boolean = false, dev: Boolean = false): UsbDebugState =
        UsbDebugState(adbEnabled = adb, wirelessDebugEnabled = wifi, developerOptionsEnabled = dev)

    private fun cellular(phone: Boolean = false): CellularReadout = CellularReadout(
        readPhoneStateGranted = phone,
        preciseLocationGranted = false,
        locationServicesEnabled = false,
        simReady = false,
        mockLocationAppSet = false,
        buildAcceptsMockLocations = false,
    )
}
