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
        /** The key of [cell]: its RAT, PLMN (only when mcc and mnc are both known), PCI, ARFCN and cell id. */
        fun of(cell: CellSnapshot): CellKey {
            val mcc = cell.mcc
            val mnc = cell.mnc
            return CellKey(
                rat = cell.rat,
                plmn = if (mcc != null && mnc != null) mcc + mnc else null,
                pci = cell.pci,
                arfcn = cell.arfcn,
                cellId = cell.cellId,
            )
        }
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
 *   classified by any earlier answer, request or push. The first sighting is fresh. Two equal cells
 *   inside one answer are both judged against the earlier answers only.
 * - Seen keys are remembered for at least [historyMs] of elapsedRealtime after their last sighting
 *   (each sighting renews the memory). As a guard against a vendor that floods new timestamps, at most
 *   65 536 measurements are remembered; the ones sighted longest ago are forgotten first.
 * - A stale cell keeps the measurement time of the measurement it repeats, as computed at its first
 *   sighting: `time_epoch` and `timestamp_ms` stay, only `seen_utc` and `age_ms` move on. The wall clock and
 *   elapsedRealtime are read separately at each answer, so recomputing it would move a repeat by a millisecond
 *   (or by a wall-clock step), and the repeat could no longer be matched to its fresh row.
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
    /** Each measurement seen, with its last sighting and its measurement time; least recently sighted first. */
    private val sightings = LinkedHashMap<Sighting, Seen>()

    init {
        require(historyMs >= 0) { "historyMs must not be negative, was $historyMs" }
    }

    fun classify(answer: CellInfoAnswer): ClassifiedAnswer {
        val nowMs = answer.observedElapsedMs
        expire(nowMs)
        val keys = answer.cells.map { Sighting(CellKey.of(it), it.timestampMs) }
        val cells = answer.cells.mapIndexed { index, cell ->
            val ageMs = ageOf(nowMs, cell.timestampMs)
            val earlier = sightings[keys[index]]
            ClassifiedCell(
                cell = cell,
                stale = earlier != null,
                ageMs = ageMs,
                measurementWallMs = earlier?.measurementWallMs ?: (answer.observedWallMs - ageMs),
            )
        }
        for ((index, key) in keys.withIndex()) {
            // Re-inserting moves the sighting to the end, so the map stays ordered by last sighting.
            val earlier = sightings.remove(key)
            sightings[key] = Seen(lastSightingMs = nowMs, measurementWallMs = earlier?.measurementWallMs ?: cells[index].measurementWallMs)
        }
        trimToCapacity()

        val serving = ServingCellSelector.selectIndices(answer.cells)
        val primary = serving.primary?.let { cells[it] }
        val nsaSecondary = serving.nsaSecondary?.let { cells[it] }
        val fresh = if (primary != null) !primary.stale else cells.any { !it.stale }
        return ClassifiedAnswer(
            answer = answer,
            cells = cells,
            primary = primary,
            nsaSecondary = nsaSecondary,
            fresh = fresh,
            repeat = cells.isNotEmpty() && !fresh,
        )
    }

    /** Forget every seen measurement. */
    fun reset() {
        sightings.clear()
    }

    private fun expire(nowMs: Long) {
        val cutoffMs = nowMs - historyMs
        val iterator = sightings.values.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().lastSightingMs < cutoffMs) iterator.remove() else break
        }
    }

    private fun trimToCapacity() {
        val iterator = sightings.keys.iterator()
        while (sightings.size > MAX_SIGHTINGS && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private data class Sighting(val key: CellKey, val timestampMs: Long)

    /** The elapsedRealtime of a measurement's last sighting, and its wall-clock measurement time from the first. */
    private class Seen(val lastSightingMs: Long, val measurementWallMs: Long)
}

private const val MAX_SIGHTINGS: Int = 65_536

/** `nowMs - timestampMs`, never negative, saturating instead of overflowing on a nonsense timestamp. */
private fun ageOf(nowMs: Long, timestampMs: Long): Long {
    if (timestampMs >= nowMs) return 0L
    val age = nowMs - timestampMs
    return if (age < 0L) Long.MAX_VALUE else age
}

/**
 * The cell whose measurement time stands for a fresh answer in gap detection and interval statistics:
 * the primary serving cell, or with no primary the newest fresh cell. Null when the answer is not fresh.
 */
internal fun ClassifiedAnswer.freshReference(): ClassifiedCell? {
    if (!fresh) return null
    primary?.let { return it }
    return cells.filter { !it.stale }.maxByOrNull { it.cell.timestampMs }
}

/**
 * This answer without the cells measured before [fromElapsedMs] (their `timestampMs` is earlier), or null when
 * every cell was: nothing of it may be written. The serving cells are kept only when they were measured in time
 * (the NSA leg only with its primary), and `fresh` and `repeat` are judged again on what is left. An answer
 * with no cells at all is returned as it is.
 */
internal fun ClassifiedAnswer.measuredFrom(fromElapsedMs: Long): ClassifiedAnswer? {
    if (cells.none { it.cell.timestampMs < fromElapsedMs }) return this
    val kept = cells.filter { it.cell.timestampMs >= fromElapsedMs }
    if (kept.isEmpty()) return null
    val keptPrimary = primary?.takeIf { it.cell.timestampMs >= fromElapsedMs }
    val keptLeg = if (keptPrimary == null) null else nsaSecondary?.takeIf { it.cell.timestampMs >= fromElapsedMs }
    val keptFresh = if (keptPrimary != null) !keptPrimary.stale else kept.any { !it.stale }
    return copy(cells = kept, primary = keptPrimary, nsaSecondary = keptLeg, fresh = keptFresh, repeat = !keptFresh)
}
