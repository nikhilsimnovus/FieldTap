package com.fieldtap.core.session

import com.fieldtap.core.input.MeasurementInput
import com.fieldtap.core.location.LocationPipeline
import com.fieldtap.core.nettest.TrafficRecord
import com.fieldtap.core.privacy.ConsentRecord
import com.fieldtap.core.radio.RadioPipeline
import com.fieldtap.core.time.Clock
import com.fieldtap.format.CollectionMeta
import com.fieldtap.format.DeviceMeta
import com.fieldtap.format.EventRow
import com.fieldtap.format.HandsetMeta
import com.fieldtap.format.ServingRat
import com.fieldtap.format.SessionMeta
import com.fieldtap.format.TrafficTest
import com.fieldtap.format.TransportMeta
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

/** The fixed facts of a session, known at start. */
data class SessionIdentity(
    val sessionId: String,
    val name: String,
    val note: String?,
    val location: String?,
    val startedUtcMs: Long,
    val transport: TransportMeta,
    val handset: HandsetMeta,
    val device: DeviceMeta,
    val consent: ConsentRecord,
)

/**
 * Builds [SessionMeta] values.
 *
 * - [open]: `stopped_utc` null, `summary.stopped_by` [ExitReasons.RECORDING], plmns `{}`,
 *   [CollectionMeta.EMPTY], precision `full`, zone pauses 0, consent from the identity.
 * - [snapshot]: the same with the given live values.
 *
 * Owner: workstream `session-core`.
 */
object SessionMetaFactory {
    fun open(identity: SessionIdentity): SessionMeta = TODO("session-core")

    fun snapshot(
        identity: SessionIdentity,
        plmns: Map<String, Int>,
        collection: CollectionMeta,
        zonePauses: Int,
        stoppedUtcMs: Long?,
        stoppedBy: String,
    ): SessionMeta = TODO("session-core")
}

/**
 * Events owned by the session itself.
 *
 * - [marker]: rat `-`, `info`, title `Marker`, detail the note (blank if none).
 * - [testFailed]: rat `-`, `error`, title `Ping failed` or `Download failed`, detail the error.
 * - [sessionInterrupted]: rat `-`, `error`, title `Session interrupted`, detail
 *   `Android stopped the app` plus the exit description when known, cause the stop token.
 *
 * Owner: workstream `session-core`.
 */
object SessionEvents {
    fun marker(wallMs: Long, note: String?): EventRow = TODO("session-core")

    fun testFailed(wallMs: Long, test: TrafficTest, error: String): EventRow = TODO("session-core")

    fun sessionInterrupted(wallMs: Long, stoppedBy: String, description: String?): EventRow = TODO("session-core")
}

/** Everything the recorder is told, in one ordered stream. */
sealed interface RecorderCommand {
    data class Measurement(val input: MeasurementInput) : RecorderCommand

    data class Traffic(val record: TrafficRecord) : RecorderCommand

    data class Mark(val note: String?, val wallMs: Long) : RecorderCommand

    data class Stop(val cause: StopCause) : RecorderCommand

    /** Once a second, from the recorder's own ticker. */
    data object Tick : RecorderCommand
}

/** What the notification, the Live screen and the automation hook show about the running session. */
data class RecorderSnapshot(
    val dirName: String,
    val startedUtcMs: Long,
    val elapsedMs: Long,
    val servingRat: ServingRat?,
    val servingRsrpDbm: Int?,
    /** Age now of the newest primary serving sample, or null before the first. */
    val newestSampleAgeMs: Long?,
    val paused: Boolean,
    val freshSamples: Long,
    val repeatsDropped: Long,
    val eventsWritten: Int,
    val trackRows: Long,
    val hasRecentFix: Boolean,
    val stopping: Boolean,
)

data class RecorderConfig(
    val identity: SessionIdentity,
    val session: AllocatedSession,
    /** `android.os.Process.myPid()`, for the heartbeat. */
    val pid: Int,
    val writePolicy: WritePolicy = WritePolicy(),
)

/**
 * The session recorder: the only writer of a session, running on one dispatcher.
 *
 * Contract:
 * - [submit] is thread-safe and never blocks: commands go into an unbounded channel. After Stop has
 *   been processed, further commands are ignored.
 * - [run] is called once, on the dispatcher from [SessionDispatchers.newSessionDispatcher]. It creates
 *   the files ([SessionFiles.create] with [SessionMetaFactory.open]), starts a 1 s ticker that submits
 *   [RecorderCommand.Tick], processes commands in order until Stop, finalizes, and returns the outcome.
 *   Cancelling the coroutine finalizes with [StopCause.SERVICE_DESTROYED].
 * - Measurement(CellInfoAnswer): `radio.onCellInfo(answer, writing = !location.paused)`; rows go to a
 *   FIFO of pending rows; events are appended at once.
 * - Pending rows are released in order, on every command, while `location.join(...)` is Resolved for
 *   the head row; each released row gets its position, is appended, and each KPI row is reported with
 *   `radio.onKpiWritten`.
 * - Measurement(FixSample): `location.onFix`; its track row appended; its privacy_zone events always
 *   appended; its gps events appended only when not paused. On a pause change to "not paused",
 *   `radio.onResume` events are appended.
 * - Service, data and display snapshots: to radio with `writing = !paused`, events appended. Signal,
 *   GNSS, availability and listener reports: snapshot only.
 * - Traffic: row and failure event appended unless paused. Mark: [SessionEvents.marker] unless paused.
 * - Tick: `radio.onTick`, `location.onTick` (events unless paused), then [WriteSchedule] actions: flush,
 *   sync, snapshot (session.json + cells.csv from radio and location), heartbeat, storage check (stop
 *   with [StopCause.STORAGE_FULL] when [StorageStatus.mustStop]). [snapshot] is updated.
 * - Stop: release every pending row with `location.joinFinal`, write the final snapshot with
 *   `stopped_utc` = the wall clock now and `stopped_by` = the cause's token, close the files.
 *
 * Tests (fakes for [RadioPipeline], [LocationPipeline], [SessionFiles]; a ManualClock; virtual time):
 * row order preserved across the join delay; nothing written while paused except privacy_zone; flush
 * every 1 s, sync every 5 s, snapshot every 60 s and at stop; stop releases pending rows; storage stop;
 * commands after stop ignored.
 *
 * Owner: workstream `session-core`.
 */
class SessionRecorder(
    private val config: RecorderConfig,
    private val clock: Clock,
    private val files: SessionFiles,
    private val radio: RadioPipeline,
    private val location: LocationPipeline,
    private val storage: () -> StorageStatus,
) {
    val snapshot: StateFlow<RecorderSnapshot> get() = TODO("session-core")

    fun submit(command: RecorderCommand): Unit = TODO("session-core")

    suspend fun run(): SessionOutcome = TODO("session-core")
}

/** Owner: workstream `session-core`. */
object SessionDispatchers {
    /** A dispatcher that runs one task at a time on the IO pool: `Dispatchers.IO.limitedParallelism(1)`. */
    fun newSessionDispatcher(): CoroutineDispatcher = TODO("session-core")
}
