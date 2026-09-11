package com.fieldtap.core.session

import com.fieldtap.core.session.SessionFixtures.DIR_NAME
import com.fieldtap.core.session.SessionFixtures.PID
import com.fieldtap.core.session.SessionFixtures.START_ELAPSED_MS
import com.fieldtap.core.session.SessionFixtures.START_WALL_MS
import com.fieldtap.core.session.SessionFixtures.cellInfoRow
import com.fieldtap.core.session.SessionFixtures.cellRow
import com.fieldtap.core.session.SessionFixtures.collection
import com.fieldtap.core.session.SessionFixtures.fix
import com.fieldtap.core.session.SessionFixtures.identity
import com.fieldtap.core.session.SessionFixtures.kpiRow
import com.fieldtap.core.session.SessionFixtures.pingRow
import com.fieldtap.core.session.SessionFixtures.trackRow
import com.fieldtap.format.CellInfoCsv
import com.fieldtap.format.CellsCsv
import com.fieldtap.format.EventsCsv
import com.fieldtap.format.KpiCsv
import com.fieldtap.format.SessionFile
import com.fieldtap.format.SessionJson
import com.fieldtap.format.TrackCsv
import com.fieldtap.format.TrafficCsv
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun csv(name: String, text: String): File = File(temp.root, name).apply { writeText(text) }

    private fun newFiles(): FileSessionFiles = FileSessionFiles(
        directory = File(temp.root, "sessions/$DIR_NAME"),
        heartbeatFile = File(temp.root, "session-state/$DIR_NAME.heartbeat"),
    )

    // --- AtomicFiles ---------------------------------------------------------------------------------

    @Test
    fun anAtomicWriteCreatesAndReplacesWithoutLeavingATemporaryFile() {
        val target = File(temp.root, "session.json")

        AtomicFiles.write(target, "first\n".toByteArray())
        assertEquals("first\n", target.readText())
        AtomicFiles.write(target, "second, longer\n".toByteArray())
        assertEquals("second, longer\n", target.readText())
        AtomicFiles.write(target, "3\n".toByteArray())

        assertEquals("3\n", target.readText())
        assertFalse(File(temp.root, "session.json.tmp").exists())
    }

    @Test
    fun aStaleTemporaryFileIsOverwritten() {
        val target = File(temp.root, "cells.csv")
        File(temp.root, "cells.csv.tmp").writeText("a stale temporary file that is much longer than the new content")

        AtomicFiles.write(target, "new".toByteArray())

        assertEquals("new", target.readText())
        assertFalse(File(temp.root, "cells.csv.tmp").exists())
    }

    @Test
    fun aFailedAtomicWriteNeverTouchesTheTarget() {
        val target = File(temp.root, "session.json").apply { writeText("{\"old\": true}\n") }
        val blocker = File(temp.root, "session.json.tmp")
        assertTrue(blocker.mkdir())
        File(blocker, "keep").writeText("x")

        try {
            AtomicFiles.write(target, "{\"new\": true}\n".toByteArray())
            fail("the write must fail when its temporary file cannot be created")
        } catch (e: IOException) {
            // Expected.
        }

        assertEquals("{\"old\": true}\n", target.readText())
    }

    // --- CsvRepair -----------------------------------------------------------------------------------

    @Test
    fun aTornLastRowIsCutBackToTheLastLineEnd() {
        val complete = "time_utc,lat\r\n2026-09-10T14:30:00.000+00:00,38.8895123\r\n"
        val torn = "2026-09-10T14:30:01.0"
        val file = csv("track.csv", complete + torn)

        assertEquals(torn.length.toLong(), CsvRepair.truncateToLastLineEnd(file))
        assertEquals(complete, file.readText())
    }

    @Test
    fun aCompleteFileIsLeftAlone() {
        val complete = "time_utc,lat\r\n2026-09-10T14:30:00.000+00:00,38.8895123\r\n"
        val file = csv("track.csv", complete)

        assertEquals(0L, CsvRepair.truncateToLastLineEnd(file))
        assertEquals(complete, file.readText())
    }

    @Test
    fun aCarriageReturnWithoutItsLineFeedIsNotALineEnd() {
        val file = csv("events.csv", "h\r\nrow one\r")

        assertEquals(8L, CsvRepair.truncateToLastLineEnd(file))
        assertEquals("h\r\n", file.readText())
    }

    @Test
    fun aFileWithoutAnyLineEndIsCutToNothing() {
        assertEquals(11L, CsvRepair.truncateToLastLineEnd(csv("kpi.csv", "time_utc,la")))
        assertEquals(0L, File(temp.root, "kpi.csv").length())
        // Lines ending in LF alone are not records of this format.
        assertEquals(6L, CsvRepair.truncateToLastLineEnd(csv("lf.csv", "h\nr1\n\n")))
        assertEquals(0L, CsvRepair.truncateToLastLineEnd(csv("empty.csv", "")))
    }

    @Test
    fun aLineEndSplitAcrossReadChunksIsFound() {
        val kept = "h\r\n" + "x".repeat(1_000) + "\r\n"
        // The LF lands on the first byte of the last 64 KiB chunk, its CR on the last byte of the one before.
        val torn = "y".repeat(64 * 1024 - 1)
        val file = csv("cellinfo.csv", kept + torn)

        assertEquals(torn.length.toLong(), CsvRepair.truncateToLastLineEnd(file))
        assertEquals(kept, file.readText())
    }

    @Test
    fun aMissingFileIsReportedAndNeverCreated() {
        val missing = File(temp.root, "traffic.csv")

        try {
            CsvRepair.truncateToLastLineEnd(missing)
            fail("a missing file must be reported")
        } catch (e: FileNotFoundException) {
            // Expected.
        }

        assertFalse(missing.exists())
    }

    // --- FileSessionFiles ----------------------------------------------------------------------------

    @Test
    fun createWritesEveryHeaderAndTheOpenSessionJson() {
        val files = newFiles()
        val meta = SessionMetaFactory.open(identity())
        try {
            files.create(meta)

            val directory = files.directory
            assertEquals(SessionFile.entries.map { it.fileName }.sorted(), directory.list()!!.sorted())
            for (file in SessionFile.CSV) {
                assertEquals(file.fileName, file.header.joinToString(",") + "\r\n", File(directory, file.fileName).readText())
            }
            assertEquals(SessionJson.encode(meta), File(directory, "session.json").readText())
        } finally {
            files.close()
        }
    }

    @Test
    fun createRefusesADirectoryThatAlreadyHoldsASessionFile() {
        val files = newFiles()
        files.directory.mkdirs()
        val existing = File(files.directory, "events.csv").apply { writeText("keep me") }

        try {
            files.create(SessionMetaFactory.open(identity()))
            fail("create() must refuse a directory that is already in use")
        } catch (e: IOException) {
            // Expected.
        } finally {
            files.close()
        }

        assertEquals("keep me", existing.readText())
        assertEquals(listOf("events.csv"), files.directory.list()!!.toList())
    }

    @Test
    fun aCreateThatFailsPartWayRemovesWhatItMade() {
        val files = newFiles()
        files.directory.mkdirs()
        val blocker = File(files.directory, "session.json.tmp").apply { mkdir() }
        File(blocker, "keep").writeText("x")

        try {
            files.create(SessionMetaFactory.open(identity()))
            fail("create() must fail when session.json cannot be written")
        } catch (e: IOException) {
            // Expected.
        } finally {
            files.close()
        }

        assertEquals(listOf("session.json.tmp"), files.directory.list()!!.toList())
    }

    @Test
    fun appendedRowsReachTheirFilesOnFlushAndSync() {
        val files = newFiles()
        try {
            files.create(SessionMetaFactory.open(identity()))
            val directory = files.directory
            val marker = SessionEvents.marker(START_WALL_MS + 45_200, "North entrance")

            files.appendEvent(marker)
            assertEquals(EventsCsv.headerLine, File(directory, "events.csv").readText())
            files.flush()
            assertEquals(EventsCsv.headerLine + EventsCsv.encode(marker), File(directory, "events.csv").readText())

            val kpi = kpiRow(START_WALL_MS + 400)
            val cellInfo = cellInfoRow(START_WALL_MS + 400, START_WALL_MS + 300, START_ELAPSED_MS + 300)
            val track = trackRow(fix(START_ELAPSED_MS, START_WALL_MS))
            val traffic = pingRow(START_WALL_MS + 10_000)
            files.appendKpi(kpi)
            files.appendKpi(kpi.copy(timeEpochMs = START_WALL_MS + 2_400))
            files.appendCellInfo(cellInfo)
            files.appendTrack(track)
            files.appendTraffic(traffic)
            files.sync()

            assertEquals(
                KpiCsv.headerLine + KpiCsv.encode(kpi) + KpiCsv.encode(kpi.copy(timeEpochMs = START_WALL_MS + 2_400)),
                File(directory, "kpi.csv").readText(),
            )
            assertEquals(CellInfoCsv.headerLine + CellInfoCsv.encode(cellInfo), File(directory, "cellinfo.csv").readText())
            assertEquals(TrackCsv.headerLine + TrackCsv.encode(track), File(directory, "track.csv").readText())
            assertEquals(TrafficCsv.headerLine + TrafficCsv.encode(traffic), File(directory, "traffic.csv").readText())
            assertEquals(CellsCsv.headerLine, File(directory, "cells.csv").readText())
        } finally {
            files.close()
        }
    }

    @Test
    fun closeWritesWhatWasStillBuffered() {
        val files = newFiles()
        files.create(SessionMetaFactory.open(identity()))
        val marker = SessionEvents.marker(START_WALL_MS + 45_200, null)
        files.appendEvent(marker)

        files.close()

        assertEquals(EventsCsv.headerLine + EventsCsv.encode(marker), File(files.directory, "events.csv").readText())
    }

    @Test
    fun aSnapshotReplacesCellsAndSessionJsonWithoutTemporaryFiles() {
        val files = newFiles()
        try {
            files.create(SessionMetaFactory.open(identity()))
            val meta = SessionMetaFactory.snapshot(identity(), mapOf("311480" to 54), collection(54, 66), 0, null, ExitReasons.RECORDING)
            val cells = listOf(cellRow(samples = 54))

            files.writeSnapshot(meta, cells)

            val directory = files.directory
            assertEquals(CellsCsv.headerLine + CellsCsv.encode(cells[0]), File(directory, "cells.csv").readText())
            assertEquals(SessionJson.encode(meta), File(directory, "session.json").readText())
            assertEquals(SessionFile.entries.map { it.fileName }.sorted(), directory.list()!!.sorted())
        } finally {
            files.close()
        }
    }

    @Test
    fun theHeartbeatLivesOutsideTheSessionAndCloseDeletesIt() {
        val files = newFiles()
        files.create(SessionMetaFactory.open(identity()))
        val record = HeartbeatRecord(START_WALL_MS + 5_000, START_ELAPSED_MS + 5_000, PID)
        val heartbeat = File(temp.root, "session-state/$DIR_NAME.heartbeat")

        files.writeHeartbeat(record)
        assertEquals(record.encode(), heartbeat.readText())
        assertEquals(SessionFile.entries.size, files.directory.list()!!.size)

        files.close()
        assertFalse(heartbeat.exists())
        files.close()

        try {
            files.appendEvent(SessionEvents.marker(START_WALL_MS, "late"))
            fail("appending after close must be refused")
        } catch (e: IllegalStateException) {
            // Expected.
        }
        // The recorder may still leave its stop time behind after close.
        files.writeHeartbeat(record)
        assertEquals(record.encode(), heartbeat.readText())
    }

    @Test
    fun theDroppedMarkerNoteLivesOutsideTheSessionAndOutlastsClose() {
        val note = File(temp.root, "session-state/$DIR_NAME.markers-dropped")
        val files = FileSessionFiles(
            directory = File(temp.root, "sessions/$DIR_NAME"),
            heartbeatFile = File(temp.root, "session-state/$DIR_NAME.heartbeat"),
            markersDroppedFile = note,
        )
        files.create(SessionMetaFactory.open(identity()))

        files.writeMarkersDropped(1)
        assertEquals("1\n", note.readText())
        files.writeMarkersDropped(3)
        assertEquals(SessionFile.entries.size, files.directory.list()!!.size)
        files.close()

        assertEquals("the Session detail screen reads it after the session stopped", "3\n", note.readText())
        files.writeMarkersDropped(4)
        assertEquals("4\n", note.readText())
        assertFalse(File(note.path + AtomicFiles.TMP_SUFFIX).exists())
        // Files built without a note write none.
        newFiles().writeMarkersDropped(2)
        assertEquals("4\n", note.readText())
    }

    @Test
    fun appendingBeforeCreateAndASecondCreateAreRefused() {
        val files = newFiles()
        try {
            files.appendKpi(kpiRow(START_WALL_MS + 400))
            fail("appending before create() must be refused")
        } catch (e: IllegalStateException) {
            // Expected.
        }
        files.create(SessionMetaFactory.open(identity()))
        try {
            files.create(SessionMetaFactory.open(identity()))
            fail("a second create() must be refused")
        } catch (e: IllegalStateException) {
            // Expected.
        } finally {
            files.close()
        }
    }

    @Test
    fun rowsBufferedPastTheEagerFlushReachTheFileAsWholeRecords() {
        val files = newFiles()
        try {
            files.create(SessionMetaFactory.open(identity()))
            val kpiFile = File(files.directory, "kpi.csv")
            // At least 60 bytes each: well past the 256 KiB an appender buffers before writing on its own.
            val rows = (0 until 10_000).map { kpiRow(START_WALL_MS + it * 100L, rsrpDbm = -60 - it % 50) }

            for (row in rows) files.appendKpi(row)
            val beforeFlush = kpiFile.readText()

            assertTrue(beforeFlush.length > KpiCsv.headerLine.length)
            assertTrue(beforeFlush.endsWith("\r\n"))
            files.flush()
            assertEquals(KpiCsv.headerLine + rows.joinToString("") { KpiCsv.encode(it) }, kpiFile.readText())
        } finally {
            files.close()
        }
    }
}
