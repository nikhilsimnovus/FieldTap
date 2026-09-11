package com.fieldtap.core.session

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.DataStateSnapshot
import com.fieldtap.core.input.DisplayInfoSnapshot
import com.fieldtap.core.input.FixSample
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.core.location.JoinResult
import com.fieldtap.core.location.LocationPipeline
import com.fieldtap.core.location.LocationStep
import com.fieldtap.core.radio.ClassifiedAnswer
import com.fieldtap.core.radio.KpiCandidate
import com.fieldtap.core.radio.RadioPipeline
import com.fieldtap.core.radio.RadioStep
import com.fieldtap.format.CellInfoRow
import com.fieldtap.format.CellRow
import com.fieldtap.format.CollectionMeta
import com.fieldtap.format.EventRow
import com.fieldtap.format.KpiRow
import com.fieldtap.format.LatLon
import com.fieldtap.format.SessionMeta
import com.fieldtap.format.TrackRow
import com.fieldtap.format.TrafficRow
import java.io.File
import java.io.IOException

/** One call on [FakeSessionFiles], in the order the recorder made it. */
internal sealed interface FileCall {
    data class Create(val meta: SessionMeta) : FileCall

    data class Kpi(val row: KpiRow) : FileCall

    data class CellInfo(val row: CellInfoRow) : FileCall

    data class Track(val row: TrackRow) : FileCall

    data class Event(val row: EventRow) : FileCall

    data class Traffic(val row: TrafficRow) : FileCall

    data class Snapshot(val meta: SessionMeta, val cells: List<CellRow>) : FileCall

    data object Flush : FileCall

    data object Sync : FileCall

    data class Heartbeat(val record: HeartbeatRecord) : FileCall

    data object Close : FileCall
}

/** Records every call; a call matching [failWhen] is recorded and then throws IOException. */
internal class FakeSessionFiles : SessionFiles {
    override val directory: File = File("fake-session")

    val calls: MutableList<FileCall> = mutableListOf()

    var failWhen: (FileCall) -> Boolean = { false }

    /** The calls that put rows or events into a CSV. */
    val rowsAndEvents: List<FileCall>
        get() = calls.filter {
            it is FileCall.Kpi || it is FileCall.CellInfo || it is FileCall.Track || it is FileCall.Event ||
                it is FileCall.Traffic
        }

    override fun create(meta: SessionMeta) = recordCall(FileCall.Create(meta))

    override fun appendKpi(row: KpiRow) = recordCall(FileCall.Kpi(row))

    override fun appendCellInfo(row: CellInfoRow) = recordCall(FileCall.CellInfo(row))

    override fun appendTrack(row: TrackRow) = recordCall(FileCall.Track(row))

    override fun appendEvent(row: EventRow) = recordCall(FileCall.Event(row))

    override fun appendTraffic(row: TrafficRow) = recordCall(FileCall.Traffic(row))

    override fun writeSnapshot(meta: SessionMeta, cells: List<CellRow>) = recordCall(FileCall.Snapshot(meta, cells))

    override fun flush() = recordCall(FileCall.Flush)

    override fun sync() = recordCall(FileCall.Sync)

    override fun writeHeartbeat(record: HeartbeatRecord) = recordCall(FileCall.Heartbeat(record))

    override fun close() = recordCall(FileCall.Close)

    private fun recordCall(call: FileCall) {
        calls += call
        if (failWhen(call)) throw IOException("injected failure on $call")
    }
}

/** A programmable [RadioPipeline]: [steps] are handed out one per answer, whatever `writing` says. */
internal class FakeRadioPipeline : RadioPipeline {
    val steps: ArrayDeque<RadioStep> = ArrayDeque()
    val cellInfoCalls: MutableList<Pair<CellInfoAnswer, Boolean>> = mutableListOf()
    val serviceCalls: MutableList<Boolean> = mutableListOf()
    val dataCalls: MutableList<Boolean> = mutableListOf()
    val displayCalls: MutableList<Boolean> = mutableListOf()
    val resumeCalls: MutableList<Pair<Long, Long>> = mutableListOf()
    val ticks: MutableList<Long> = mutableListOf()
    val kpiWritten: MutableList<KpiCandidate> = mutableListOf()

    var serviceEvents: List<EventRow> = emptyList()
    var dataEvents: List<EventRow> = emptyList()
    var displayEvents: List<EventRow> = emptyList()
    var resumeEvents: List<EventRow> = emptyList()
    var cellRows: List<CellRow> = emptyList()
    var plmnCounts: Map<String, Int> = emptyMap()
    var collectionMeta: CollectionMeta = CollectionMeta.EMPTY
    var latestAnswer: ClassifiedAnswer? = null

    /** Thrown by [onCellInfo] when set, to stand for a bug inside the pipeline. */
    var failure: RuntimeException? = null

    override fun onCellInfo(answer: CellInfoAnswer, writing: Boolean): RadioStep {
        failure?.let { throw it }
        cellInfoCalls += answer to writing
        return steps.removeFirstOrNull() ?: RadioStep.EMPTY
    }

    override fun onServiceState(state: ServiceStateSnapshot, writing: Boolean): List<EventRow> {
        serviceCalls += writing
        return serviceEvents
    }

    override fun onDataState(state: DataStateSnapshot, writing: Boolean): List<EventRow> {
        dataCalls += writing
        return dataEvents
    }

    override fun onDisplayInfo(info: DisplayInfoSnapshot, writing: Boolean): List<EventRow> {
        displayCalls += writing
        return displayEvents
    }

    override fun onTick(nowElapsedMs: Long) {
        ticks += nowElapsedMs
    }

    override fun onResume(nowWallMs: Long, nowElapsedMs: Long): List<EventRow> {
        resumeCalls += nowWallMs to nowElapsedMs
        return resumeEvents
    }

    override fun onKpiWritten(candidate: KpiCandidate) {
        kpiWritten += candidate
    }

    override fun cells(): List<CellRow> = cellRows

    override fun plmns(): Map<String, Int> = plmnCounts

    override fun collection(): CollectionMeta = collectionMeta

    override fun latest(): ClassifiedAnswer? = latestAnswer
}

/** A programmable [LocationPipeline]: a join is Resolved only for measurement times put in [resolved]. */
internal class FakeLocationPipeline : LocationPipeline {
    override var paused: Boolean = false
    override var zonePauses: Int = 0
    override var holding: Boolean = false
    override var pausedWithoutFix: Boolean = false
    var holdAge: Long? = null

    override fun holdAgeMs(nowElapsedMs: Long): Long? = holdAge

    val resolved: HashMap<Long, LatLon?> = HashMap()
    var joinFinalPosition: (Long) -> LatLon? = { null }
    val joinFinalCalls: MutableList<Long> = mutableListOf()
    var onFixStep: (FixSample) -> LocationStep = { LocationStep(track = null, events = emptyList(), pauseChanged = false) }
    val fixes: MutableList<FixSample> = mutableListOf()
    var tickEvents: List<EventRow> = emptyList()

    /** What [onTick] returns; by default [tickEvents]. A test replaces it to change the pause state on a tick. */
    var onTickStep: (Long, Long) -> List<EventRow> = { _, _ -> tickEvents }
    val tickCalls: MutableList<Pair<Long, Long>> = mutableListOf()
    var lastFixSample: FixSample? = null

    override fun onFix(fix: FixSample): LocationStep {
        fixes += fix
        return onFixStep(fix)
    }

    override fun onTick(nowWallMs: Long, nowElapsedMs: Long): List<EventRow> {
        tickCalls += nowWallMs to nowElapsedMs
        return onTickStep(nowWallMs, nowElapsedMs)
    }

    override fun join(measurementElapsedMs: Long, nowElapsedMs: Long): JoinResult =
        if (resolved.containsKey(measurementElapsedMs)) {
            JoinResult.Resolved(resolved[measurementElapsedMs])
        } else {
            JoinResult.Pending
        }

    override fun joinFinal(measurementElapsedMs: Long): LatLon? {
        joinFinalCalls += measurementElapsedMs
        return joinFinalPosition(measurementElapsedMs)
    }

    override fun lastFix(): FixSample? = lastFixSample
}
