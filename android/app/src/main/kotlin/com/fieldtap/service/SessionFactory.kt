package com.fieldtap.service

import com.fieldtap.app.AppInfo
import com.fieldtap.app.SettingsRepository
import com.fieldtap.core.location.DefaultLocationPipeline
import com.fieldtap.core.radio.DefaultRadioPipeline
import com.fieldtap.core.session.AtomicFiles
import com.fieldtap.core.session.FileSessionFiles
import com.fieldtap.core.session.RecorderCommand
import com.fieldtap.core.session.RecorderConfig
import com.fieldtap.core.session.RecorderSnapshot
import com.fieldtap.core.session.SessionDispatchers
import com.fieldtap.core.session.SessionFiles
import com.fieldtap.core.session.SessionIdentity
import com.fieldtap.core.session.SessionOutcome
import com.fieldtap.core.session.SessionPaths
import com.fieldtap.core.session.SessionRecorder
import com.fieldtap.core.session.SessionStore
import com.fieldtap.core.session.StartRequest
import com.fieldtap.core.session.StoragePolicy
import com.fieldtap.core.session.StorageStatus
import com.fieldtap.core.session.StorageUsage
import com.fieldtap.core.time.Clock
import com.fieldtap.format.DeviceMeta
import com.fieldtap.format.HandsetMeta
import com.fieldtap.format.Schema
import com.fieldtap.format.SessionMeta
import com.fieldtap.format.TransportMeta
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.StateFlow

/**
 * The production [SessionFactory]: allocates `<root>/<yyyyMMdd-HHmmss>_<slug>` through [SessionStore], builds
 * the [SessionIdentity] (a random session id, the trimmed name, note and place, app version, handset, the
 * install's `device.key` and the recorded consent), and a [SessionRecorder] over [FileSessionFiles],
 * [DefaultRadioPipeline] and [DefaultLocationPipeline] (the settings' privacy zones; mock fixes only in
 * debuggable builds), with a storage check against [storagePolicy], on a new session dispatcher.
 *
 * - `device.key` is `app:` plus the settings' install id, a random UUID; `device.label` is the handset model.
 *   No Android or SIM identifier is read. A handset reader that fails leaves the handset fields empty.
 * - The recorder's files report their creation to the runtime ([RecorderHandle.run]), and the prepared
 *   session's `discard` removes the directory only while it holds nothing but temporary files.
 * - When anything fails after the directory was allocated, the empty directory is removed before the failure
 *   propagates, so a failed start leaves nothing in the sessions list.
 *
 * No Android type is used here; the platform readers arrive as functions. Call it off the main thread.
 *
 * Owner: workstream `service-and-tests`.
 */
class DefaultSessionFactory(
    private val paths: () -> SessionPaths,
    private val store: () -> SessionStore,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val appInfo: () -> AppInfo,
    private val handset: () -> HandsetMeta,
    private val pid: () -> Int,
    private val storagePolicy: StoragePolicy,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
) : SessionFactory {
    override suspend fun prepare(request: StartRequest): PreparedSession {
        val current = settings.current()
        val consent = checkNotNull(current.consent) { "no consent recorded" }
        val info = appInfo()
        val handsetMeta = try {
            handset()
        } catch (e: RuntimeException) {
            HandsetMeta()
        }
        val sessionPaths = paths()
        val name = request.name.trim()
        val allocated = store().allocate(name)
        return try {
            val identity = SessionIdentity(
                sessionId = newSessionId(),
                name = name,
                note = request.note.cleanText(),
                location = request.location.cleanText(),
                startedUtcMs = allocated.startedUtcMs,
                transport = TransportMeta(appVersion = info.versionName, versionCode = info.versionCode),
                handset = handsetMeta,
                device = DeviceMeta(key = Schema.DEVICE_KEY_PREFIX + current.installId, label = handsetMeta.model),
                consent = consent,
            )
            val filesCreated = FilesCreatedSignal()
            val files = CreationReportingFiles(
                delegate = FileSessionFiles(allocated.directory, sessionPaths.heartbeat(allocated.dirName)),
                signal = filesCreated,
            )
            val recorder = SessionRecorder(
                config = RecorderConfig(identity = identity, session = allocated, pid = pid()),
                clock = clock,
                files = files,
                radio = DefaultRadioPipeline(),
                location = DefaultLocationPipeline(zones = current.zones, allowMockFixes = info.debuggable),
                storage = {
                    StorageStatus(
                        usedBytes = StorageUsage.usedBytes(sessionPaths.root),
                        freeBytes = StorageUsage.freeBytes(sessionPaths.root),
                        policy = storagePolicy,
                    )
                },
            )
            PreparedSession(
                dirName = allocated.dirName,
                startedUtcMs = allocated.startedUtcMs,
                recorder = SessionRecorderHandle(recorder, filesCreated),
                dispatcher = SessionDispatchers.newSessionDispatcher(),
                tests = current.tests,
                discard = { SessionDirectories.discardIfUnused(allocated.directory) },
            )
        } catch (e: Exception) {
            SessionDirectories.discardIfUnused(allocated.directory)
            throw e
        }
    }

    private fun String?.cleanText(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * [RecorderHandle] over the production [SessionRecorder]. [filesCreated] is the signal its [SessionFiles]
 * fire after a successful `create`.
 *
 * Owner: workstream `service-and-tests`.
 */
class SessionRecorderHandle internal constructor(
    private val recorder: SessionRecorder,
    private val filesCreated: FilesCreatedSignal,
) : RecorderHandle {
    override val snapshot: StateFlow<RecorderSnapshot> get() = recorder.snapshot

    override val outcome: StateFlow<SessionOutcome?> get() = recorder.outcome

    override fun submit(command: RecorderCommand) {
        recorder.submit(command)
    }

    override suspend fun run(onFilesCreated: () -> Unit): SessionOutcome {
        filesCreated.listen(onFilesCreated)
        return recorder.run()
    }
}

/**
 * Tells one listener, once, that a session's files exist.
 *
 * Owner: workstream `service-and-tests`.
 */
internal class FilesCreatedSignal {
    private val listener = AtomicReference<(() -> Unit)?>(null)

    /** Replaces the listener; it is called by the next [fire] only. */
    fun listen(block: () -> Unit) {
        listener.set(block)
    }

    /** Calls the listener, if one is set, and forgets it. */
    fun fire() {
        listener.getAndSet(null)?.invoke()
    }
}

/**
 * [SessionFiles] that fires [signal] after [delegate] created the files; every other call is [delegate]'s.
 *
 * Owner: workstream `service-and-tests`.
 */
internal class CreationReportingFiles(
    private val delegate: SessionFiles,
    private val signal: FilesCreatedSignal,
) : SessionFiles by delegate {
    override fun create(meta: SessionMeta) {
        delegate.create(meta)
        signal.fire()
    }
}

/**
 * Removes session directories that were allocated but never used.
 *
 * Owner: workstream `service-and-tests`.
 */
internal object SessionDirectories {
    /**
     * Deletes [directory] when it holds nothing but temporary files (`*.tmp`), which is all a failed
     * `SessionFiles.create` can leave behind. A directory with any other entry is left untouched, so session
     * files are never deleted here. Returns true when the directory is gone afterwards.
     */
    fun discardIfUnused(directory: File): Boolean {
        val entries = directory.listFiles() ?: return !directory.exists()
        if (entries.any { !it.isFile || !it.name.endsWith(AtomicFiles.TMP_SUFFIX) }) return false
        for (entry in entries) entry.delete()
        return directory.delete() || !directory.exists()
    }
}
