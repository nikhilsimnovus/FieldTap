package com.fieldtap.core.readiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessPolicyTest {

    private val ready = ReadinessFacts(
        manufacturer = "Google",
        preciseLocationGranted = true,
        locationEnabled = true,
        notificationsGranted = true,
        phonePermissionGranted = true,
        ignoringBatteryOptimisations = true,
        backgroundRestricted = false,
        standbyBucket = 10,
        simReady = true,
        wifiConnected = false,
        charging = false,
    )

    private fun evaluate(facts: ReadinessFacts): ReadinessReport = ReadinessPolicy.evaluate(facts, NOW)

    private fun item(facts: ReadinessFacts, check: ReadinessCheck): ReadinessItem =
        requireNotNull(evaluate(facts).item(check)) { "no item for $check" }

    @Test
    fun aReadyPhoneHasOneOkItemPerCheckInEnumOrder() {
        val report = evaluate(ready)

        assertEquals(NOW, report.checkedUtcMs)
        assertEquals("Google", report.manufacturer)
        assertEquals(ReadinessCheck.entries.toList(), report.items.map { it.check })
        assertTrue(report.items.all { it.level == ReadinessLevel.OK })
        assertTrue(report.canStart)
        assertTrue(report.blockers.isEmpty())
        assertTrue(report.advice.isEmpty())
        assertFalse(report.aggressiveOem)
    }

    @Test
    fun everyDetailIsOnePlainSentence() {
        val variants = listOf(
            ready,
            ready.copy(
                preciseLocationGranted = false,
                locationEnabled = false,
                notificationsGranted = false,
                phonePermissionGranted = false,
                ignoringBatteryOptimisations = false,
                backgroundRestricted = true,
                standbyBucket = 45,
                simReady = false,
                wifiConnected = true,
            ),
            ready.copy(standbyBucket = 40, wifiConnected = true, charging = true),
            ready.copy(standbyBucket = null),
        )
        for (facts in variants) {
            for (item in evaluate(facts).items) {
                assertTrue("blank detail for ${item.check}", item.detail.isNotBlank())
                assertTrue("detail must end with a full stop: ${item.detail}", item.detail.endsWith("."))
                assertEquals("one sentence only: ${item.detail}", 1, item.detail.count { it == '.' } - decimalPoints(item.detail))
                for (word in listOf("decode", "signalling", "handover", "RRC")) {
                    assertFalse("wording implies $word: ${item.detail}", item.detail.contains(word, ignoreCase = true))
                }
            }
        }
    }

    @Test
    fun noPreciseLocationBlocksAStart() {
        val report = evaluate(ready.copy(preciseLocationGranted = false))
        val item = requireNotNull(report.item(ReadinessCheck.PRECISE_LOCATION))

        assertEquals(ReadinessLevel.BLOCKER, item.level)
        assertEquals(SettingsTarget.APP_DETAILS, item.target)
        assertFalse(report.canStart)
        assertEquals(listOf(ReadinessCheck.PRECISE_LOCATION), report.blockers.map { it.check })
    }

    @Test
    fun locationOffBlocksAStart() {
        val report = evaluate(ready.copy(locationEnabled = false))
        val item = requireNotNull(report.item(ReadinessCheck.LOCATION_ENABLED))

        assertEquals(ReadinessLevel.BLOCKER, item.level)
        assertEquals(SettingsTarget.LOCATION_SOURCE, item.target)
        assertFalse(report.canStart)
    }

    @Test
    fun bothBlockersAreListedInEnumOrder() {
        val report = evaluate(ready.copy(preciseLocationGranted = false, locationEnabled = false))

        assertEquals(
            listOf(ReadinessCheck.PRECISE_LOCATION, ReadinessCheck.LOCATION_ENABLED),
            report.blockers.map { it.check },
        )
    }

    @Test
    fun refusedNotificationsAreAdviceThatStillAllowsAStart() {
        val report = evaluate(ready.copy(notificationsGranted = false))
        val item = requireNotNull(report.item(ReadinessCheck.NOTIFICATIONS))

        assertEquals(ReadinessLevel.ADVICE, item.level)
        assertEquals(SettingsTarget.APP_NOTIFICATIONS, item.target)
        assertTrue(item.detail.contains("Stop and Mark"))
        assertTrue(report.canStart)
        assertEquals(listOf(ReadinessCheck.NOTIFICATIONS), report.advice.map { it.check })
    }

    @Test
    fun thePhonePermissionIsOptionalAndNeverAdvice() {
        // Decisions 7 and 9: "Instant cell updates" are opt-in and the app works fully without them.
        val refused = item(ready.copy(phonePermissionGranted = false), ReadinessCheck.PHONE_PERMISSION)
        val granted = item(ready, ReadinessCheck.PHONE_PERMISSION)

        assertEquals(ReadinessLevel.OK, refused.level)
        assertEquals(ReadinessLevel.OK, granted.level)
        assertTrue(refused.detail.startsWith("Instant cell updates are off"))
        assertTrue(granted.detail.startsWith("Instant cell updates are on"))
        assertEquals(SettingsTarget.APP_DETAILS, refused.target)
        assertTrue(evaluate(ready.copy(phonePermissionGranted = false)).advice.isEmpty())
    }

    @Test
    fun batteryOptimisationOnIsAdviceLinkingToTheListScreen() {
        val item = item(ready.copy(ignoringBatteryOptimisations = false), ReadinessCheck.BATTERY_OPTIMISATION)

        assertEquals(ReadinessLevel.ADVICE, item.level)
        assertEquals(SettingsTarget.BATTERY_OPTIMISATION, item.target)
        assertEquals(ReadinessLevel.OK, item(ready, ReadinessCheck.BATTERY_OPTIMISATION).level)
    }

    @Test
    fun backgroundRestrictionIsAdvice() {
        val item = item(ready.copy(backgroundRestricted = true), ReadinessCheck.BACKGROUND_RESTRICTION)

        assertEquals(ReadinessLevel.ADVICE, item.level)
        assertEquals(SettingsTarget.APP_DETAILS, item.target)
        assertEquals(ReadinessLevel.OK, item(ready, ReadinessCheck.BACKGROUND_RESTRICTION).level)
    }

    @Test
    fun onlyRareAndRestrictedStandbyBucketsAreAdvice() {
        val levels = mapOf(
            5 to ReadinessLevel.OK,
            10 to ReadinessLevel.OK,
            20 to ReadinessLevel.OK,
            30 to ReadinessLevel.OK,
            39 to ReadinessLevel.OK,
            40 to ReadinessLevel.ADVICE,
            45 to ReadinessLevel.ADVICE,
            50 to ReadinessLevel.ADVICE,
        )
        for ((bucket, level) in levels) {
            val item = item(ready.copy(standbyBucket = bucket), ReadinessCheck.STANDBY_BUCKET)
            assertEquals("bucket $bucket", level, item.level)
            assertEquals(SettingsTarget.APP_DETAILS, item.target)
        }
        assertTrue(item(ready.copy(standbyBucket = 40), ReadinessCheck.STANDBY_BUCKET).detail.contains("rare"))
        assertTrue(item(ready.copy(standbyBucket = 45), ReadinessCheck.STANDBY_BUCKET).detail.contains("restricted"))
        assertTrue(item(ready.copy(standbyBucket = 10), ReadinessCheck.STANDBY_BUCKET).detail.contains("active"))
    }

    @Test
    fun anUnknownStandbyBucketIsOk() {
        val item = item(ready.copy(standbyBucket = null), ReadinessCheck.STANDBY_BUCKET)

        assertEquals(ReadinessLevel.OK, item.level)
        assertTrue(item.detail.contains("did not report"))
    }

    @Test
    fun noReadySimIsAdviceWithNoSettingsScreen() {
        val item = item(ready.copy(simReady = false), ReadinessCheck.SIM_PRESENT)

        assertEquals(ReadinessLevel.ADVICE, item.level)
        assertEquals(SettingsTarget.NONE, item.target)
        assertTrue(item.detail.contains("emergency"))
    }

    @Test
    fun wifiIsAdviceOnlyWhenConnectedOnBattery() {
        val onBattery = item(ready.copy(wifiConnected = true, charging = false), ReadinessCheck.WIFI_OFF)
        val charging = item(ready.copy(wifiConnected = true, charging = true), ReadinessCheck.WIFI_OFF)
        val off = item(ready.copy(wifiConnected = false, charging = false), ReadinessCheck.WIFI_OFF)
        val offCharging = item(ready.copy(wifiConnected = false, charging = true), ReadinessCheck.WIFI_OFF)

        assertEquals(ReadinessLevel.ADVICE, onBattery.level)
        assertTrue(onBattery.detail.contains("10 s"))
        assertEquals(ReadinessLevel.OK, charging.level)
        assertEquals(ReadinessLevel.OK, off.level)
        assertEquals(ReadinessLevel.OK, offCharging.level)
        for (item in listOf(onBattery, charging, off, offCharging)) assertEquals(SettingsTarget.WIFI, item.target)
    }

    @Test
    fun everyAdviceAtOnceKeepsTheStartPossible() {
        val report = evaluate(
            ready.copy(
                notificationsGranted = false,
                phonePermissionGranted = false,
                ignoringBatteryOptimisations = false,
                backgroundRestricted = true,
                standbyBucket = 45,
                simReady = false,
                wifiConnected = true,
                charging = false,
            ),
        )

        assertTrue(report.canStart)
        assertEquals(
            listOf(
                ReadinessCheck.NOTIFICATIONS,
                ReadinessCheck.BATTERY_OPTIMISATION,
                ReadinessCheck.BACKGROUND_RESTRICTION,
                ReadinessCheck.STANDBY_BUCKET,
                ReadinessCheck.SIM_PRESENT,
                ReadinessCheck.WIFI_OFF,
            ),
            report.advice.map { it.check },
        )
    }

    @Test
    fun aggressiveOemsMatchCaseInsensitivelyAndIgnoreSurroundingSpace() {
        for (name in listOf("OnePlus", "ONEPLUS", "oneplus", " OnePlus ", "OPPO", "oppo", "realme", "Realme")) {
            assertTrue(name, ReadinessPolicy.isAggressiveOem(name))
        }
        for (name in listOf("samsung", "Google", "Xiaomi", "", "OnePlus Technology", "one plus")) {
            assertFalse(name, ReadinessPolicy.isAggressiveOem(name))
        }
        assertTrue(evaluate(ready.copy(manufacturer = "OnePlus")).aggressiveOem)
    }

    @Test
    fun theCheckIsRequiredBeforeTheFirstSessionOnEveryPhone() {
        for (maker in listOf("Google", "samsung", "OnePlus", "OPPO", "realme")) {
            assertTrue(maker, ReadinessPolicy.requiredBeforeSession(maker, lastRunUtcMs = null, lastSessionStartedUtcMs = null))
            assertTrue(maker, ReadinessPolicy.requiredBeforeSession(maker, lastRunUtcMs = null, lastSessionStartedUtcMs = NOW))
        }
    }

    @Test
    fun otherMakersNeedItOnlyOnce() {
        for (maker in listOf("Google", "samsung", "Xiaomi")) {
            assertFalse(maker, ReadinessPolicy.requiredBeforeSession(maker, lastRunUtcMs = NOW - 1, lastSessionStartedUtcMs = null))
            assertFalse(maker, ReadinessPolicy.requiredBeforeSession(maker, lastRunUtcMs = NOW - 1, lastSessionStartedUtcMs = NOW))
            assertFalse(maker, ReadinessPolicy.requiredBeforeSession(maker, lastRunUtcMs = NOW + 1, lastSessionStartedUtcMs = NOW))
        }
    }

    @Test
    fun aggressiveOemsNeedItBeforeEverySession() {
        for (maker in listOf("OnePlus", "oneplus", "OPPO", "Realme")) {
            // Ran, no session since: not required.
            assertFalse(maker, ReadinessPolicy.requiredBeforeSession(maker, lastRunUtcMs = NOW, lastSessionStartedUtcMs = null))
            // A session started after the last check: required again.
            assertTrue(maker, ReadinessPolicy.requiredBeforeSession(maker, lastRunUtcMs = NOW - 1, lastSessionStartedUtcMs = NOW))
            // Checked again after that session started: not required.
            assertFalse(maker, ReadinessPolicy.requiredBeforeSession(maker, lastRunUtcMs = NOW + 1, lastSessionStartedUtcMs = NOW))
            // Checked in the same millisecond the session started: counts as run since.
            assertFalse(maker, ReadinessPolicy.requiredBeforeSession(maker, lastRunUtcMs = NOW, lastSessionStartedUtcMs = NOW))
        }
    }

    @Test
    fun itemLookupReturnsNullForAMissingCheck() {
        val report = ReadinessReport(checkedUtcMs = NOW, manufacturer = "Google", items = emptyList())

        assertNull(report.item(ReadinessCheck.SIM_PRESENT))
        assertTrue(report.canStart)
    }

    /** Full stops inside numbers such as "2.5" are not sentence ends. */
    private fun decimalPoints(text: String): Int = Regex("[0-9]\\.[0-9]").findAll(text).count()

    private companion object {
        const val NOW: Long = 1_789_050_600_000L
    }
}
