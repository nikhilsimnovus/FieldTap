package com.fieldtap.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelParserTest {

    // A realistic /proc/version with a build-host stamp, compiler group and date tail.
    private val procVersion =
        "Linux version 5.10.101-android12-9-00001-g0abc1234def-ab9876543 " +
            "(build-user@build-host-07) (Android (8508608, based on r450784e) clang version 14.0.7) " +
            "#1 SMP PREEMPT Wed Jan 1 12:00:00 UTC 2025"

    @Test
    fun parsesReleaseArchAndFlags() {
        val info = KernelParser.parse(
            uname = "5.10.101-android12-9-00001-g0abc1234def-ab9876543\n",
            machine = "aarch64\n",
            procVersion = procVersion,
        )

        assertEquals("5.10.101-android12-9-00001-g0abc1234def-ab9876543", info.release)
        assertEquals("aarch64", info.architecture)
        assertTrue(info.smp)
        assertTrue(info.preempt)
    }

    @Test
    fun emptyOrNullUnameIsNull() {
        val info = KernelParser.parse(uname = "  ", machine = null, procVersion = null)

        assertNull(info.release)
        assertNull(info.architecture)
        assertFalse(info.smp)
        assertFalse(info.preempt)
        assertNull(info.redactedVersion)
    }

    @Test
    fun preemptRtIsDetected() {
        val info = KernelParser.parse(null, null, "Linux version 6.1.0 #2 SMP PREEMPT_RT Thu Feb 2 2026")
        assertTrue(info.preempt)
        assertTrue(info.smp)
        assertEquals("Linux version 6.1.0 SMP PREEMPT_RT", info.redactedVersion)
    }

    @Test
    fun redactionKeepsOnlyTheHeaderAndFlags() {
        val redacted = KernelParser.redactProcVersion(procVersion)!!

        assertEquals("Linux version 5.10.101-android12-9-00001-g0abc1234def-ab9876543 SMP PREEMPT", redacted)
    }

    @Test
    fun redactionLeavesNoBuildStampNoDateAndNoBuildPath() {
        val redacted = KernelParser.redactProcVersion(
            "Linux version 5.15.78 (nobody@ci-runner-3) (gcc 11) #3 SMP PREEMPT " +
                "Mon Dec 4 09:15:22 PST 2025 /build/out/target",
        )!!

        assertFalse("no build-host stamp", redacted.contains("@"))
        assertFalse("no parenthesised compiler group", redacted.contains("(") || redacted.contains(")"))
        assertFalse("no build path", redacted.contains("/build"))
        assertFalse("no year/date tail", redacted.contains("2025"))
        assertFalse("no weekday", redacted.contains("Mon"))
        assertEquals("Linux version 5.15.78 SMP PREEMPT", redacted)
    }

    @Test
    fun redactionOfBlankIsNull() {
        assertNull(KernelParser.redactProcVersion(null))
        assertNull(KernelParser.redactProcVersion("   "))
    }
}
