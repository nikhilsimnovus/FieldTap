package com.fieldtap.ui.sessions

import com.fieldtap.app.SessionStatus
import com.fieldtap.core.export.ExportException
import com.fieldtap.core.session.StartRequest
import com.fieldtap.format.LocationPrecision
import com.fieldtap.ui.common.FakeAppGraph
import com.fieldtap.ui.common.MainDispatcherRule
import com.fieldtap.ui.common.TestData
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val graph = FakeAppGraph()
    private val dir = TestData.DIR

    @Test
    fun loadsTheDetailWhenCreated() = runTest(main.dispatcher) {
        val detail = TestData.detail()
        graph.sessions.details = mapOf(dir to detail)

        val viewModel = SessionDetailViewModel(graph, dir)
        runCurrent()

        assertEquals(SessionDetailUiState(detail = detail, export = ExportState.Idle, deleted = false), viewModel.state.value)
    }

    @Test
    fun aMissingSessionIsNotAnErrorButAFailedReadIs() = runTest(main.dispatcher) {
        val missing = SessionDetailViewModel(graph, dir)
        runCurrent()
        assertNull(missing.state.value.detail)
        assertFalse(missing.state.value.loading)
        assertFalse(missing.state.value.loadFailed)

        graph.sessions.detailFailure = IOException("unreadable")
        val failing = SessionDetailViewModel(graph, dir)
        runCurrent()
        assertTrue(failing.state.value.loadFailed)

        graph.sessions.detailFailure = null
        graph.sessions.details = mapOf(dir to TestData.detail())
        failing.reload()
        runCurrent()
        assertFalse(failing.state.value.loadFailed)
        assertEquals(TestData.detail(), failing.state.value.detail)
    }

    @Test
    fun exportGoesThroughBuildingToReady() = runTest(main.dispatcher) {
        graph.sessions.details = mapOf(dir to TestData.detail())
        val gate = CompletableDeferred<Unit>()
        graph.sessions.exportGate = gate
        val viewModel = SessionDetailViewModel(graph, dir)
        runCurrent()

        viewModel.export(LocationPrecision.APPROX_110M)
        runCurrent()
        assertEquals(ExportState.Building, viewModel.state.value.export)

        viewModel.export(LocationPrecision.NONE)
        runCurrent()
        assertEquals("one build at a time", listOf(dir to LocationPrecision.APPROX_110M), graph.sessions.exports)

        gate.complete(Unit)
        runCurrent()

        assertEquals(ExportState.Ready(TestData.exportResult(dir, LocationPrecision.APPROX_110M)), viewModel.state.value.export)
    }

    @Test
    fun exportFailuresCarryTheirReason() = runTest(main.dispatcher) {
        graph.sessions.details = mapOf(dir to TestData.detail())
        graph.sessions.exportFailure = ExportException(ExportException.Reason.TOO_LARGE, "The zip is larger than 52428800 bytes")
        val viewModel = SessionDetailViewModel(graph, dir)
        runCurrent()

        viewModel.export(LocationPrecision.FULL)
        runCurrent()
        assertEquals(ExportState.Failed(ExportFailure.TOO_LARGE), viewModel.state.value.export)

        graph.sessions.exportFailure = IllegalStateException("zip bug")
        viewModel.export(LocationPrecision.FULL)
        runCurrent()
        assertEquals(ExportState.Failed(ExportFailure.UNKNOWN), viewModel.state.value.export)
    }

    @Test
    fun theRunningSessionIsNeverExported() = runTest(main.dispatcher) {
        graph.sessions.details = mapOf(dir to TestData.detail(TestData.summary(recording = true, stoppedBy = "recording", stoppedUtcMs = null)))
        val bySummary = SessionDetailViewModel(graph, dir)
        runCurrent()

        bySummary.export(LocationPrecision.FULL)
        runCurrent()
        assertEquals(ExportState.Failed(ExportFailure.SESSION_OPEN), bySummary.state.value.export)

        graph.sessions.details = mapOf(dir to TestData.detail())
        graph.sessionControl.status.value = SessionStatus.Recording(TestData.snapshot(dirName = dir))
        val byStatus = SessionDetailViewModel(graph, dir)
        runCurrent()
        byStatus.export(LocationPrecision.FULL)
        runCurrent()
        assertEquals(ExportState.Failed(ExportFailure.SESSION_OPEN), byStatus.state.value.export)

        assertTrue(graph.sessions.exports.isEmpty())
    }

    @Test
    fun changingThePrecisionDropsAZipBuiltAtAnother() = runTest(main.dispatcher) {
        graph.sessions.details = mapOf(dir to TestData.detail())
        val viewModel = SessionDetailViewModel(graph, dir)
        runCurrent()
        viewModel.export(LocationPrecision.APPROX_110M)
        runCurrent()

        viewModel.selectPrecision(LocationPrecision.APPROX_110M)
        assertTrue(viewModel.state.value.export is ExportState.Ready)

        viewModel.selectPrecision(LocationPrecision.NONE)
        assertEquals(ExportState.Idle, viewModel.state.value.export)

        graph.sessions.exportFailure = IOException("disk full")
        viewModel.export(LocationPrecision.NONE)
        runCurrent()
        viewModel.selectPrecision(LocationPrecision.NONE)
        assertEquals("a failure is cleared by any new choice", ExportState.Idle, viewModel.state.value.export)
    }

    @Test
    fun deleteRefusesTheRunningSessionBeforeAskingTheRepository() = runTest(main.dispatcher) {
        graph.sessions.details = mapOf(dir to TestData.detail(TestData.summary(recording = true, stoppedBy = "recording", stoppedUtcMs = null)))
        val viewModel = SessionDetailViewModel(graph, dir)
        runCurrent()

        viewModel.delete()
        runCurrent()

        assertEquals(DeleteError.RECORDING, viewModel.state.value.deleteError)
        assertTrue(graph.sessions.deletes.isEmpty())
        assertFalse(viewModel.state.value.deleted)

        viewModel.dismissDeleteError()
        assertNull(viewModel.state.value.deleteError)

        graph.sessions.details = mapOf(dir to TestData.detail())
        graph.sessionControl.status.value = SessionStatus.Stopping(TestData.snapshot(dirName = dir))
        val stopping = SessionDetailViewModel(graph, dir)
        runCurrent()
        stopping.delete()
        assertEquals(DeleteError.RECORDING, stopping.state.value.deleteError)
        assertTrue(graph.sessions.deletes.isEmpty())
    }

    @Test
    fun aSuccessfulDeleteEndsTheScreenOnce() = runTest(main.dispatcher) {
        graph.sessions.details = mapOf(dir to TestData.detail())
        graph.sessionControl.status.value = SessionStatus.Recording(TestData.snapshot(dirName = "20260911-080000_Other"))
        val viewModel = SessionDetailViewModel(graph, dir)
        runCurrent()

        viewModel.delete()
        runCurrent()
        viewModel.delete()
        viewModel.export(LocationPrecision.FULL)
        runCurrent()

        assertTrue(viewModel.state.value.deleted)
        assertFalse(viewModel.state.value.deleting)
        assertEquals(listOf(dir), graph.sessions.deletes)
        assertTrue("nothing is exported after a delete", graph.sessions.exports.isEmpty())
    }

    @Test
    fun aRefusedDeleteIsReported() = runTest(main.dispatcher) {
        graph.sessions.details = mapOf(dir to TestData.detail())
        graph.sessions.deleteResult = false
        val viewModel = SessionDetailViewModel(graph, dir)
        runCurrent()

        viewModel.delete()
        runCurrent()

        assertEquals(DeleteError.FAILED, viewModel.state.value.deleteError)
        assertFalse(viewModel.state.value.deleted)
    }

    @Test
    fun noDeleteWhileAZipIsBuilding() = runTest(main.dispatcher) {
        graph.sessions.details = mapOf(dir to TestData.detail())
        graph.sessions.exportGate = CompletableDeferred()
        val viewModel = SessionDetailViewModel(graph, dir)
        runCurrent()
        viewModel.export(LocationPrecision.FULL)
        runCurrent()

        viewModel.delete()
        runCurrent()

        assertTrue(graph.sessions.deletes.isEmpty())
    }

    @Test
    fun theDetailReloadsWhenASessionStartsOrEnds() = runTest(main.dispatcher) {
        graph.sessions.details = mapOf(dir to TestData.detail(TestData.summary(recording = true, stoppedBy = "recording", stoppedUtcMs = null)))
        graph.sessionControl.status.value = SessionStatus.Recording(TestData.snapshot(dirName = dir))
        val viewModel = SessionDetailViewModel(graph, dir)
        runCurrent()
        assertTrue(viewModel.state.value.detail?.summary?.recording == true)

        graph.sessions.details = mapOf(dir to TestData.detail())
        graph.sessionControl.status.value = SessionStatus.Idle
        runCurrent()

        assertFalse(viewModel.state.value.detail?.summary?.recording == true)
        assertEquals(false, SessionsPresentation.isRunning(dir, viewModel.state.value.detail, SessionStatus.Starting(StartRequest("x"))))
    }
}
