package com.fieldtap.core.radio

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.format.Rat

/**
 * The identity of a cell for de-duplication and change detection. Two cells describe the same
 * measurement when their keys are equal and their `timestampMs` are equal.
 *
 * Owner: workstream `radio-core`.
 */
data class CellKey(
    val rat: Rat,
    /** mcc + mnc, only when both are known. */
    val plmn: String?,
    val pci: Int?,
    val arfcn: Int?,
    val cellId: Long?,
) {
    companion object {
        fun of(cell: CellSnapshot): CellKey = TODO("radio-core")
    }
}

/** One cell of an answer after classification. */
data class ClassifiedCell(
    val cell: CellSnapshot,
    /** True when this [CellKey] with this `timestampMs` was already classified: a cached repeat. */
    val stale: Boolean,
    /** `answer.observedElapsedMs - cell.timestampMs`, clamped to >= 0. */
    val ageMs: Long,
    /** `answer.observedWallMs - ageMs`: when the modem measured, on the wall clock. */
    val measurementWallMs: Long,
)

/** An answer after classification, with its serving cells picked by [ServingCellSelector]. */
data class ClassifiedAnswer(
    val answer: CellInfoAnswer,
    /** Every cell, in the answer's order. */
    val cells: List<ClassifiedCell>,
    /** The primary serving cell, if any. */
    val primary: ClassifiedCell?,
    /** The NSA NR leg (secondary serving NR cell while the primary is LTE), if any. */
    val nsaSecondary: ClassifiedCell?,
    /** The primary is fresh; with no primary, any cell is fresh. */
    val fresh: Boolean,
    /** Not empty and not fresh: a cached repeat. */
    val repeat: Boolean,
)

/**
 * The freshness engine: decides which cells of a cell-info answer are new measurements, so a
 * cached repeat is never logged as a measurement.
 *
 * Contract:
 * - A cell is stale when a cell with an equal [CellKey] and an equal `timestampMs` was already
 *   classified by any earlier answer, request or push. The first sighting is fresh.
 * - Seen keys are remembered for at least [historyMs] of elapsedRealtime after their last sighting.
 * - A stale cell keeps the measurement time of the measurement it repeats: `time_epoch` and
 *   `timestamp_ms` stay, only `seen_utc` and `age_ms` move on.
 * - [classify] never throws for vendor oddities. Timestamps that never advance make every later
 *   answer a repeat, which is the honest result; negative ages clamp to 0.
 * - Calls come from one thread (the session dispatcher or the Live reducer's collector); the
 *   engine is not thread-safe.
 *
 * Tests: the golden fixture's cadence (fresh every 2 s, requests every 1 s -> fresh, repeat,
 * fresh, ...: 54 fresh and 66 repeats over 120 requests); a push answer repeating a request answer;
 * an NSA answer (primary LTE fresh, NR leg stale); an empty answer; history expiry.
 *
 * Owner: workstream `radio-core`.
 */
class FreshnessEngine(private val historyMs: Long = 120_000) {
    fun classify(answer: CellInfoAnswer): ClassifiedAnswer = TODO("radio-core")

    /** Forget every seen measurement. */
    fun reset(): Unit = TODO("radio-core")
}
