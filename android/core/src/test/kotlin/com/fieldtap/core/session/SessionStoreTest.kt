package com.fieldtap.core.session

import com.fieldtap.core.session.SessionFixtures.DIR_NAME
import com.fieldtap.core.session.SessionFixtures.START_ELAPSED_MS
import com.fieldtap.core.session.SessionFixtures.START_WALL_MS
import com.fieldtap.core.session.SessionFixtures.identity
import com.fieldtap.core.time.ManualClock
import com.fieldtap.format.CollectionMeta
import com.fieldtap.format.SessionJson
import com.fieldtap.format.SessionMeta
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** 2026-09-10T14:30:00.400+00:00. */
    private val clock = ManualClock(wallMs = START_WALL_MS + 400, elapsedMs = START_ELAPSED_MS)
    private lateinit var paths: SessionPaths
    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        paths = SessionPaths(root = File(temp.root, "sessions"), stateDir = File(temp.root, "session-state"))
        store = SessionStore(paths, clock)
    }

    private fun openMeta(): SessionMeta = SessionMetaFactory.open(identity())

    private fun closedMeta(): SessionMeta =
        SessionMetaFactory.snapshot(identity(), emptyMap(), CollectionMeta.EMPTY, 0, START_WALL_MS + 120_000, "user")

    private fun writeSession(dirName: String, meta: SessionMeta): File {
        val directory = paths.directory(dirName).apply { mkdirs() }
        File(directory, "session.json").writeText(SessionJson.encode(meta))
        return directory
    }

    @Test
    fun listIsNewestFirstAndSkipsEverythingElse() {
        writeSession("20260910-143000_Mall-walk-north-path", openMeta())
        writeSession("20260911-090000_Second-walk", closedMeta())
        writeSession("20260909-170000_First-walk", closedMeta())
        paths.directory(".20260912-000000_hidden").mkdirs()
        paths.directory("notes").mkdirs()
        File(paths.root, "20260912-000000_a-file").writeText("not a directory")

        assertEquals(
            listOf("20260911-090000_Second-walk", "20260910-143000_Mall-walk-north-path", "20260909-170000_First-walk"),
            store.list().map { it.dirName },
        )
    }

    @Test
    fun listIsEmptyBeforeTheFirstSession() {
        assertTrue(store.list().isEmpty())
        assertTrue(store.openSessions().isEmpty())
    }

    @Test
    fun aDamagedSessionIsStillListedWithItsProblem() {
        val broken = paths.directory("20260910-150000_Broken").apply { mkdirs() }
        File(broken, "session.json").writeText("{\"format\": ")
        File(broken, "kpi.csv").writeBytes(ByteArray(100))
        paths.directory("20260910-160000_Empty").mkdirs()

        val listings = store.list().associateBy { it.dirName }

        val brokenListing = listings.getValue("20260910-150000_Broken")
        assertNull(brokenListing.meta)
        assertTrue(brokenListing.error!!, brokenListing.error.startsWith("session.json is not valid"))
        assertEquals(111L, brokenListing.sizeBytes)
        val emptyListing = listings.getValue("20260910-160000_Empty")
        assertNull(emptyListing.meta)
        assertEquals("session.json is missing", emptyListing.error)
        assertEquals(0L, emptyListing.sizeBytes)
    }

    @Test
    fun readAnswersOnlyForExistingSessionDirectories() {
        val directory = writeSession(DIR_NAME, openMeta())

        val listing = store.read(DIR_NAME)
        assertEquals(DIR_NAME, listing?.dirName)
        assertEquals(directory, listing?.directory)
        assertEquals(openMeta(), listing?.meta)
        assertNull(listing?.error)
        assertNull(store.read("20260101-000000_missing"))
        assertNull(store.read("notes"))
        assertNull(store.read("../sessions"))
        assertNull(store.read(""))
    }

    @Test
    fun allocateNamesTheDirectoryAfterTheStartSecond() = runTest {
        val allocated = store.allocate("Mall walk (north path)")

        assertEquals(AllocatedSession(DIR_NAME, paths.directory(DIR_NAME), START_WALL_MS + 400), allocated)
        assertTrue(allocated.directory.isDirectory)
        assertEquals(0, allocated.directory.list()!!.size)
    }

    @Test
    fun allocateWaitsForTheNextSecondWhenTheNameIsTaken() = runTest {
        val taken = paths.directory(DIR_NAME).apply { mkdirs() }

        val allocation = async { store.allocate("Mall walk (north path)") }
        runCurrent()
        assertFalse(allocation.isCompleted)

        // 14:30:00.400 waits 600 ms, to 14:30:01.000.
        clock.advance(599)
        advanceTimeBy(599)
        runCurrent()
        assertFalse(allocation.isCompleted)

        clock.advance(1)
        advanceTimeBy(1)
        runCurrent()
        val allocated = allocation.await()

        assertEquals("20260910-143001_Mall-walk-north-path", allocated.dirName)
        assertEquals(START_WALL_MS + 1_000, allocated.startedUtcMs)
        assertTrue(allocated.directory.isDirectory)
        assertTrue(taken.isDirectory)
        assertEquals(0, taken.list()!!.size)
    }

    @Test
    fun deleteRefusesTheRunningSession() {
        val directory = writeSession(DIR_NAME, openMeta())

        assertFalse(store.delete(DIR_NAME, activeDirName = DIR_NAME))

        assertTrue(File(directory, "session.json").isFile)
    }

    @Test
    fun deleteRemovesTheDirectoryAndItsHeartbeat() {
        val directory = writeSession(DIR_NAME, closedMeta())
        File(directory, "kpi.csv").writeText("frame\r\n")
        File(directory, "nested").mkdirs()
        File(directory, "nested/left.bin").writeBytes(ByteArray(3))
        paths.stateDir.mkdirs()
        val heartbeat = paths.heartbeat(DIR_NAME).apply { writeText("v1 1 2 3\n") }

        assertTrue(store.delete(DIR_NAME, activeDirName = "20260911-090000_Other"))

        assertFalse(directory.exists())
        assertFalse(heartbeat.exists())
        assertTrue(store.list().isEmpty())
        assertFalse(store.delete(DIR_NAME, activeDirName = null))
        assertFalse(store.delete("notes", activeDirName = null))
    }

    @Test
    fun openSessionsAreTheOnesStillWithoutAStopTime() {
        writeSession("20260910-143000_Open", openMeta())
        writeSession("20260909-170000_Closed", closedMeta())
        paths.directory("20260908-120000_Damaged").mkdirs()

        assertEquals(listOf("20260910-143000_Open"), store.openSessions().map { it.dirName })
    }

    @Test
    fun heartbeatsLiveInTheStateDirectory() {
        assertEquals(File(temp.root, "session-state/$DIR_NAME.heartbeat"), paths.heartbeat(DIR_NAME))
        assertEquals(File(temp.root, "session-state/$DIR_NAME.markers-dropped"), paths.markersDropped(DIR_NAME))
        assertEquals(File(temp.root, "sessions/$DIR_NAME"), paths.directory(DIR_NAME))
    }

    @Test
    fun theDroppedMarkerNoteIsReadAndDeletedWithItsSession() {
        writeSession(DIR_NAME, closedMeta())
        assertEquals("no note, nothing dropped", 0, store.markersDropped(DIR_NAME))
        paths.stateDir.mkdirs()
        val note = paths.markersDropped(DIR_NAME).apply { writeText(MarkersDroppedNote.encode(2)) }

        assertEquals(2, store.markersDropped(DIR_NAME))
        assertEquals(0, store.markersDropped("notes"))
        note.writeText("two\n")
        assertEquals("an unreadable note counts nothing", 0, store.markersDropped(DIR_NAME))
        note.writeText("2\n")

        assertTrue(store.delete(DIR_NAME, activeDirName = null))
        assertFalse(note.exists())
    }

    @Test
    fun aDroppedMarkerNoteHoldsItsCountAndNothingElse() {
        assertEquals("0\n", MarkersDroppedNote.encode(0))
        assertEquals("12\n", MarkersDroppedNote.encode(12))
        assertEquals(12, MarkersDroppedNote.decode("12\n"))
        assertEquals(12, MarkersDroppedNote.decode("12"))
        for (text in listOf("", "\n", "-1\n", "1 2\n", "1.5\n", "9999999999\n", "12\r\n")) {
            assertNull(text, MarkersDroppedNote.decode(text))
        }
    }

    @Test
    fun aSessionJsonAboveSixteenMebibytesIsReportedWithoutBeingRead() {
        val directory = paths.directory(DIR_NAME).apply { mkdirs() }
        val tooLarge = 16L * 1024 * 1024 + 1
        RandomAccessFile(File(directory, "session.json"), "rw").use { it.setLength(tooLarge) }

        val listing = store.read(DIR_NAME)

        assertNull(listing?.meta)
        assertEquals("session.json is larger than 16 MiB", listing?.error)
        assertEquals(tooLarge, listing?.sizeBytes)
        assertTrue(store.openSessions().isEmpty())
    }
}
