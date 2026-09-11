package com.fieldtap.ui.readiness

import com.fieldtap.app.SoakState
import com.fieldtap.core.readiness.ReadinessCheck
import com.fieldtap.core.readiness.ReadinessLevel
import com.fieldtap.core.soak.SoakResult
import com.fieldtap.ui.setup.SetupSamples
import com.fieldtap.ui.theme.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessPresentationTest {
    /** Two blockers, four pieces of advice and three passed checks. */
    private val troubled = SetupSamples.readinessReport(
        SetupSamples.facts(
            precise = false,
            locationEnabled = false,
            notifications = false,
            ignoringBatteryOptimisations = false,
            simReady = false,
            wifiConnected = true,
        ),
    )

    @Test
    fun levelsMapToTones() {
        assertEquals(StatusTone.SUCCESS, ReadinessPresentation.tone(ReadinessLevel.OK))
        assertEquals(StatusTone.WARNING, ReadinessPresentation.tone(ReadinessLevel.ADVICE))
        assertEquals(StatusTone.ERROR, ReadinessPresentation.tone(ReadinessLevel.BLOCKER))
    }

    @Test
    fun blockersComeFirstThenAdviceThenPassedChecksEachInReportOrder() {
        assertEquals(
            listOf(
                ReadinessCheck.PRECISE_LOCATION,
                ReadinessCheck.LOCATION_ENABLED,
                ReadinessCheck.NOTIFICATIONS,
                ReadinessCheck.BATTERY_OPTIMISATION,
                ReadinessCheck.SIM_PRESENT,
                ReadinessCheck.WIFI_OFF,
                ReadinessCheck.PHONE_PERMISSION,
                ReadinessCheck.BACKGROUND_RESTRICTION,
                ReadinessCheck.STANDBY_BUCKET,
            ),
            ReadinessPresentation.ordered(troubled.items).map { it.check },
        )
    }

    @Test
    fun aFixShowsOnlyForAProblemASettingsScreenCanFix() {
        fun showsFix(check: ReadinessCheck): Boolean = ReadinessPresentation.showsFix(troubled.item(check)!!)
        assertTrue(showsFix(ReadinessCheck.PRECISE_LOCATION))
        assertTrue(showsFix(ReadinessCheck.LOCATION_ENABLED))
        assertTrue(showsFix(ReadinessCheck.BATTERY_OPTIMISATION))
        assertTrue(showsFix(ReadinessCheck.WIFI_OFF))
        assertFalse("no settings screen puts a SIM in", showsFix(ReadinessCheck.SIM_PRESENT))
        assertFalse("passed", showsFix(ReadinessCheck.PHONE_PERMISSION))
        assertFalse("passed", showsFix(ReadinessCheck.STANDBY_BUCKET))
    }

    @Test
    fun theSummaryPutsBlockersBeforeAdviceBeforeTheMaker() {
        assertEquals(ReadinessSummary.Blocked(2), ReadinessPresentation.summary(troubled))

        val advice = SetupSamples.readinessReport(SetupSamples.facts(manufacturer = "OnePlus", notifications = false, wifiConnected = true))
        assertEquals(ReadinessSummary.Advice(2), ReadinessPresentation.summary(advice))

        val oneplus = SetupSamples.readinessReport(SetupSamples.facts(manufacturer = " OnePlus "))
        assertEquals(ReadinessSummary.AggressiveOem("OnePlus"), ReadinessPresentation.summary(oneplus))

        assertEquals(ReadinessSummary.Ready, ReadinessPresentation.summary(SetupSamples.readinessReport()))
    }

    @Test
    fun summaryTonesSayWhetherASessionCanStart() {
        assertEquals(StatusTone.ERROR, ReadinessSummary.Blocked(1).tone)
        assertEquals(StatusTone.WARNING, ReadinessSummary.Advice(1).tone)
        assertEquals(StatusTone.WARNING, ReadinessSummary.AggressiveOem("realme").tone)
        assertEquals(StatusTone.SUCCESS, ReadinessSummary.Ready.tone)
    }

    @Test
    fun soakProgressIsTheShareOfTheDurationDone() {
        assertEquals(0f, ReadinessPresentation.soakFraction(SoakState.Running(elapsedMs = 0, durationMs = 600_000)), 0f)
        assertEquals(0.5f, ReadinessPresentation.soakFraction(SoakState.Running(elapsedMs = 300_000, durationMs = 600_000)), 1e-6f)
        assertEquals(1f, ReadinessPresentation.soakFraction(SoakState.Running(elapsedMs = 700_000, durationMs = 600_000)), 0f)
        assertEquals(0f, ReadinessPresentation.soakFraction(SoakState.Running(elapsedMs = -5, durationMs = 600_000)), 0f)
        assertEquals(0f, ReadinessPresentation.soakFraction(SoakState.Running(elapsedMs = 10, durationMs = 0)), 0f)
    }

    @Test
    fun soakVerdictsSplitAt95And80Percent() {
        assertEquals(SoakVerdict.GOOD, ReadinessPresentation.soakVerdict(soak(logged = 600)))
        assertEquals(SoakVerdict.GOOD, ReadinessPresentation.soakVerdict(soak(logged = 570)))
        assertEquals(SoakVerdict.PARTIAL, ReadinessPresentation.soakVerdict(soak(logged = 569)))
        assertEquals(SoakVerdict.PARTIAL, ReadinessPresentation.soakVerdict(soak(logged = 480)))
        assertEquals(SoakVerdict.POOR, ReadinessPresentation.soakVerdict(soak(logged = 479)))
        assertEquals(SoakVerdict.POOR, ReadinessPresentation.soakVerdict(soak(logged = 0)))
        assertEquals(SoakVerdict.TOO_SHORT, ReadinessPresentation.soakVerdict(soak(logged = 0, elapsed = 0)))
        assertEquals(StatusTone.SUCCESS, SoakVerdict.GOOD.tone)
        assertEquals(StatusTone.ERROR, SoakVerdict.POOR.tone)
    }

    @Test
    fun aRunWithoutScreenOffAnswersSaysNothingAboutScreenOff() {
        assertTrue(ReadinessPresentation.screenStayedOn(soak(logged = 600, answers = 60, screenOff = 0)))
        assertFalse(ReadinessPresentation.screenStayedOn(soak(logged = 600, answers = 60, screenOff = 1)))
        assertFalse(ReadinessPresentation.screenStayedOn(soak(logged = 0, answers = 0, screenOff = 0)))
    }

    private fun soak(logged: Long, elapsed: Long = 600, answers: Int = 60, screenOff: Int = 55): SoakResult = SoakResult(
        durationMs = 600_000,
        secondsElapsed = elapsed,
        secondsLogged = logged,
        answers = answers,
        freshAnswers = answers,
        screenOffAnswers = screenOff,
    )
}
