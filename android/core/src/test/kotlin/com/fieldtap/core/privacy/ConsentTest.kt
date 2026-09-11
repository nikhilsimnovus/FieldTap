package com.fieldtap.core.privacy

import java.math.BigInteger
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentTest {

    @Test
    fun theCurrentTextIsPinnedToItsVersion() {
        assertEquals("2026-09-10-draft", Consent.CURRENT.version)
        assertEquals(
            "the consent text changed: give it a new version, then update this hash",
            "f63cb16aa9e2ae42f6feac10f42b470d85967b6b5ecc563cf655a1b61d4a442e",
            Consent.CURRENT.sha256,
        )
    }

    @Test
    fun theHashIsOverTheExactUtf8Text() {
        val digest = MessageDigest.getInstance("SHA-256").digest(Consent.CURRENT.text.toByteArray(Charsets.UTF_8))
        assertEquals(BigInteger(1, digest).toString(16).padStart(64, '0'), Consent.CURRENT.sha256)
        assertTrue(Regex("[0-9a-f]{64}").matches(Consent.CURRENT.sha256))
    }

    @Test
    fun oneChangedCharacterIsADifferentHashButTheVersionIsNotHashed() {
        val current = Consent.CURRENT
        assertNotEquals(current.sha256, current.copy(text = current.text + " ").sha256)
        assertNotEquals(current.sha256, current.copy(text = current.text.replaceFirst("phone", "Phone")).sha256)
        assertEquals(current.sha256, current.copy(version = "another").sha256)
    }

    @Test
    fun isCurrentNeedsTheVersionAndTheHash() {
        val current = Consent.CURRENT
        assertTrue(Consent.isCurrent(ConsentRecord(current.version, current.sha256, grantedUtcMs = 0)))
        assertFalse(Consent.isCurrent(null))
        assertFalse(Consent.isCurrent(ConsentRecord("2026-09-01", current.sha256, grantedUtcMs = 0)))
        assertFalse(Consent.isCurrent(ConsentRecord(current.version, "0".repeat(64), grantedUtcMs = 0)))
        assertFalse(Consent.isCurrent(ConsentRecord(current.version, current.sha256.uppercase(), grantedUtcMs = 0)))
    }

    @Test
    fun aRecordIsForTheCurrentTextAtTheGrantTime() {
        val record = Consent.record(grantedUtcMs = 1_789_050_600_123L)
        assertEquals(ConsentRecord(Consent.CURRENT.version, Consent.CURRENT.sha256, 1_789_050_600_123L), record)
        assertTrue(Consent.isCurrent(record))
    }

    @Test
    fun theTextCoversWhatTheLeadDecided() {
        val text = Consent.CURRENT.text
        val required = listOf(
            "cells your phone reports", "GPS track", "tests", "model", "stay on this phone", "zip file",
            "phone or SIM identifiers", "IMEI", "IMSI", "ICCID", "phone number", "withdraw consent", "Settings",
            "New sessions then cannot start", "privacy zone",
        )
        for (phrase in required) assertTrue("missing: $phrase", text.contains(phrase))
        assertFalse("no signalling wording", Regex("(?i)handover|\\brrc\\b|signalling|decod").containsMatchIn(text))
        assertFalse("the draft note stays out of the UI", text.contains("draft", ignoreCase = true))
        assertFalse("the legal note stays out of the UI", text.contains("legal", ignoreCase = true))
        assertEquals("plain paragraphs", 6, text.split("\n\n").size)
        assertFalse(text.contains("  "))
        assertFalse(text.contains("\r"))
    }
}
