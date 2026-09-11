package com.fieldtap.debug

import com.fieldtap.core.nettest.TestSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationResultTest {

    @Test
    fun aStartResultIsOneLineInContractKeyOrder() {
        val json = AutomationResult(action = "start", ok = true, dirName = "20260910-143000_e2e-walk", error = null).toJson()

        assertEquals("{\"action\": \"start\", \"ok\": true, \"dir_name\": \"20260910-143000_e2e-walk\", \"error\": null}", json)
    }

    @Test
    fun aFailedMarkHasANullDirectory() {
        val json = AutomationResult(action = "mark", ok = false, dirName = null, error = AutomationContract.ERROR_NOT_RECORDING).toJson()

        assertEquals("{\"action\": \"mark\", \"ok\": false, \"dir_name\": null, \"error\": \"not_recording\"}", json)
    }

    @Test
    fun textIsEscapedAndNeverBreaksTheLine() {
        val json = AutomationResult(action = "stop", ok = false, dirName = "a\"b\\c", error = "line one\nline two").toJson()

        assertEquals("{\"action\": \"stop\", \"ok\": false, \"dir_name\": \"a\\\"b\\\\c\", \"error\": \"line one\\nline two\"}", json)
        assertFalse(json.contains('\n'))
    }
}

class TestOverridesTest {

    @Test
    fun noOverridesKeepTheStoredSettings() {
        val stored = TestSettings()

        assertTrue(TestOverrides.NONE.isEmpty)
        assertEquals(stored, TestOverrides().applyTo(stored))
        assertNull(TestOverrides().problem())
    }

    @Test
    fun theEmulatorOverridesReplaceOnlyWhatIsGiven() {
        val overrides = TestOverrides(
            pingTarget = " 10.0.2.2 ",
            pingIntervalMs = 15_000,
            downloadUrl = "https://speed.cloudflare.com/__down?bytes=1000000",
            downloadCapBytes = 1_000_000,
        )

        val applied = overrides.applyTo(TestSettings())

        assertFalse(overrides.isEmpty)
        assertEquals("10.0.2.2", applied.pingTarget)
        assertEquals(15_000L, applied.pingIntervalMs)
        assertEquals(TestSettings().pingCount, applied.pingCount)
        assertEquals("https://speed.cloudflare.com/__down?bytes=1000000", applied.downloadUrl)
        assertEquals(1_000_000L, applied.downloadCapBytes)
        assertEquals(TestSettings().sessionBudgetBytes, applied.sessionBudgetBytes)
    }

    @Test
    fun anEmptyTargetTurnsThatTestOff() {
        val applied = TestOverrides(pingTarget = "", downloadUrl = "").applyTo(TestSettings())

        assertEquals("", applied.pingTarget)
        assertNull(applied.downloadUrl)
    }

    @Test
    fun outOfRangeValuesNameTheExtra() {
        assertEquals("invalid_ping_interval_ms", TestOverrides(pingIntervalMs = 0).problem())
        assertEquals("invalid_ping_count", TestOverrides(pingCount = -1).problem())
        assertEquals("invalid_download_interval_ms", TestOverrides(downloadIntervalMs = 0).problem())
        assertEquals("invalid_download_cap_bytes", TestOverrides(downloadCapBytes = 0).problem())
        assertEquals("invalid_session_budget_bytes", TestOverrides(sessionBudgetBytes = -1).problem())
        assertEquals("invalid_download_url", TestOverrides(downloadUrl = "http://example.com/file").problem())
        assertNull(TestOverrides(sessionBudgetBytes = 0, downloadUrl = "").problem())
    }
}

class AutomationExtrasTest {

    @Test
    fun booleansAreReadFromEitherFlag() {
        assertEquals(true, AutomationExtras.booleanOf(true))
        assertEquals(false, AutomationExtras.booleanOf(false))
        assertEquals(true, AutomationExtras.booleanOf(" TRUE "))
        assertEquals(false, AutomationExtras.booleanOf("false"))
        assertNull(AutomationExtras.booleanOf("yes"))
        assertNull(AutomationExtras.booleanOf(1))
        assertNull(AutomationExtras.booleanOf(null))
    }

    @Test
    fun wholeNumbersAreReadFromEitherFlag() {
        assertEquals(20_000L, AutomationExtras.longOf(20_000L))
        assertEquals(3L, AutomationExtras.longOf(3))
        assertEquals(42L, AutomationExtras.longOf(" 42 "))
        assertNull(AutomationExtras.longOf("4.2"))
        assertNull(AutomationExtras.longOf("abc"))
        assertNull(AutomationExtras.longOf(true))
        assertNull(AutomationExtras.longOf(null))
    }

    @Test
    fun theEmulatorStartCommandIsReadInFull() {
        val arguments = AutomationExtras.start(
            mapOf(
                "name" to "e2e-walk",
                "note" to "CI",
                "accept_consent" to true,
                "mark_ready" to "true",
                "tests" to false,
                "timeout_ms" to 30_000L,
                "ping_target" to "10.0.2.2",
                "ping_interval_ms" to 15_000,
                "ping_count" to "3",
                "download_url" to "https://speed.cloudflare.com/__down?bytes=1000000",
                "download_cap_bytes" to 1_000_000L,
            ),
        )

        assertEquals(
            StartArguments(
                name = "e2e-walk",
                note = "CI",
                location = null,
                tests = false,
                acceptConsent = true,
                markReady = true,
                timeoutMs = 30_000,
                overrides = TestOverrides(
                    pingTarget = "10.0.2.2",
                    pingIntervalMs = 15_000,
                    pingCount = 3,
                    downloadUrl = "https://speed.cloudflare.com/__down?bytes=1000000",
                    downloadCapBytes = 1_000_000,
                ),
                problem = null,
            ),
            arguments,
        )
    }

    @Test
    fun absentExtrasTakeTheDefaults() {
        val arguments = AutomationExtras.start(emptyMap())

        assertEquals("", arguments.name)
        assertFalse(arguments.tests || arguments.acceptConsent || arguments.markReady)
        assertEquals(AutomationContract.DEFAULT_TIMEOUT_MS, arguments.timeoutMs)
        assertTrue(arguments.overrides.isEmpty)
        assertNull(arguments.problem)
        assertEquals(StopArguments(AutomationContract.DEFAULT_TIMEOUT_MS, null), AutomationExtras.stop(emptyMap()))
        assertNull(AutomationExtras.note(emptyMap()))
    }

    @Test
    fun anUnreadableValueFailsTheActionNamingTheFirstSuchExtra() {
        val arguments = AutomationExtras.start(mapOf("name" to "walk", "accept_consent" to "yes", "timeout_ms" to "soon"))
        assertEquals("invalid_accept_consent", arguments.problem)

        assertEquals("invalid_ping_interval_ms", AutomationExtras.start(mapOf("ping_interval_ms" to "15s")).problem)
        assertEquals(StopArguments(AutomationContract.DEFAULT_TIMEOUT_MS, "invalid_timeout_ms"), AutomationExtras.stop(mapOf("timeout_ms" to "soon")))
        assertEquals(StopArguments(5_000, null), AutomationExtras.stop(mapOf("timeout_ms" to 5_000)))
    }

    @Test
    fun aPingCountBeyondIntIsClampedSoARangeCheckStillSeesItsSign() {
        assertEquals(Int.MAX_VALUE, AutomationExtras.start(mapOf("ping_count" to 10_000_000_000L)).overrides.pingCount)
        assertEquals("invalid_ping_count", AutomationExtras.start(mapOf("ping_count" to -10_000_000_000L)).overrides.problem())
    }

    @Test
    fun aNumberGivenForATextExtraIsUsedAsText() {
        assertEquals("42", AutomationExtras.start(mapOf("name" to 42)).name)
        assertEquals("corner", AutomationExtras.note(mapOf("note" to "corner")))
    }
}
