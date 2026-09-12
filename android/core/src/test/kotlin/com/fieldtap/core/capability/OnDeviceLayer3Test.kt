package com.fieldtap.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceLayer3Test {

    @Test
    fun theOnePlusCaseIsNotViableWithTheDiagAbsentReason() {
        val probe = probe(
            suStatus = SuStatus.GRANTED,
            isRoot = true,
            diag = DiagDevice.ABSENT,
            kernel = KernelConfigProbe.DIAG_ABSENT,
            layer3 = Layer3OnDevice.NOT_POSSIBLE,
        )

        val verdict = OnDeviceLayer3.verdict(probe, usb(adb = true))

        assertEquals(Layer3OnDevice.NOT_POSSIBLE, verdict.outcome)
        assertFalse(verdict.viable)
        assertEquals(
            "it is rooted, but its kernel has no diag device (/dev/diag is absent, and the kernel config reports no diag support)",
            verdict.reason,
        )
        assertTrue(verdict.laptopPath, verdict.laptopPath.contains("on a laptop with this phone connected over USB"))
        assertTrue(verdict.usbDebuggingOn)
    }

    @Test
    fun rootedWithAPresentNodeIsViable() {
        val probe = probe(
            suStatus = SuStatus.GRANTED,
            isRoot = true,
            diag = DiagDevice.PRESENT,
            kernel = KernelConfigProbe.DIAG_PRESENT,
            layer3 = Layer3OnDevice.POSSIBLE,
        )

        val verdict = OnDeviceLayer3.verdict(probe, usb())

        assertEquals(Layer3OnDevice.POSSIBLE, verdict.outcome)
        assertTrue(verdict.viable)
        assertTrue(verdict.reason, verdict.reason.contains("has a usable diag device"))
    }

    @Test
    fun deniedIsUnknownWithTheReasonAndTheLaptopPathStillAttached() {
        val probe = probe(suStatus = SuStatus.DENIED, layer3 = Layer3OnDevice.UNKNOWN)

        val verdict = OnDeviceLayer3.verdict(probe, usb(adb = false))

        assertEquals(Layer3OnDevice.UNKNOWN, verdict.outcome)
        assertFalse(verdict.viable)
        assertTrue(verdict.reason, verdict.reason.contains("not granted"))
        assertTrue(verdict.laptopPath.isNotBlank())
        assertFalse(verdict.usbDebuggingOn)
    }

    @Test
    fun aPermissionDeniedNodeReadsAsSelinuxBlocking() {
        val probe = probe(
            suStatus = SuStatus.GRANTED,
            isRoot = true,
            diag = DiagDevice.PERMISSION_DENIED,
            layer3 = Layer3OnDevice.NOT_POSSIBLE,
        )

        val verdict = OnDeviceLayer3.verdict(probe, usb())

        assertEquals(
            "it is rooted and has a /dev/diag node, but SELinux blocks access to it",
            verdict.reason,
        )
    }

    @Test
    fun passiveLooksRootedIsUnknownAndPointsAtTheCheck() {
        val root = RootDetector.assess(passive(managers = listOf("com.topjohnwu.magisk")))

        val verdict = OnDeviceLayer3.verdict(root, usb(adb = true))

        assertEquals(Layer3OnDevice.UNKNOWN, verdict.outcome)
        assertTrue(verdict.reason, verdict.reason.contains("has not been tested yet"))
        assertTrue(verdict.usbDebuggingOn)
    }

    @Test
    fun passiveNoRootPathIsNotPossible() {
        val root = RootDetector.assess(passive())

        val verdict = OnDeviceLayer3.verdict(root, usb())

        assertEquals(Layer3OnDevice.NOT_POSSIBLE, verdict.outcome)
        assertFalse(verdict.viable)
        assertTrue(verdict.reason, verdict.reason.contains("no root path"))
    }

    @Test
    fun theReasonNeverImpliesTheAppDecodesSignalling() {
        val probes = listOf(
            probe(suStatus = SuStatus.GRANTED, isRoot = true, diag = DiagDevice.ABSENT, kernel = KernelConfigProbe.DIAG_ABSENT, layer3 = Layer3OnDevice.NOT_POSSIBLE),
            probe(suStatus = SuStatus.GRANTED, isRoot = true, diag = DiagDevice.PRESENT, layer3 = Layer3OnDevice.POSSIBLE),
            probe(suStatus = SuStatus.DENIED, layer3 = Layer3OnDevice.UNKNOWN),
            probe(suStatus = SuStatus.TIMED_OUT, layer3 = Layer3OnDevice.UNKNOWN),
            probe(suStatus = SuStatus.NOT_PRESENT, layer3 = Layer3OnDevice.NOT_POSSIBLE),
        )
        for (probe in probes) {
            val reason = OnDeviceLayer3.verdict(probe, usb()).reason
            assertFalse(reason, reason.contains("RRC"))
            assertFalse(reason, reason.contains("NAS"))
            assertFalse(reason, reason.contains("SIB"))
            assertFalse(reason, reason.contains("decode"))
        }
    }

    private fun probe(
        suStatus: SuStatus,
        isRoot: Boolean = false,
        diag: DiagDevice = DiagDevice.UNKNOWN,
        kernel: KernelConfigProbe = KernelConfigProbe.CONFIG_UNAVAILABLE,
        layer3: Layer3OnDevice = Layer3OnDevice.UNKNOWN,
    ): RootProbeResult = RootProbeResult(
        suStatus = suStatus,
        isRoot = isRoot,
        selinux = SelinuxMode.ENFORCING,
        diagDevice = diag,
        kernelDiag = kernel,
        layer3 = layer3,
        elapsedMs = 100,
        message = "",
    )

    private fun usb(adb: Boolean = false): UsbDebugState =
        UsbDebugState(adbEnabled = adb, wirelessDebugEnabled = false, developerOptionsEnabled = adb)

    private fun passive(managers: List<String> = emptyList()): PassiveInputs = PassiveInputs(
        props = emptyMap(),
        buildTags = "release-keys",
        suBinariesPresent = emptyList(),
        rootManagerPackages = managers,
        writableSystemPaths = emptyList(),
        usb = usb(),
        cellular = CellularReadout(false, false, false, false, false, false),
    )
}
