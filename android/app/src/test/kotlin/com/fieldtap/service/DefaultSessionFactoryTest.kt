package com.fieldtap.service

import com.fieldtap.app.AppInfo
import com.fieldtap.core.privacy.Consent
import com.fieldtap.core.session.RecorderCommand
import com.fieldtap.core.session.SessionPaths
import com.fieldtap.core.session.SessionStore
import com.fieldtap.core.session.StartRequest
import com.fieldtap.core.session.StopCause
import com.fieldtap.core.session.StoragePolicy
import com.fieldtap.core.settings.AppSettings
import com.fieldtap.core.time.ManualClock
import com.fieldtap.format.HandsetMeta
import com.fieldtap.format.SessionDirName
import com.fieldtap.format.SessionFile
import com.fieldtap.format.SessionJson
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The production factory on a temporary sessions root, with the real recorder and session files. */
class DefaultSessionFactoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val clock = ManualClock()

    @Test
    fun aPreparedSessionWritesTheRequestIdentityAndConsentIntoSessionJson() = runTest {
        val paths = paths()
        val settings = consented()
        val prepared = factory(paths, settings).prepare(StartRequest(name = "  Factory walk  ", note = "  north door  ", location = "   "))
        val directory = File(paths.root, prepared.dirName)

        assertEquals(SessionDirName.of(clock.wallMs, "Factory walk"), prepared.dirName)
        assertEquals(clock.wallMs, prepared.startedUtcMs)
        assertTrue(directory.isDirectory)
        assertEquals("nothing is written before the recorder runs", 0, directory.listFiles()?.size)
        assertEquals(settings.value.tests, prepared.tests)

        var created = 0
        prepared.recorder.submit(RecorderCommand.Stop(StopCause.USER))
        val outcome = prepared.recorder.run(onFilesCreated = { created++ })

        assertEquals(1, created)
        assertEquals(prepared.dirName, outcome.dirName)
        assertEquals("user", outcome.stoppedBy)
        assertEquals(outcome, prepared.recorder.outcome.value)

        val meta = SessionJson.decode(File(directory, SessionFile.SESSION_JSON.fileName).readText(Charsets.UTF_8))
        assertEquals("session-1", meta.sessionId)
        assertEquals("Factory walk", meta.name)
        assertEquals("north door", meta.note)
        assertNull("a blank place is left out", meta.location)
        assertEquals("app:install-1", meta.device.key)
        assertEquals("Pixel 9", meta.device.label)
        assertEquals("Google", meta.handset.manufacturer)
        assertEquals("1.2.3", meta.transport.appVersion)
        assertEquals(42L, meta.transport.versionCode)
        assertEquals(Consent.CURRENT.version, meta.privacy.consentVersion)
        assertEquals(Consent.CURRENT.sha256, meta.privacy.consentSha256)
        assertEquals("user", meta.summary.stoppedBy)
        assertNotNull(meta.stoppedUtcMs)
        for (file in SessionFile.BUNDLE) assertTrue(file.fileName, File(directory, file.fileName).isFile)
        assertFalse("the heartbeat is removed at close", paths.heartbeat(prepared.dirName).exists())

        prepared.discard()
        assertTrue("a directory with a session in it is never discarded", directory.isDirectory)
    }

    @Test
    fun withoutConsentNothingIsAllocated() = runTest {
        val paths = paths()
        val factory = factory(paths, FakeSettings(AppSettings(installId = "install-1", consent = null)))

        val error = runCatching { factory.prepare(StartRequest("walk")) }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        assertTrue(paths.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun aHandsetReaderThatFailsLeavesTheHandsetEmpty() = runTest {
        val paths = paths()
        val factory = factory(paths, consented(), handset = { throw SecurityException("refused") })
        val prepared = factory.prepare(StartRequest("walk"))

        prepared.recorder.submit(RecorderCommand.Stop(StopCause.USER))
        prepared.recorder.run(onFilesCreated = {})

        val meta = SessionJson.decode(File(paths.root, "${prepared.dirName}/${SessionFile.SESSION_JSON.fileName}").readText(Charsets.UTF_8))
        assertNull(meta.handset.manufacturer)
        assertNull(meta.handset.model)
        assertEquals("app:install-1", meta.device.key)
        assertNull(meta.device.label)
    }

    @Test
    fun filesThatCannotBeCreatedAreReportedAndWhatWasThereIsKept() = runTest {
        val paths = paths()
        val prepared = factory(paths, consented()).prepare(StartRequest("walk"))
        val directory = File(paths.root, prepared.dirName)
        val foreign = File(directory, SessionFile.KPI.fileName).apply { writeText("not ours") }

        var created = 0
        val error = runCatching { prepared.recorder.run(onFilesCreated = { created++ }) }.exceptionOrNull()

        assertTrue("expected an IOException, got $error", error is IOException)
        assertEquals(0, created)
        assertNull(prepared.recorder.outcome.value)
        prepared.discard()
        assertEquals("not ours", foreign.readText())
    }

    @Test
    fun anUnusedDirectoryIsDiscardedWithItsTemporaryFiles() = runTest {
        val paths = paths()
        val prepared = factory(paths, consented()).prepare(StartRequest("walk"))
        val directory = File(paths.root, prepared.dirName)
        File(directory, SessionFile.SESSION_JSON.fileName + ".tmp").writeText("{")

        prepared.discard()

        assertFalse(directory.exists())
        assertTrue("the sessions root stays", paths.root.isDirectory)
    }

    @Test
    fun discardIfUnusedNeverDeletesSessionFilesOrDirectories() {
        val withFiles = temp.newFolder("20260910-143000_walk")
        File(withFiles, SessionFile.EVENTS.fileName).writeText("time_utc\r\n")
        assertFalse(SessionDirectories.discardIfUnused(withFiles))
        assertTrue(File(withFiles, SessionFile.EVENTS.fileName).isFile)

        val withDirectory = temp.newFolder("20260910-143001_walk")
        File(withDirectory, "nested.tmp").mkdirs()
        assertFalse(SessionDirectories.discardIfUnused(withDirectory))
        assertTrue(withDirectory.isDirectory)

        val empty = temp.newFolder("20260910-143002_walk")
        assertTrue(SessionDirectories.discardIfUnused(empty))
        assertFalse(empty.exists())

        assertTrue("a directory that is already gone counts as discarded", SessionDirectories.discardIfUnused(File(temp.root, "missing")))
    }

    @Test
    fun theFilesCreatedSignalCallsItsListenerOnce() {
        val signal = FilesCreatedSignal()
        signal.fire()

        var calls = 0
        signal.listen { calls++ }
        signal.fire()
        signal.fire()

        assertEquals(1, calls)
    }

    private fun paths() = SessionPaths(root = File(temp.root, "sessions"), stateDir = File(temp.root, "session-state"))

    private fun consented() = FakeSettings(AppSettings(installId = "install-1", consent = Consent.record(clock.wallMs - 60_000)))

    private fun factory(
        paths: SessionPaths,
        settings: FakeSettings,
        handset: () -> HandsetMeta = { HandsetMeta(manufacturer = "Google", model = "Pixel 9") },
    ) = DefaultSessionFactory(
        paths = { paths },
        store = { SessionStore(paths, clock) },
        settings = settings,
        clock = clock,
        appInfo = { AppInfo(versionName = "1.2.3", versionCode = 42, applicationId = "com.fieldtap", debuggable = false) },
        handset = handset,
        pid = { 4242 },
        storagePolicy = StoragePolicy(),
        newSessionId = { "session-1" },
    )
}
