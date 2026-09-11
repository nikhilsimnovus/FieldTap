package com.fieldtap.core.session

import com.fieldtap.core.location.Golden
import com.fieldtap.core.privacy.PrivacyZoneGate
import com.fieldtap.format.CellsCsv
import com.fieldtap.format.Csv
import com.fieldtap.format.EventKind
import com.fieldtap.format.EventRat
import com.fieldtap.format.EventRow
import com.fieldtap.format.EventsCsv
import com.fieldtap.format.SessionFormat
import com.fieldtap.format.SessionJson
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
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
        assertEquals(0, derived.zonePauseEvents)
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
        assertEquals(null, derived.newestRowUtcMs)
    }

    @Test
    fun zonePauseEventsAndTheNewestTimeAnyRowCarriesAreRead() {
        val directory = temp.newFolder("paused")
        for (name in listOf("kpi.csv", "cellinfo.csv", "track.csv", "traffic.csv", "events.csv")) Golden.file(name).copyTo(File(directory, name))
        val golden = SessionJson.decode(Golden.text("session.json"))
        val before = SessionRebuild.derive(directory, golden.startedUtcMs)
        val newest = checkNotNull(before.newestRowUtcMs)
        assertTrue("the newest row is from the golden session's last minute", newest > golden.startedUtcMs + 60_000)

        val paused = EventRow(newest + 30_000, EventRat.NONE, EventKind.PRIVACY_ZONE, com.fieldtap.format.Severity.INFO, PrivacyZoneGate.PAUSED_TITLE, null)
        File(directory, "events.csv").appendText(EventsCsv.encode(paused))
        val after = SessionRebuild.derive(directory, golden.startedUtcMs)

        assertEquals(1, after.zonePauseEvents)
        assertEquals(newest + 30_000, after.newestRowUtcMs)
        assertEquals(before.collection, after.collection)
        assertEquals(before.plmns, after.plmns)
    }

    @Test
    fun eightHoursOfRowsAreRebuiltInLittleMemory() {
        // 8 h of the golden session's two minutes: 82 560 cellinfo.csv rows, 13 MB. Reading them whole needed about
        // 190 MB of heap; one record at a time they fit in 32 MB.
        val directory = temp.newFolder("eight-hours")
        val repeats = 240
        writeRepeatedSession(directory, repeats)
        val javaHome = File(System.getProperty("java.home"))
        val java = listOf("bin/java.exe", "bin/java").map { File(javaHome, it) }.first { it.isFile }
        val classpath = listOf(RebuildProbe::class.java, SessionRebuild::class.java, Csv::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }
            .distinct()
            .joinToString(File.pathSeparator)

        val process = ProcessBuilder(
            java.path, "-Xmx32m", "-XX:+UseSerialGC", "-cp", classpath,
            RebuildProbe::class.java.name, directory.path, Golden.START_WALL_MS.toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()

        assertTrue("the rebuild did not finish within 2 minutes", process.waitFor(120, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
        assertEquals("fresh=${54L * repeats} plmn311480=${54 * repeats}", output.trim().lines().last())
    }

    /** cellinfo.csv and kpi.csv holding the golden rows [repeats] times over, each copy two minutes after the one before. */
    private fun writeRepeatedSession(directory: File, repeats: Int) {
        fun repeat(name: String, shiftColumns: (List<String>, List<String>, Long) -> List<String>) {
            val records = Csv.records(Golden.text(name))
            val header = Csv.parseRecord(records.first())
            val rows = records.drop(1).map { Csv.parseRecord(it) }
            File(directory, name).bufferedWriter(Charsets.UTF_8).use { out ->
                out.write(Csv.record(header))
                for (copy in 0 until repeats) {
                    val shiftMs = copy * 120_000L
                    for (row in rows) out.write(Csv.record(shiftColumns(header, row, shiftMs)))
                }
            }
        }
        repeat("cellinfo.csv") { header, row, shiftMs ->
            row.toMutableList().apply {
                val seen = header.indexOf("seen_utc")
                val epoch = header.indexOf("time_epoch")
                val stamp = header.indexOf("timestamp_ms")
                this[seen] = SessionFormat.utc(checkNotNull(SessionFormat.parseUtc(this[seen])) + shiftMs)
                this[epoch] = SessionFormat.timeEpoch(epochMs(this[epoch]) + shiftMs)
                this[stamp] = (this[stamp].toLong() + shiftMs).toString()
            }
        }
        repeat("kpi.csv") { header, row, shiftMs ->
            row.toMutableList().apply {
                val epoch = header.indexOf("time_epoch")
                this[epoch] = SessionFormat.timeEpoch(epochMs(this[epoch]) + shiftMs)
            }
        }
    }

    private fun epochMs(text: String): Long = BigDecimal(text).movePointRight(3).longValueExact()
}
