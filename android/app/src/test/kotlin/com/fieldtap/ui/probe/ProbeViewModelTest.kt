@file:OptIn(ExperimentalCoroutinesApi::class)

package com.fieldtap.ui.probe

import com.fieldtap.core.probe.ProbeReport
import com.fieldtap.ui.setup.FakeAppGraph
import com.fieldtap.ui.setup.SetupMainDispatcherRule
import com.fieldtap.ui.setup.SetupSamples
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProbeViewModelTest {
    @get:Rule
    val mainRule = SetupMainDispatcherRule()

    @Test
    fun runListensForThirtySecondsShowsProgressThenTheReport() = runTest {
        val graph = FakeAppGraph()
        graph.fakeProbe.progressLines = listOf("Trying the restricted listeners", "Listening: 0 s of 30 s, 0 answers")
        val viewModel = ProbeViewModel(graph)

        viewModel.run()
        assertTrue(viewModel.state.value.running)
        advanceUntilIdle()
        assertEquals(listOf(30_000L), graph.fakeProbe.durations)
        assertEquals("Listening: 0 s of 30 s, 0 answers", viewModel.state.value.progress)
        assertTrue(viewModel.state.value.running)

        val report = SetupSamples.probeReport()
        graph.fakeProbe.result.complete(report)
        advanceUntilIdle()

        assertEquals(ProbeUiState(running = false, progress = null, report = report, exported = null), viewModel.state.value)
        assertNull(viewModel.problem.value)
    }

    @Test
    fun aRunRequestedWhileListeningIsIgnored() = runTest {
        val graph = FakeAppGraph()
        val viewModel = ProbeViewModel(graph)

        viewModel.run()
        advanceUntilIdle()
        viewModel.run()
        advanceUntilIdle()

        assertEquals(1, graph.fakeProbe.durations.size)
    }

    @Test
    fun aFailedRunSaysSoAndShowsNoReport() = runTest {
        val graph = FakeAppGraph()
        graph.fakeProbe.runFailure = IllegalStateException("telephony service gone")
        val viewModel = ProbeViewModel(graph)

        viewModel.run()
        advanceUntilIdle()

        assertEquals(ProbeProblem.RUN_FAILED, viewModel.problem.value)
        assertEquals(ProbeUiState(running = false, progress = null, report = null, exported = null), viewModel.state.value)
    }

    @Test
    fun stopCancelsTheRunWithoutABanner() = runTest {
        val graph = FakeAppGraph()
        val viewModel = ProbeViewModel(graph)
        viewModel.run()
        advanceUntilIdle()

        viewModel.stop()
        assertFalse(viewModel.state.value.running)
        assertNull(viewModel.problem.value)

        graph.fakeProbe.result.complete(SetupSamples.probeReport())
        advanceUntilIdle()
        assertNull(viewModel.state.value.report)
        assertFalse(viewModel.state.value.running)
    }

    @Test
    fun leavingTheScreenInterruptsTheRunAndSaysSo() = runTest {
        val graph = FakeAppGraph()
        val viewModel = ProbeViewModel(graph)
        viewModel.run()
        advanceUntilIdle()

        viewModel.interrupt()
        advanceUntilIdle()

        assertEquals(ProbeProblem.INTERRUPTED, viewModel.problem.value)
        assertFalse(viewModel.state.value.running)
        assertNull(viewModel.state.value.progress)
    }

    @Test
    fun interruptWithoutARunChangesNothing() = runTest {
        val graph = FakeAppGraph()
        val viewModel = ProbeViewModel(graph)
        viewModel.run()
        graph.fakeProbe.result.complete(SetupSamples.probeReport())
        advanceUntilIdle()
        val finished = viewModel.state.value

        viewModel.interrupt()

        assertNull(viewModel.problem.value)
        assertEquals(finished, viewModel.state.value)
    }

    @Test
    fun aCancelledRunCannotOverwriteTheNextRun() = runTest {
        val graph = FakeAppGraph()
        val viewModel = ProbeViewModel(graph)
        val first = graph.fakeProbe.result
        viewModel.run()
        advanceUntilIdle()
        viewModel.stop()

        val second = CompletableDeferred<ProbeReport>()
        graph.fakeProbe.result = second
        viewModel.run()
        advanceUntilIdle()
        first.complete(SetupSamples.probeReport(model = "old"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.running)
        assertNull(viewModel.state.value.report)

        second.complete(SetupSamples.probeReport(model = "new"))
        advanceUntilIdle()
        assertEquals("new", viewModel.state.value.report?.handset?.model)
        assertFalse(viewModel.state.value.running)
    }

    @Test
    fun aNewRunReplacesTheOldReport() = runTest {
        val graph = FakeAppGraph()
        val viewModel = ProbeViewModel(graph)
        viewModel.run()
        graph.fakeProbe.result.complete(SetupSamples.probeReport())
        advanceUntilIdle()

        graph.fakeProbe.result = CompletableDeferred()
        viewModel.run()

        assertEquals(ProbeUiState(running = true, progress = null, report = null, exported = null), viewModel.state.value)
    }

    @Test
    fun exportWritesTheReportAndAsksTheScreenToShareIt() = runTest {
        val graph = FakeAppGraph()
        val viewModel = finishedRun(graph)
        val report = viewModel.state.value.report!!
        val shared = collectShares(viewModel)

        viewModel.export()
        assertTrue(viewModel.exporting.value)
        advanceUntilIdle()

        assertEquals(graph.fakeProbe.exportFile, viewModel.state.value.exported)
        assertEquals(listOf(graph.fakeProbe.exportFile), shared)
        assertEquals(listOf(report), graph.fakeProbe.exported)
        assertFalse(viewModel.exporting.value)
        assertNull(viewModel.problem.value)
    }

    @Test
    fun aFailedExportSaysSoSharesNothingAndCanBeRetried() = runTest {
        val graph = FakeAppGraph()
        graph.fakeProbe.exportFailure = IOException("no space")
        val viewModel = finishedRun(graph)
        val shared = collectShares(viewModel)

        viewModel.export()
        advanceUntilIdle()
        assertEquals(ProbeProblem.EXPORT_FAILED, viewModel.problem.value)
        assertNull(viewModel.state.value.exported)
        assertTrue(shared.isEmpty())
        assertFalse(viewModel.exporting.value)

        graph.fakeProbe.exportFailure = null
        viewModel.export()
        advanceUntilIdle()
        assertNull(viewModel.problem.value)
        assertEquals(1, shared.size)
    }

    @Test
    fun exportNeedsAFinishedReport() = runTest {
        val graph = FakeAppGraph()
        val viewModel = ProbeViewModel(graph)

        viewModel.export()
        viewModel.run()
        viewModel.export()
        advanceUntilIdle()

        assertTrue(graph.fakeProbe.exported.isEmpty())
        assertFalse(viewModel.exporting.value)
    }

    private fun TestScope.finishedRun(graph: FakeAppGraph): ProbeViewModel {
        val viewModel = ProbeViewModel(graph)
        viewModel.run()
        graph.fakeProbe.result.complete(SetupSamples.probeReport())
        advanceUntilIdle()
        return viewModel
    }

    private fun TestScope.collectShares(viewModel: ProbeViewModel): MutableList<File> {
        val shared = mutableListOf<File>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.shareRequests.collect { shared += it } }
        return shared
    }
}
