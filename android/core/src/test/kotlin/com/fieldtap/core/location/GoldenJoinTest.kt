package com.fieldtap.core.location

import com.fieldtap.core.input.FixSample
import com.fieldtap.format.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden kpi.csv and cellinfo.csv positions, reproduced from the golden 1 Hz track by the
 * production pipeline: `fieldtap.gps.tag_rows(rows, track, max_gap_s=5.0)` on the monotonic clock.
 */
class GoldenJoinTest {

    /** One measurement row: when the modem measured it, when the app received it, and its golden position. */
    private data class Row(
        val label: String,
        val measurementElapsedMs: Long,
        val arrivalElapsedMs: Long,
        val lat: String,
        val lon: String,
    )

    private fun kpiRows(): List<Row> {
        val kpi = Golden.table("kpi.csv")
        val time = kpi.column("time_epoch")
        val comment = kpi.column("comment")
        val lat = kpi.column("lat")
        val lon = kpi.column("lon")
        val age = Regex("age_ms=([0-9]+)")
        return kpi.rows.mapIndexed { i, row ->
            val measured = Golden.elapsedAt(Golden.epochMs(row[time]))
            val ageMs = age.find(row[comment])!!.groupValues[1].toLong()
            Row("kpi.csv row ${i + 1}", measured, measured + ageMs, row[lat], row[lon])
        }
    }

    private fun cellinfoRows(): List<Row> {
        val cellinfo = Golden.table("cellinfo.csv")
        val seen = cellinfo.column("seen_utc")
        val time = cellinfo.column("time_epoch")
        val timestamp = cellinfo.column("timestamp_ms")
        val lat = cellinfo.column("lat")
        val lon = cellinfo.column("lon")
        return cellinfo.rows.mapIndexed { i, row ->
            val measured = row[timestamp].toLong()
            assertEquals("timestamp_ms is time_epoch on the monotonic clock", Golden.elapsedAt(Golden.epochMs(row[time])), measured)
            Row("cellinfo.csv row ${i + 1}", measured, Golden.elapsedAt(Golden.utcMs(row[seen])), row[lat], row[lon])
        }
    }

    private fun assertPosition(row: Row, actual: LatLon?) {
        if (row.lat.isEmpty()) {
            assertEquals("${row.label}: blank position", null, actual)
        } else {
            assertEquals("${row.label} lat", row.lat, actual?.let { seven(it.lat) })
            assertEquals("${row.label} lon", row.lon, actual?.let { seven(it.lon) })
        }
    }

    @Test
    fun replayedInArrivalOrderEveryRowGetsItsGoldenPosition() {
        val fixes = Golden.trackFixes()
        val rows = kpiRows() + cellinfoRows()
        // Fixes first, so a fix and a row arriving at the same instant see the fix; none do in the fixture.
        val arrivals: List<Pair<Long, Any>> =
            (fixes.map { it.observedElapsedMs to it as Any } + rows.map { it.arrivalElapsedMs to it as Any })
                .sortedBy { it.first }

        val pipeline = DefaultLocationPipeline(zones = emptyList())
        val pending = ArrayDeque<Row>()
        val resolved = LinkedHashMap<Row, LatLon?>()
        var pendingAnswers = 0

        fun release(nowElapsedMs: Long) {
            while (pending.isNotEmpty()) {
                when (val result = pipeline.join(pending.first().measurementElapsedMs, nowElapsedMs)) {
                    JoinResult.Pending -> {
                        pendingAnswers++
                        return
                    }
                    is JoinResult.Resolved -> resolved[pending.removeFirst()] = result.position
                }
            }
        }

        for ((now, item) in arrivals) {
            when (item) {
                is FixSample -> assertTrue(pipeline.onFix(item).track != null)
                is Row -> pending.addLast(item)
            }
            release(now)
        }

        assertTrue("every row resolved without joinFinal", pending.isEmpty())
        assertTrue("rows waited for the next fix at least once", pendingAnswers > 0)
        assertEquals(rows.size, resolved.size)
        assertEquals("rows are released in arrival order", rows.sortedBy { it.arrivalElapsedMs }, resolved.keys.toList())
        for ((row, position) in resolved) assertPosition(row, position)
    }

    @Test
    fun joinFinalOverTheWholeTrackGivesTheGoldenPositions() {
        // The track spans 119 s; a buffer longer than the production 60 s keeps every fix for this offline check.
        val joiner = FixJoiner(retainMs = 600_000)
        for (fix in Golden.trackFixes()) joiner.add(fix)
        for (row in kpiRows() + cellinfoRows()) assertPosition(row, joiner.joinFinal(row.measurementElapsedMs))
    }

    @Test
    fun rowsAtPoint400TakeTheFixAtPoint000OfTheSameSecond() {
        val track = Golden.table("track.csv")
        val time = track.column("time_utc")
        val lat = track.column("lat")
        val lon = track.column("lon")
        val bySecond = track.rows.associate { row ->
            (Golden.utcMs(row[time]) - Golden.START_WALL_MS) / 1_000 to (row[lat] to row[lon])
        }
        val joiner = FixJoiner(retainMs = 600_000)
        for (fix in Golden.trackFixes()) joiner.add(fix)

        val rows = kpiRows()
        assertTrue(rows.isNotEmpty())
        for (row in rows) {
            val offsetMs = row.measurementElapsedMs - Golden.BOOT_MS_AT_START
            assertEquals("${row.label} is measured at .400", 400L, offsetMs % 1_000)
            val sameSecond = bySecond.getValue(offsetMs / 1_000)
            val joined = joiner.joinFinal(row.measurementElapsedMs)
            assertEquals(row.label, sameSecond, joined?.let { seven(it.lat) to seven(it.lon) })
            assertEquals("${row.label} in kpi.csv", sameSecond, row.lat to row.lon)
        }
    }
}
