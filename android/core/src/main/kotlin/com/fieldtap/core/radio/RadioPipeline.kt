package com.fieldtap.core.radio

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.DataStateSnapshot
import com.fieldtap.core.input.DisplayInfoSnapshot
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.format.CellRow
import com.fieldtap.format.CollectionMeta
import com.fieldtap.format.EventRow

/** What one answer produced for the files. Positions are not joined yet. */
data class RadioStep(
    val cellInfo: List<CellInfoCandidate>,
    val kpi: List<KpiCandidate>,
    /** Radio events and a sampling_gap event, ready to write, in order. */
    val events: List<EventRow>,
) {
    companion object {
        val EMPTY: RadioStep = RadioStep(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Everything radio the session recorder needs, behind one interface so the recorder can be tested
 * with a fake. Single-threaded: called only on the session dispatcher.
 *
 * `writing` is false while logging is paused inside a privacy zone. Paused, a call updates only what
 * must stay current (freshness de-duplication, the latest service, data and display snapshots, the gap
 * detector's service view) and returns nothing: no rows, no events, no statistics.
 *
 * [onResume] is called at the moment logging resumes: it resets the gap detector and feeds the latest
 * service, data and display snapshots, re-stamped with the resume time, to the event deriver, so the
 * files learn about any change that happened inside the zone without learning when.
 *
 * [onKpiWritten] is called by the recorder for each kpi.csv row it actually wrote (after the GPS join),
 * and feeds [ServingCellTable].
 *
 * Owner: workstream `radio-core`.
 */
interface RadioPipeline {
    fun onCellInfo(answer: CellInfoAnswer, writing: Boolean): RadioStep

    fun onServiceState(state: ServiceStateSnapshot, writing: Boolean): List<EventRow>

    fun onDataState(state: DataStateSnapshot, writing: Boolean): List<EventRow>

    fun onDisplayInfo(info: DisplayInfoSnapshot, writing: Boolean): List<EventRow>

    fun onTick(nowElapsedMs: Long)

    fun onResume(nowWallMs: Long, nowElapsedMs: Long): List<EventRow>

    fun onKpiWritten(candidate: KpiCandidate)

    /** cells.csv as it should be written now. */
    fun cells(): List<CellRow>

    /** `summary.plmns` now. */
    fun plmns(): Map<String, Int>

    /** `collection` now. */
    fun collection(): CollectionMeta

    /** The newest classified answer, paused or not; for the notification. */
    fun latest(): ClassifiedAnswer?
}

/**
 * The production [RadioPipeline]: [FreshnessEngine] -> [RadioRows] -> [RadioEventDeriver],
 * [SamplingGapDetector], [CollectionStats], [ServingCellTable].
 *
 * Tests: feeding the golden session's 120 answers (see tests/fixtures/make_android_session.py for
 * the inputs) yields its kpi rows, cellinfo rows (without positions), serving_cell events, gap and
 * collection values; a pause in the middle suppresses rows and stats, and resume re-emits changed state.
 *
 * Owner: workstream `radio-core`.
 */
class DefaultRadioPipeline(
    private val freshness: FreshnessEngine = FreshnessEngine(),
    private val deriver: RadioEventDeriver = RadioEventDeriver(),
    private val gaps: SamplingGapDetector = SamplingGapDetector(),
    private val stats: CollectionStats = CollectionStats(),
    private val cellTable: ServingCellTable = ServingCellTable(),
) : RadioPipeline {
    override fun onCellInfo(answer: CellInfoAnswer, writing: Boolean): RadioStep = TODO("radio-core")

    override fun onServiceState(state: ServiceStateSnapshot, writing: Boolean): List<EventRow> = TODO("radio-core")

    override fun onDataState(state: DataStateSnapshot, writing: Boolean): List<EventRow> = TODO("radio-core")

    override fun onDisplayInfo(info: DisplayInfoSnapshot, writing: Boolean): List<EventRow> = TODO("radio-core")

    override fun onTick(nowElapsedMs: Long): Unit = TODO("radio-core")

    override fun onResume(nowWallMs: Long, nowElapsedMs: Long): List<EventRow> = TODO("radio-core")

    override fun onKpiWritten(candidate: KpiCandidate): Unit = TODO("radio-core")

    override fun cells(): List<CellRow> = TODO("radio-core")

    override fun plmns(): Map<String, Int> = TODO("radio-core")

    override fun collection(): CollectionMeta = TODO("radio-core")

    override fun latest(): ClassifiedAnswer? = TODO("radio-core")
}
