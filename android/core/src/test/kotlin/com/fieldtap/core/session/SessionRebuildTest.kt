package com.fieldtap.core.session

import com.fieldtap.core.location.Golden
import com.fieldtap.format.CellsCsv
import com.fieldtap.format.SessionJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The derived parts of a session rebuilt from its CSV files, called directly so that a failure is not hidden by recovery's fallback. */
class SessionRebuildTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun theGoldenSessionsRowsGiveItsPlmnsCellsAndCollection() {
        val golden = SessionJson.decode(Golden.text("session.json"))

        val derived = SessionRebuild.derive(Golden.dir, golden.startedUtcMs)

        assertEquals(mapOf("311480" to 54), derived.plmns)
        assertEquals(golden.summary.plmns, derived.plmns)
        assertEquals(Golden.text("cells.csv"), CellsCsv.headerLine + derived.cells.joinToString("") { CellsCsv.encode(it) })
        // session.json writes the shares with one decimal.
        val written = SessionJson.decode(SessionJson.encode(golden.copy(collection = derived.collection)))
        assertEquals(golden.collection, written.collection)
    }

    @Test
    fun aKpiRowWithoutItsCellInfoRowCountsTowardNothing() {
        val directory = temp.newFolder("session")
        Golden.file("kpi.csv").copyTo(File(directory, "kpi.csv"))
        Golden.file("events.csv").copyTo(File(directory, "events.csv"))

        val derived = SessionRebuild.derive(directory, Golden.START_WALL_MS)

        assertTrue(derived.plmns.isEmpty())
        assertTrue(derived.cells.isEmpty())
        assertEquals(0L, derived.collection.freshSamples)
        // The golden gap has no answer to time it, so it keeps the event's time and its detail's seconds.
        val gap = derived.collection.gaps.single()
        assertEquals(14_000L, gap.stopUtcMs - gap.startUtcMs)
        assertEquals("screen_off", gap.reason)
    }

    @Test
    fun aSessionWithNoRowsHasNothingDerived() {
        val directory = temp.newFolder("empty")

        val derived = SessionRebuild.derive(directory, Golden.START_WALL_MS)

        assertTrue(derived.plmns.isEmpty())
        assertTrue(derived.cells.isEmpty())
        assertEquals(0L, derived.collection.freshSamples)
        assertTrue(derived.collection.gaps.isEmpty())
    }
}
