@file:OptIn(ExperimentalCoroutinesApi::class)

package com.fieldtap.ui.onboarding

import com.fieldtap.core.privacy.Consent
import com.fieldtap.core.privacy.ConsentRecord
import com.fieldtap.ui.setup.FakeAppGraph
import com.fieldtap.ui.setup.SetupMainDispatcherRule
import com.fieldtap.ui.setup.SetupSamples
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingViewModelTest {
    @get:Rule
    val mainRule = SetupMainDispatcherRule()

    @Test
    fun acceptStoresTheCurrentVersionAndHashWithTheWallClock() = runTest {
        val graph = FakeAppGraph()
        graph.clock.wallMs = 1_789_050_612_345L
        val viewModel = OnboardingViewModel(graph)
        advanceUntilIdle()
        assertFalse(viewModel.consentCurrent.value)

        viewModel.accept()
        assertEquals(AcceptState.SAVING, viewModel.acceptState.value)
        advanceUntilIdle()

        val record = graph.fakeSettings.stored.value.consent
        assertEquals(ConsentRecord(version = Consent.CURRENT.version, sha256 = Consent.CURRENT.sha256, grantedUtcMs = 1_789_050_612_345L), record)
        assertEquals("2026-09-10-draft", record?.version)
        assertTrue(viewModel.consentCurrent.value)
        assertEquals(1_789_050_612_345L, viewModel.acceptedAtUtcMs.value)
        assertEquals(AcceptState.IDLE, viewModel.acceptState.value)
    }

    @Test
    fun theGrantTimeIsReadAtTheTapNotWhenTheWriteLands() = runTest {
        val graph = FakeAppGraph()
        val gate = CompletableDeferred<Unit>()
        graph.fakeSettings.gate = gate
        graph.clock.wallMs = 1_000_000L
        val viewModel = OnboardingViewModel(graph)
        advanceUntilIdle()

        viewModel.accept()
        runCurrent()
        graph.clock.advance(5_000)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1_000_000L, graph.fakeSettings.stored.value.consent?.grantedUtcMs)
    }

    @Test
    fun acceptedEmitsOnceAfterTheRecordIsStored() = runTest {
        val graph = FakeAppGraph()
        val gate = CompletableDeferred<Unit>()
        graph.fakeSettings.gate = gate
        val viewModel = OnboardingViewModel(graph)
        val events = collectAccepted(viewModel)
        advanceUntilIdle()

        viewModel.accept()
        runCurrent()
        assertEquals(0, events.size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, events.size)
    }

    @Test
    fun aSecondTapWhileSavingWritesOnce() = runTest {
        val graph = FakeAppGraph()
        val gate = CompletableDeferred<Unit>()
        graph.fakeSettings.gate = gate
        val viewModel = OnboardingViewModel(graph)
        val events = collectAccepted(viewModel)
        advanceUntilIdle()

        viewModel.accept()
        runCurrent()
        viewModel.accept()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, graph.fakeSettings.updates)
        assertEquals(1, events.size)
    }

    @Test
    fun acceptWhenAlreadyCurrentKeepsTheOriginalGrantTime() = runTest {
        val graph = FakeAppGraph(SetupSamples.settings(consent = Consent.record(grantedUtcMs = 1_000L)))
        val viewModel = OnboardingViewModel(graph)
        val events = collectAccepted(viewModel)
        advanceUntilIdle()
        assertTrue(viewModel.consentCurrent.value)
        assertEquals(1_000L, viewModel.acceptedAtUtcMs.value)

        viewModel.accept()
        advanceUntilIdle()

        assertEquals(0, graph.fakeSettings.updates)
        assertEquals(1_000L, graph.fakeSettings.stored.value.consent?.grantedUtcMs)
        assertEquals(1, events.size)
    }

    @Test
    fun aFailedWriteShowsFailedEmitsNothingAndCanBeRetried() = runTest {
        val graph = FakeAppGraph()
        graph.fakeSettings.failure = IOException("disk full")
        val viewModel = OnboardingViewModel(graph)
        val events = collectAccepted(viewModel)
        advanceUntilIdle()

        viewModel.accept()
        advanceUntilIdle()
        assertEquals(AcceptState.FAILED, viewModel.acceptState.value)
        assertEquals(0, events.size)
        assertNull(graph.fakeSettings.stored.value.consent)
        assertFalse(viewModel.consentCurrent.value)

        graph.fakeSettings.failure = null
        viewModel.accept()
        advanceUntilIdle()
        assertEquals(AcceptState.IDLE, viewModel.acceptState.value)
        assertEquals(1, events.size)
        assertTrue(Consent.isCurrent(graph.fakeSettings.stored.value.consent))
    }

    @Test
    fun anUnexpectedRuntimeFailureIsAlsoShownAsFailed() = runTest {
        val graph = FakeAppGraph()
        graph.fakeSettings.failure = IllegalStateException("store closed")
        val viewModel = OnboardingViewModel(graph)
        advanceUntilIdle()

        viewModel.accept()
        advanceUntilIdle()

        assertEquals(AcceptState.FAILED, viewModel.acceptState.value)
    }

    @Test
    fun consentCurrentFollowsTheStoredSettings() = runTest {
        val graph = FakeAppGraph()
        val viewModel = OnboardingViewModel(graph)
        advanceUntilIdle()
        assertFalse(viewModel.consentCurrent.value)

        graph.fakeSettings.stored.value = SetupSamples.settings(
            consent = ConsentRecord(version = Consent.CURRENT.version, sha256 = "0".repeat(64), grantedUtcMs = 5L),
        )
        advanceUntilIdle()
        assertFalse(viewModel.consentCurrent.value)
        assertNull(viewModel.acceptedAtUtcMs.value)

        graph.fakeSettings.stored.value = SetupSamples.settings(consent = Consent.record(grantedUtcMs = 7L))
        advanceUntilIdle()
        assertTrue(viewModel.consentCurrent.value)
        assertEquals(7L, viewModel.acceptedAtUtcMs.value)

        graph.fakeSettings.stored.value = SetupSamples.settings(consent = null)
        advanceUntilIdle()
        assertFalse(viewModel.consentCurrent.value)
    }

    @Test
    fun theDisclosureShowsTheCurrentConsentTextWordForWord() {
        val viewModel = OnboardingViewModel(FakeAppGraph())
        assertSame(Consent.CURRENT, viewModel.consentText)

        val paragraphs = consentParagraphs(Consent.CURRENT.text)
        assertEquals(6, paragraphs.size)
        assertEquals(Consent.CURRENT.text, paragraphs.joinToString("\n\n"))
    }

    private fun TestScope.collectAccepted(viewModel: OnboardingViewModel): MutableList<Unit> {
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.accepted.collect { events += it } }
        return events
    }
}
