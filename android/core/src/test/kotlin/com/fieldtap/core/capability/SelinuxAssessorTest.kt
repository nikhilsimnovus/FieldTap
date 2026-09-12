package com.fieldtap.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelinuxAssessorTest {

    @Test
    fun enforcingWithAnAbsentNodeBlocksTheAppDiagPath() {
        val a = SelinuxAssessor.assess(SelinuxMode.ENFORCING, DiagDevice.ABSENT)

        assertTrue(a.blocksAppDiagPath)
        assertTrue(a.consequence, a.consequence.contains("enforcing"))
    }

    @Test
    fun enforcingDoesNotFlipAProvenReadableNode() {
        // A PRESENT node means `ls` succeeded as root, so ENFORCING must NOT mark the path blocked
        // (matches the CapabilityVerdict.layer3 rule).
        val a = SelinuxAssessor.assess(SelinuxMode.ENFORCING, DiagDevice.PRESENT)

        assertFalse(a.blocksAppDiagPath)
        assertTrue(a.consequence, a.consequence.contains("readable as root"))
    }

    @Test
    fun permissiveAndDisabledNeverBlock() {
        for (mode in listOf(SelinuxMode.PERMISSIVE, SelinuxMode.DISABLED)) {
            for (diag in DiagDevice.entries) {
                val a = SelinuxAssessor.assess(mode, diag)
                assertFalse("$mode/$diag", a.blocksAppDiagPath)
            }
        }
    }

    @Test
    fun unknownModeDoesNotBlockAndReadsAsUnknown() {
        val a = SelinuxAssessor.assess(SelinuxMode.UNKNOWN, DiagDevice.UNKNOWN)

        assertFalse(a.blocksAppDiagPath)
        assertEquals(SelinuxMode.UNKNOWN, a.mode)
        assertTrue(a.consequence, a.consequence.contains("unknown"))
    }

    @Test
    fun enforcingWithADeniedOrUnknownNodeBlocks() {
        assertTrue(SelinuxAssessor.assess(SelinuxMode.ENFORCING, DiagDevice.PERMISSION_DENIED).blocksAppDiagPath)
        assertTrue(SelinuxAssessor.assess(SelinuxMode.ENFORCING, DiagDevice.UNKNOWN).blocksAppDiagPath)
    }
}
