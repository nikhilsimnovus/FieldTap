package com.fieldtap.ui.live

import com.fieldtap.core.readiness.ReadinessCheck
import com.fieldtap.core.readiness.ReadinessItem
import com.fieldtap.core.readiness.ReadinessLevel
import com.fieldtap.core.readiness.ReadinessReport
import com.fieldtap.core.readiness.SettingsTarget
import com.fieldtap.core.session.StartRefusal
import com.fieldtap.core.session.StartRequest
import com.fieldtap.ui.common.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrestartPlannerTest {

    @Test
    fun nothingFoundMeansNoIssues() {
        assertEquals(emptyList<PrestartIssue>(), PrestartPlanner.issues(TestData.readiness(), consentCurrent = true, storageCanStart = true, refusal = null))
        assertEquals(
            "unknown consent and storage are left to the state machine",
            emptyList<PrestartIssue>(),
            PrestartPlanner.issues(TestData.readiness(), consentCurrent = null, storageCanStart = null, refusal = null),
        )
    }

    @Test
    fun blockingProblemsComeFirstThenAdviceInCheckOrder() {
        val report = report(
            ReadinessItem(ReadinessCheck.PRECISE_LOCATION, ReadinessLevel.BLOCKER, "", SettingsTarget.APP_DETAILS),
            ReadinessItem(ReadinessCheck.NOTIFICATIONS, ReadinessLevel.ADVICE, "", SettingsTarget.APP_NOTIFICATIONS),
            ReadinessItem(ReadinessCheck.WIFI_OFF, ReadinessLevel.ADVICE, "", SettingsTarget.WIFI),
        )

        val issues = PrestartPlanner.issues(report, consentCurrent = false, storageCanStart = false, refusal = null)

        assertEquals(
            listOf(
                PrestartIssue(PrestartIssueKind.NO_CONSENT, blocking = true),
                PrestartIssue(PrestartIssueKind.NO_PRECISE_LOCATION, blocking = true, target = SettingsTarget.APP_DETAILS),
                PrestartIssue(PrestartIssueKind.STORAGE_FULL, blocking = true),
                PrestartIssue(PrestartIssueKind.NOTIFICATIONS_OFF, blocking = false, target = SettingsTarget.APP_NOTIFICATIONS),
                PrestartIssue(PrestartIssueKind.WIFI_ON_BATTERY, blocking = false, target = SettingsTarget.WIFI),
            ),
            issues,
        )
    }

    @Test
    fun aRefusalAddsItsProblemOnceAndMakesItBlocking() {
        val report = report(ReadinessItem(ReadinessCheck.LOCATION_ENABLED, ReadinessLevel.ADVICE, "", SettingsTarget.LOCATION_SOURCE))

        val issues = PrestartPlanner.issues(report, consentCurrent = true, storageCanStart = true, refusal = StartRefusal.LOCATION_OFF)

        assertEquals(listOf(PrestartIssue(PrestartIssueKind.LOCATION_OFF, blocking = true, target = SettingsTarget.LOCATION_SOURCE)), issues)
    }

    @Test
    fun refusalsTheSheetCannotFixAddNothing() {
        for (refusal in listOf(StartRefusal.BLANK_NAME, StartRefusal.SESSION_RUNNING)) {
            assertEquals(emptyList<PrestartIssue>(), PrestartPlanner.issues(TestData.readiness(), true, true, refusal))
            assertNull(PrestartPlanner.kindOf(refusal))
        }
        assertEquals(PrestartIssueKind.NO_CONSENT, PrestartPlanner.kindOf(StartRefusal.NO_CONSENT))
        assertEquals(PrestartIssueKind.NO_PRECISE_LOCATION, PrestartPlanner.kindOf(StartRefusal.NO_PRECISE_LOCATION))
        assertEquals(PrestartIssueKind.LOCATION_OFF, PrestartPlanner.kindOf(StartRefusal.LOCATION_OFF))
        assertEquals(PrestartIssueKind.STORAGE_FULL, PrestartPlanner.kindOf(StartRefusal.STORAGE_FULL))
    }

    @Test
    fun everyReadinessCheckButThePhonePermissionHasAProblem() {
        val kinds = ReadinessCheck.entries.associateWith { PrestartPlanner.kindOf(it) }
        assertNull(kinds[ReadinessCheck.PHONE_PERMISSION])
        assertEquals(ReadinessCheck.entries.size - 1, kinds.values.filterNotNull().toSet().size)

        val advice = TestData.readiness(ReadinessCheck.PHONE_PERMISSION to ReadinessLevel.ADVICE)
        assertEquals(emptyList<PrestartIssue>(), PrestartPlanner.issues(advice, true, true, null))
    }

    @Test
    fun aMakerKnownForKillingAppsIsAdviceAfterTheChecks() {
        val report = TestData.readiness(ReadinessCheck.BATTERY_OPTIMISATION to ReadinessLevel.ADVICE, manufacturer = " OnePlus ")

        val issues = PrestartPlanner.issues(report, true, true, null)

        assertEquals(listOf(PrestartIssueKind.BATTERY_OPTIMISATION, PrestartIssueKind.AGGRESSIVE_OEM), issues.map { it.kind })
        assertTrue(issues.none { it.blocking })
    }

    @Test
    fun aCheckThatCouldNotRunIsAdvice() {
        val issues = PrestartPlanner.issues(report = null, consentCurrent = true, storageCanStart = true, refusal = null, checkFailed = true)

        assertEquals(listOf(PrestartIssue(PrestartIssueKind.CHECK_FAILED, blocking = false)), issues)
    }

    @Test
    fun theSettingsTargetFallsBackToTheUsualScreen() {
        assertEquals(SettingsTarget.WIFI, PrestartPlanner.settingsTarget(PrestartIssue(PrestartIssueKind.WIFI_ON_BATTERY, false)))
        assertEquals(SettingsTarget.LOCATION_SOURCE, PrestartPlanner.settingsTarget(PrestartIssue(PrestartIssueKind.LOCATION_OFF, true)))
        assertEquals(SettingsTarget.APP_NOTIFICATIONS, PrestartPlanner.settingsTarget(PrestartIssue(PrestartIssueKind.NOTIFICATIONS_OFF, false)))
        assertEquals(SettingsTarget.BATTERY_OPTIMISATION, PrestartPlanner.settingsTarget(PrestartIssue(PrestartIssueKind.BATTERY_OPTIMISATION, false)))
        assertEquals(SettingsTarget.APP_DETAILS, PrestartPlanner.settingsTarget(PrestartIssue(PrestartIssueKind.STANDBY_BUCKET, false)))
        assertEquals(SettingsTarget.NONE, PrestartPlanner.settingsTarget(PrestartIssue(PrestartIssueKind.NO_SIM, false)))
        assertEquals(
            "the screen the check named wins",
            SettingsTarget.APP_DETAILS,
            PrestartPlanner.settingsTarget(PrestartIssue(PrestartIssueKind.WIFI_ON_BATTERY, false, SettingsTarget.APP_DETAILS)),
        )
    }

    @Test
    fun startAnywayOnlyWhenNothingBlocks() {
        val request = StartRequest("Walk")
        assertTrue(PrestartState.Review(request, emptyList()).canStartAnyway)
        assertTrue(PrestartState.Review(request, listOf(PrestartIssue(PrestartIssueKind.NO_SIM, false))).canStartAnyway)
        assertFalse(PrestartState.Review(request, listOf(PrestartIssue(PrestartIssueKind.NO_SIM, false), PrestartIssue(PrestartIssueKind.STORAGE_FULL, true))).canStartAnyway)
    }

    private fun report(vararg nonOk: ReadinessItem): ReadinessReport {
        val byCheck = nonOk.associateBy { it.check }
        return ReadinessReport(
            checkedUtcMs = TestData.STARTED_UTC_MS,
            manufacturer = "Google",
            items = ReadinessCheck.entries.map { byCheck[it] ?: ReadinessItem(it, ReadinessLevel.OK, "", SettingsTarget.NONE) },
        )
    }
}
