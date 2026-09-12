package com.fieldtap.platform.capability

import com.fieldtap.core.capability.KernelConfigProbe
import com.fieldtap.core.capability.SuStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- Deep sections (deep-root-spec §4) ----

    @Test
    fun deepSectionsAreParsedAfterTheV1Sections() {
        val stdout = listOf(
            "uid=0(root)", "---",
            "Enforcing", "---",
            "ls: /dev/diag: No such file or directory", "---",
            "CFGOK", "---",
            "5.10.101-android12-9", "---",
            "aarch64", "---",
            "Linux version 5.10.101-android12-9 (u@h) #1 SMP PREEMPT 2025", "---",
            "stat: '/dev/diag': No such file or directory", "---",
            "crw-rw---- 1 radio radio 10, 61 2026-09-11 /dev/diag_router", "---",
            "lo\nrmnet_data0\nqmux0\nwlan0", "---",
            "/system/bin/tcpdump", "---",
            "RLOGOK",
            "5",
            "---END",
        ).joinToString("\n")

        val raw = SuperuserRunner.parseSuOutput(stdout, 0, timedOut = false, threwIoException = false)

        assertEquals(SuStatus.GRANTED, raw.suStatus)
        assertEquals("5.10.101-android12-9", raw.unameOutput)
        assertEquals("aarch64", raw.unameMachineOutput)
        assertTrue(raw.procVersionOutput, raw.procVersionOutput!!.contains("Linux version"))
        assertTrue(raw.diagStatOutput, raw.diagStatOutput!!.contains("No such file"))
        assertTrue(raw.diagNodesLsOutput, raw.diagNodesLsOutput!!.contains("/dev/diag_router"))
        assertTrue(raw.netListOutput, raw.netListOutput!!.contains("rmnet_data0"))
        assertTrue(raw.tcpdumpWhichOutput, raw.tcpdumpWhichOutput!!.contains("tcpdump"))
        assertEquals(listOf("/system/bin/tcpdump"), raw.tcpdumpPathHits)
        assertTrue(raw.radioLogReadable)
        assertEquals(5, raw.radioLogLineCount)
    }

    @Test
    fun v1OnlyOutputLeavesTheDeepFieldsAtTheirDefaults() {
        val stdout = sections(
            id = "uid=0(root)",
            getenforce = "Enforcing",
            diagLs = "crw-rw-rw- 1 root root 10, 60 /dev/diag",
            kernel = "CFGOK\nCONFIG_DIAG_CHAR=y",
        )

        val raw = SuperuserRunner.parseSuOutput(stdout, 0, timedOut = false, threwIoException = false)

        assertEquals(SuStatus.GRANTED, raw.suStatus)
        assertNull(raw.unameOutput)
        assertNull(raw.unameMachineOutput)
        assertNull(raw.procVersionOutput)
        assertNull(raw.diagStatOutput)
        assertNull(raw.netListOutput)
        assertNull(raw.tcpdumpWhichOutput)
        assertEquals(emptyList<String>(), raw.tcpdumpPathHits)
        assertFalse(raw.radioLogReadable)
        assertNull(raw.radioLogLineCount)
    }

    @Test
    fun radioLogYieldsOnlyAFlagAndACountNoLineEverReachesAField() {
        // A sentinel "log line" placed in the radio-log section must never land in any parsed field: only
        // RLOGOK/RLOGNO and the integer count are read. In production `| wc -l` runs shell-side, so no line
        // even arrives — this proves the parser cannot capture one either (deep-root-spec §0.3, §7).
        val sentinel = "07-11 12:00:00.000 1234 5678 D RILJ: a TMSI paging record 0xdeadbeef"
        val stdout = listOf(
            "uid=0(root)", "---",
            "Enforcing", "---",
            "ls: /dev/diag: No such file or directory", "---",
            "CFGNONE", "---",
            "5.10.101", "---",
            "aarch64", "---",
            "Linux version 5.10.101 #1 SMP PREEMPT", "---",
            "stat: '/dev/diag': No such file or directory", "---",
            "", "---",
            "lo\nrmnet_data0", "---",
            "", "---",
            "RLOGOK",
            "3",
            sentinel,
            "---END",
        ).joinToString("\n")

        val raw = SuperuserRunner.parseSuOutput(stdout, 0, timedOut = false, threwIoException = false)

        assertTrue(raw.radioLogReadable)
        assertEquals(3, raw.radioLogLineCount)
        val stringFields = listOf(
            raw.idOutput, raw.getenforceOutput, raw.diagLsOutput, raw.unameOutput, raw.unameMachineOutput,
            raw.procVersionOutput, raw.diagStatOutput, raw.diagNodesLsOutput, raw.netListOutput, raw.tcpdumpWhichOutput,
        )
        for (field in stringFields) {
            assertFalse("no radio-log line may reach a field: $field", field?.contains("paging record") ?: false)
        }
        assertFalse(raw.tcpdumpPathHits.any { it.contains("paging") })
    }

    @Test
    fun timeoutAndNotPresentLeaveDeepFieldsDefaulted() {
        val timedOut = SuperuserRunner.parseSuOutput(partialGranted(), SuperuserRunner.EXIT_UNSET, timedOut = true, threwIoException = false)
        assertFalse(timedOut.radioLogReadable)
        assertNull(timedOut.unameOutput)
        assertEquals(emptyList<String>(), timedOut.tcpdumpPathHits)

        val notPresent = SuperuserRunner.parseSuOutput("", SuperuserRunner.EXIT_UNSET, timedOut = false, threwIoException = true)
        assertFalse(notPresent.radioLogReadable)
        assertNull(notPresent.procVersionOutput)
    }

    private fun sections(id: String, getenforce: String, diagLs: String, kernel: String): String =
        listOf(id, "---", getenforce, "---", diagLs, "---", kernel, "---END").joinToString("\n")

    private fun partialGranted(): String = "uid=0(root)\n---\nEnforcing\n"
}
