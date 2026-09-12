package com.fieldtap.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityParsersTest {

    // ---- SelinuxParser ----

    @Test
    fun selinuxKnownModesCaseAndWhitespaceInsensitive() {
        assertEquals(SelinuxMode.ENFORCING, SelinuxParser.parse("Enforcing"))
        assertEquals(SelinuxMode.ENFORCING, SelinuxParser.parse("  enforcing\n"))
        assertEquals(SelinuxMode.PERMISSIVE, SelinuxParser.parse("Permissive"))
        assertEquals(SelinuxMode.DISABLED, SelinuxParser.parse("DISABLED"))
    }

    @Test
    fun selinuxEmptyGarbageAndNullAreUnknown() {
        assertEquals(SelinuxMode.UNKNOWN, SelinuxParser.parse(""))
        assertEquals(SelinuxMode.UNKNOWN, SelinuxParser.parse("   "))
        assertEquals(SelinuxMode.UNKNOWN, SelinuxParser.parse("getenforce: not found"))
        assertEquals(SelinuxMode.UNKNOWN, SelinuxParser.parse(null))
    }

    // ---- DiagParser ----

    @Test
    fun diagCharacterDevicePresent() {
        assertEquals(DiagDevice.PRESENT, DiagParser.device("crw-rw-rw- 1 root root 10, 60 2026-09-11 /dev/diag"))
        // SELinux label / ACL marker after the mode still starts with a char-device mode.
        assertEquals(DiagDevice.PRESENT, DiagParser.device("crw-rw----+ 1 system system 10, 60 /dev/diag"))
    }

    @Test
    fun diagNoSuchFileIsAbsent() {
        assertEquals(DiagDevice.ABSENT, DiagParser.device("ls: /dev/diag: No such file or directory"))
        assertEquals(DiagDevice.ABSENT, DiagParser.device("ls: cannot access '/dev/diag': No such file or directory"))
    }

    @Test
    fun diagPermissionDeniedIsPermissionDenied() {
        assertEquals(DiagDevice.PERMISSION_DENIED, DiagParser.device("ls: /dev/diag: Permission denied"))
        assertEquals(DiagDevice.PERMISSION_DENIED, DiagParser.device("Access denied"))
    }

    @Test
    fun diagBlockDeviceOrUnrecognisedOrNullIsUnknown() {
        assertEquals(DiagDevice.UNKNOWN, DiagParser.device("brw-rw---- 1 root root 7, 0 /dev/loop0"))
        assertEquals(DiagDevice.UNKNOWN, DiagParser.device("-rw-r--r-- 1 root root 0 /dev/diag"))
        assertEquals(DiagDevice.UNKNOWN, DiagParser.device("something unexpected"))
        assertEquals(DiagDevice.UNKNOWN, DiagParser.device(""))
        assertEquals(DiagDevice.UNKNOWN, DiagParser.device(null))
    }

    // ---- SuOutputParser ----

    @Test
    fun rootFromUidZeroOrWhoami() {
        assertTrue(SuOutputParser.isRoot("uid=0(root) gid=0(root) groups=0(root)"))
        assertTrue(SuOutputParser.isRoot("root"))
        assertTrue(SuOutputParser.isRoot("  root\n"))
    }

    @Test
    fun notRootForNonZeroUidOrNull() {
        assertFalse(SuOutputParser.isRoot("uid=1000(u0_a123) gid=1000(u0_a123)"))
        assertFalse(SuOutputParser.isRoot("shell"))
        assertFalse(SuOutputParser.isRoot(""))
        assertFalse(SuOutputParser.isRoot(null))
    }
}
