package com.fieldtap.platform.capability

import com.fieldtap.core.capability.KernelConfigProbe
import com.fieldtap.core.capability.SuStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperuserRunnerTest {

    @Test
    fun ioExceptionMeansSuNotPresent() {
        val raw = SuperuserRunner.parseSuOutput("", SuperuserRunner.EXIT_UNSET, timedOut = false, threwIoException = true)

        assertEquals(SuStatus.NOT_PRESENT, raw.suStatus)
        assertNull(raw.idOutput)
        assertNull(raw.getenforceOutput)
        assertNull(raw.diagLsOutput)
        assertEquals(KernelConfigProbe.CONFIG_UNAVAILABLE, raw.kernelConfigDiag)
    }

    @Test
    fun timeoutFlagMeansTimedOut() {
        val raw = SuperuserRunner.parseSuOutput(partialGranted(), SuperuserRunner.EXIT_UNSET, timedOut = true, threwIoException = false)

        assertEquals(SuStatus.TIMED_OUT, raw.suStatus)
        assertNull(raw.idOutput)
        assertEquals(KernelConfigProbe.CONFIG_UNAVAILABLE, raw.kernelConfigDiag)
    }

    @Test
    fun nonZeroExitWithoutUidZeroIsDenied() {
        val empty = SuperuserRunner.parseSuOutput("", 1, timedOut = false, threwIoException = false)
        assertEquals(SuStatus.DENIED, empty.suStatus)
        assertNull(empty.idOutput)

        val message = SuperuserRunner.parseSuOutput("su: permission denied\n", 1, timedOut = false, threwIoException = false)
        assertEquals(SuStatus.DENIED, message.suStatus)
        assertEquals("su: permission denied", message.idOutput)
    }

    @Test
    fun grantedWithAllSectionsAndDiagPresent() {
        val stdout = sections(
            id = "uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0",
            getenforce = "Enforcing",
            diagLs = "crw-rw-rw- 1 root root 10, 60 2026-09-11 08:00 /dev/diag",
            kernel = "CFGOK\nCONFIG_DIAG_CHAR=y",
        )
        val raw = SuperuserRunner.parseSuOutput(stdout, 0, timedOut = false, threwIoException = false)

        assertEquals(SuStatus.GRANTED, raw.suStatus)
        assertTrue(raw.idOutput, raw.idOutput!!.contains("uid=0"))
        assertEquals("Enforcing", raw.getenforceOutput)
        assertTrue(raw.diagLsOutput, raw.diagLsOutput!!.startsWith("crw"))
        assertEquals(KernelConfigProbe.DIAG_PRESENT, raw.kernelConfigDiag)
    }

    @Test
    fun grantedWithDiagAbsentAndConfigReadButNoMatch() {
        val stdout = sections(
            id = "uid=0(root) gid=0(root)",
            getenforce = "Enforcing",
            diagLs = "ls: /dev/diag: No such file or directory",
            kernel = "CFGOK",
        )
        val raw = SuperuserRunner.parseSuOutput(stdout, 0, timedOut = false, threwIoException = false)

        assertEquals(SuStatus.GRANTED, raw.suStatus)
        assertTrue(raw.diagLsOutput, raw.diagLsOutput!!.contains("No such file"))
        assertEquals(KernelConfigProbe.DIAG_ABSENT, raw.kernelConfigDiag)
    }

    @Test
    fun grantedWithConfigUnavailable() {
        val stdout = sections(
            id = "uid=0(root)",
            getenforce = "Permissive",
            diagLs = "ls: /dev/diag: No such file or directory",
            kernel = "CFGNONE",
        )
        val raw = SuperuserRunner.parseSuOutput(stdout, 0, timedOut = false, threwIoException = false)

        assertEquals(SuStatus.GRANTED, raw.suStatus)
        assertEquals(KernelConfigProbe.CONFIG_UNAVAILABLE, raw.kernelConfigDiag)
    }

    @Test
    fun aMissingSectionIsLeftNull() {
        // An empty getenforce section (nothing between the first two markers) is null, not "".
        // The kernel section is a real grep hit — CFGOK then the matching config line — so DIAG_PRESENT holds.
        val stdout = listOf(
            "uid=0(root)",
            "---",
            "---",
            "crw-rw-rw- 1 root root 10, 60 /dev/diag",
            "---",
            "CFGOK",
            "CONFIG_DIAG_CHAR=y",
            "---END",
        ).joinToString("\n")
        val raw = SuperuserRunner.parseSuOutput(stdout, 0, timedOut = false, threwIoException = false)

        assertEquals(SuStatus.GRANTED, raw.suStatus)
        assertNull(raw.getenforceOutput)
        assertTrue(raw.diagLsOutput, raw.diagLsOutput!!.startsWith("crw"))
        assertEquals(KernelConfigProbe.DIAG_PRESENT, raw.kernelConfigDiag)
    }

    @Test
    fun crlfLineEndingsAreNormalised() {
        val stdout = "uid=0(root)\r\n---\r\nEnforcing\r\n---\r\nls: /dev/diag: No such file or directory\r\n---\r\nCFGOK\r\n---END\r\n"
        val raw = SuperuserRunner.parseSuOutput(stdout, 0, timedOut = false, threwIoException = false)

        assertEquals(SuStatus.GRANTED, raw.suStatus)
        assertEquals("Enforcing", raw.getenforceOutput)
        assertEquals(KernelConfigProbe.DIAG_ABSENT, raw.kernelConfigDiag)
    }

    private fun sections(id: String, getenforce: String, diagLs: String, kernel: String): String =
        listOf(id, "---", getenforce, "---", diagLs, "---", kernel, "---END").joinToString("\n")

    private fun partialGranted(): String = "uid=0(root)\n---\nEnforcing\n"
}
