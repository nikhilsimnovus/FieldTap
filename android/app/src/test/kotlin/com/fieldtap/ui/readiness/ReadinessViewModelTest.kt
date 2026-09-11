@file:OptIn(ExperimentalCoroutinesApi::class)

package com.fieldtap.ui.readiness

import com.fieldtap.app.SessionStatus
import com.fieldtap.app.SoakState
import com.fieldtap.core.soak.SoakResult
import com.fieldtap.ui.setup.FakeAppGraph
import com.fieldtap.ui.setup.SetupMainDispatcherRule
import com.fieldtap.ui.setup.SetupSamples
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReadinessViewModelTest {
    @get:Rule
    val mainRule = SetupMainDispatcherRule()

    @Test
    fun checkShowsCheckingThenTheReport() = runTest {
        val graph = FakeAppGraph()
        val report = SetupSamples.readinessReport()
        graph.fakeReadiness.nextReport = { report }
        val viewModel = ReadinessViewModel(graph)
        assertNull(viewModel.state.value.report)
        assertFalse(viewModel.state.value.checking)

        viewModel.check()
        assertTrue(viewModel.state.value.checking)
        advanceUntilIdle()

        assertEquals(report, viewModel.state.value.report)
        assertFalse(viewModel.state.value.checking)
        assertFalse(viewModel.checkFailed.value)
        assertEquals(1, graph.fakeReadiness.calls)
    }

    @Test
    fun checksRequestedWhileOneRunsRunOnceMoreAfterIt() = runTest {
        val graph = FakeAppGraph()
        val gate = CompletableDeferred<Unit>()
        graph.fakeReadiness.gate = gate
        var reports = 0
        graph.fakeReadiness.nextReport = {
            reports++
            SetupSamples.readinessReport(SetupSamples.facts(manufacturer = "maker $reports"))
        }
        val viewModel = ReadinessViewModel(graph)

        viewModel.check()
        runCurrent()
        viewModel.check()
        viewModel.check()
        assertEquals(1, graph.fakeReadiness.calls)
        assertTrue(viewModel.state.value.checking)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, graph.fakeReadiness.calls)
        assertEquals("maker 2", viewModel.state.value.report?.manufacturer)
        assertFalse(viewModel.state.value.checking)
    }

    @Test
    fun aCheckAfterTheLastOneFinishedRunsAgain() = runTest {
        val graph = FakeAppGraph()
        val viewModel = ReadinessViewModel(graph)

        viewModel.check()
        advanceUntilIdle()
        viewModel.check()
        advanceUntilIdle()

        assertEquals(2, graph.fakeReadiness.calls)
    }

    @Test
    fun aFailedCheckKeepsThePreviousReportAndSaysSo() = runTest {
        val graph = FakeAppGraph()
        val viewModel = ReadinessViewModel(graph)
        viewModel.check()
        advanceUntilIdle()
        val first = viewModel.state.value.report

        graph.fakeReadiness.failure = IOException("binder died")
        viewModel.check()
        advanceUntilIdle()
        assertTrue(viewModel.checkFailed.value)
        assertEquals(first, viewModel.state.value.report)
        assertFalse(viewModel.state.value.checking)

        graph.fakeReadiness.failure = null
        viewModel.check()
        advanceUntilIdle()
        assertFalse(viewModel.checkFailed.value)
    }

    @Test
    fun aFailureBeforeAnyReportLeavesNoReport() = runTest {
        val graph = FakeAppGraph()
        graph.fakeReadiness.failure = SecurityException("not allowed")
        val viewModel = ReadinessViewModel(graph)

        viewModel.check()
        advanceUntilIdle()

        assertNull(viewModel.state.value.report)
        assertTrue(viewModel.checkFailed.value)
        assertFalse(viewModel.state.value.checking)
    }

    @Test
    fun startSoakStartsTheTestAndTheStateFollowsIt() = runTest {
        val graph = FakeAppGraph()
        val viewModel = ReadinessViewModel(graph)
        advanceUntilIdle()

        viewModel.startSoak()
        advanceUntilIdle()
        assertEquals(1, graph.fakeSoak.starts)
        assertEquals(SoakState.Running(elapsedMs = 0, durationMs = 600_000), viewModel.state.value.soak)
        assertNull(viewModel.soakNotice.value)

        graph.fakeSoak.state.value = SoakState.Running(elapsedMs = 300_000, durationMs = 600_000)
        advanceUntilIdle()
        assertEquals(SoakState.Running(elapsedMs = 300_000, durationMs = 600_000), viewModel.state.value.soak)

        val result = SoakResult(
            durationMs = 600_000,
            secondsElapsed = 600,
            secondsLogged = 598,
            answers = 60,
            freshAnswers = 58,
            screenOffAnswers = 59,
        )
        graph.fakeSoak.state.value = SoakState.Done(result)
        advanceUntilIdle()
        assertEquals(SoakState.Done(result), viewModel.state.value.soak)
        assertNull(viewModel.soakNotice.value)
    }

    @Test
    fun startingWhileATestRunsDoesNothing() = runTest {
        val graph = FakeAppGraph()
        val viewModel = ReadinessViewModel(graph)
        viewModel.startSoak()
        advanceUntilIdle()

        viewModel.startSoak()
        advanceUntilIdle()

        assertEquals(1, graph.fakeSoak.starts)
    }

    @Test
    fun cancelSoakStopsTheTestWithoutANotice() = runTest {
        val graph = FakeAppGraph()
        val viewModel = ReadinessViewModel(graph)
        viewModel.startSoak()
        advanceUntilIdle()

        viewModel.cancelSoak()
        advanceUntilIdle()

        assertEquals(1, graph.fakeSoak.cancels)
        assertEquals(SoakState.Idle, viewModel.state.value.soak)
        assertNull(viewModel.soakNotice.value)
    }

    @Test
    fun aStartAndroidRefusesSaysSo() = runTest {
        val graph = FakeAppGraph()
        graph.fakeSoak.refuse = true
        val viewModel = ReadinessViewModel(graph)

        viewModel.startSoak()
        assertEquals(SoakNotice.START_REFUSED, viewModel.soakNotice.value)
        advanceUntilIdle()

        assertEquals(1, graph.fakeSoak.starts)
        assertEquals(SoakState.Idle, viewModel.state.value.soak)
        assertEquals(SoakNotice.START_REFUSED, viewModel.soakNotice.value)
    }

    @Test
    fun aTestThatFallsBackToIdleOnItsOwnSaysAndroidRefused() = runTest {
        val graph = FakeAppGraph()
        val viewModel = ReadinessViewModel(graph)
        viewModel.startSoak()
        advanceUntilIdle()

        graph.fakeSoak.state.value = SoakState.Idle
        advanceUntilIdle()

        assertEquals(SoakNotice.START_REFUSED, viewModel.soakNotice.value)
    }

    @Test
    fun aNewStartClearsTheLastNotice() = runTest {
        val graph = FakeAppGraph()
        graph.fakeSoak.refuse = true
        val viewModel = ReadinessViewModel(graph)
        viewModel.startSoak()
        advanceUntilIdle()

        graph.fakeSoak.refuse = false
        viewModel.startSoak()
        advanceUntilIdle()

        assertNull(viewModel.soakNotice.value)
        assertTrue(viewModel.state.value.soak is SoakState.Running)
    }

    @Test
    fun theSoakTestDoesNotStartWhileASessionRecords() = runTest {
        val graph = FakeAppGraph()
        graph.fakeSessionControl.status.value = SetupSamples.recording()
        val viewModel = ReadinessViewModel(graph)
        advanceUntilIdle()
        assertTrue(viewModel.sessionRunning.value)

        viewModel.startSoak()
        assertEquals(0, graph.fakeSoak.starts)
        assertEquals(SoakNotice.SESSION_RUNNING, viewModel.soakNotice.value)

        graph.fakeSessionControl.status.value = SessionStatus.Idle
        advanceUntilIdle()
        assertFalse(viewModel.sessionRunning.value)

        viewModel.startSoak()
        advanceUntilIdle()
        assertEquals(1, graph.fakeSoak.starts)
        assertNull(viewModel.soakNotice.value)
    }

    @Test
    fun aTestAlreadyRunningShowsFromTheStart() = runTest {
        val graph = FakeAppGraph()
        graph.fakeSoak.state.value = SoakState.Running(elapsedMs = 120_000, durationMs = 600_000)

        val viewModel = ReadinessViewModel(graph)

        assertEquals(SoakState.Running(elapsedMs = 120_000, durationMs = 600_000), viewModel.state.value.soak)
    }
}
