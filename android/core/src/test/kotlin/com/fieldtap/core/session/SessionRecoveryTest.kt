package com.fieldtap.core.session

import com.fieldtap.core.session.SessionFixtures.DIR_NAME
import com.fieldtap.core.session.SessionFixtures.PID
import com.fieldtap.core.session.SessionFixtures.START_ELAPSED_MS
import com.fieldtap.core.session.SessionFixtures.START_WALL_MS
import com.fieldtap.core.session.SessionFixtures.cellRow
import com.fieldtap.core.session.SessionFixtures.collection
import com.fieldtap.core.session.SessionFixtures.identity
import com.fieldtap.core.session.SessionFixtures.kpiRow
import com.fieldtap.core.time.ManualClock
import com.fieldtap.format.EventsCsv
import com.fieldtap.format.SessionFile
import com.fieldtap.format.SessionJson
import com.fieldtap.format.TrackCsv
import com.fieldtap.format.TrafficCsv
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Launch recovery on real files in a temporary directory, as a killed process leaves them. */
class SessionRecoveryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val heartbeatWall = START_WALL_MS + 95_000
    private val heartbeat = HeartbeatRecord(heartbeatWall, START_ELAPSED_MS + 95_000, PID)
    private val lastSnapshot = SessionMetaFactory.snapshot(
        identity(),
        mapOf("311480" to 1),
        collection(fresh = 1, repeats = 2),
        zonePauses = 0,
        stoppedUtcMs = null,
        stoppedBy = ExitReasons.RECORDING,
    )
    private val marker = SessionEvents.marker(START_WALL_MS + 45_200, "North entrance")

    private lateinit var paths: SessionPaths
    private lateinit var store: SessionStore
    private lateinit var recovery: SessionRecovery

    @Before
    fun setUp() {
        paths = SessionPaths(root = File(temp.root, "sessions"), stateDir = File(temp.root, "session-state"))
        store = SessionStore(paths, ManualClock())
        recovery = SessionRecovery(store, paths)
    }

    /** A session recorded, flushed and snapshotted but never stopped, with its last heartbeat. */
    private fun killedSession(): File {
        val directory = paths.directory(DIR_NAME)
        val files = FileSessionFiles(directory, paths.heartbeat(DIR_NAME))
        files.create(SessionMetaFactory.open(identity()))
        files.appendKpi(kpiRow(START_WALL_MS + 400))
        files.appendEvent(marker)
        files.writeSnapshot(lastSnapshot, listOf(cellRow(samples = 1)))
        // Closing releases the file handles and deletes the heartbeat, so the last heartbeat is written again,
        // as a process killed right after it would have left it. session.json still says "recording".
        files.close()
        files.writeHeartbeat(heartbeat)
        return directory
    }

    private fun interrupted(
        stoppedBy: String = "freezer",
        description: String? = "freezer",
        stoppedUtcMs: Long = heartbeatWall,
    ): RecoveryAction.CloseInterrupted = RecoveryAction.CloseInterrupted(DIR_NAME, stoppedUtcMs, stoppedBy, description)

    @Test
    fun findOpenReportsTheHeartbeatAndTheNewestWrite() {
        val directory = killedSession()

        val open = recovery.findOpen()

        assertEquals(1, open.size)
        assertEquals(DIR_NAME, open[0].listing.dirName)
        assertEquals(heartbeat, open[0].heartbeat)
        assertEquals(SessionFile.entries.maxOf { File(directory, it.fileName).lastModified() }, open[0].lastWriteWallMs)
    }

    @Test
    fun closeCutsTheTornRowAppendsTheEventAndClosesSessionJson() {
        val directory = killedSession()
        val kpiFile = File(directory, "kpi.csv")
        val intactKpi = kpiFile.readBytes()
        kpiFile.appendBytes("1789050601.400,lte,,212,-8".toByteArray())
        val cellsBefore = File(directory, "cells.csv").readBytes()

        val outcome = recovery.close(interrupted())

        assertArrayEquals(intactKpi, kpiFile.readBytes())
        assertEquals(
            EventsCsv.headerLine + EventsCsv.encode(marker) +
                EventsCsv.encode(SessionEvents.sessionInterrupted(heartbeatWall, "freezer", "freezer")),
            File(directory, "events.csv").readText(),
        )
        val closed = SessionJson.decode(File(directory, "session.json").readText())
        assertEquals(
            lastSnapshot.copy(stoppedUtcMs = heartbeatWall, summary = lastSnapshot.summary.copy(stoppedBy = "freezer")),
            closed,
        )
        assertArrayEquals(cellsBefore, File(directory, "cells.csv").readBytes())
        assertFalse(paths.heartbeat(DIR_NAME).exists())
        assertEquals(
            SessionOutcome(DIR_NAME, START_WALL_MS, heartbeatWall, "freezer", interrupted = true, freshSamples = 1),
            outcome,
        )
        assertTrue(recovery.findOpen().isEmpty())
    }

    @Test
    fun closingTwiceWritesTheEventOnce() {
        killedSession()
        val action = interrupted(stoppedBy = "low_memory", description = null)

        val first = recovery.close(action)
        val second = recovery.close(action)

        assertEquals(first, second)
        val events = File(paths.directory(DIR_NAME), "events.csv").readText()
        assertEquals(1, events.split("\r\n").count { it.contains(",session_interrupted,") })
    }

    @Test
    fun aRetryAfterADeathMidwayReusesTheEventAlreadyWritten() {
        val directory = killedSession()
        val earlier = SessionEvents.sessionInterrupted(heartbeatWall, "freezer", null)
        File(directory, "events.csv").appendText(EventsCsv.encode(earlier))

        // This attempt planned another time and found no exit record; the event on disk wins.
        val outcome = recovery.close(interrupted(stoppedBy = "unknown", description = null, stoppedUtcMs = heartbeatWall + 5_000))

        assertEquals(heartbeatWall, outcome.stoppedUtcMs)
        assertEquals("freezer", outcome.stoppedBy)
        assertEquals(
            EventsCsv.headerLine + EventsCsv.encode(marker) + EventsCsv.encode(earlier),
            File(directory, "events.csv").readText(),
        )
        val closed = SessionJson.decode(File(directory, "session.json").readText())
        assertEquals(heartbeatWall, closed.stoppedUtcMs)
        assertEquals("freezer", closed.summary.stoppedBy)
    }

    @Test
    fun missingOrHeaderlessCsvFilesGetTheirHeaderBack() {
        val directory = killedSession()
        File(directory, "traffic.csv").delete()
        File(directory, "track.csv").writeText("time_utc,la")

        recovery.close(interrupted())

        assertEquals(TrafficCsv.headerLine, File(directory, "traffic.csv").readText())
        assertEquals(TrackCsv.headerLine, File(directory, "track.csv").readText())
    }

    @Test
    fun staleTemporaryFilesAreRemoved() {
        val directory = killedSession()
        File(directory, "session.json.tmp").writeText("{")
        File(directory, "cells.csv.tmp").writeText("first_seen_utc")

        recovery.close(interrupted())

        assertEquals(SessionFile.entries.map { it.fileName }.sorted(), directory.list()!!.sorted())
    }

    @Test
    fun theStopIsNeverWrittenBeforeTheStart() {
        val directory = killedSession()

        val outcome = recovery.close(interrupted(stoppedBy = "unknown", description = null, stoppedUtcMs = START_WALL_MS - 60_000))

        assertEquals(START_WALL_MS, outcome.stoppedUtcMs)
        assertEquals(START_WALL_MS, SessionJson.decode(File(directory, "session.json").readText()).stoppedUtcMs)
    }

    @Test
    fun closingASessionThatCannotBeReadFailsAndChangesNothing() {
        try {
            recovery.close(interrupted())
            fail("a session that does not exist cannot be closed")
        } catch (e: IOException) {
            // Expected.
        }

        val damaged = paths.directory(DIR_NAME).apply { mkdirs() }
        File(damaged, "session.json").writeText("{")
        try {
            recovery.close(interrupted())
            fail("a session whose session.json does not decode cannot be closed")
        } catch (e: IOException) {
            // Expected.
        }

        assertEquals(listOf("session.json"), damaged.list()!!.toList())
        assertEquals("{", File(damaged, "session.json").readText())
    }

    @Test
    fun findOpenForgetsHeartbeatsOfClosedOrMissingSessionsOnly() {
        killedSession()
        recovery.close(interrupted())
        val closedBeat = paths.heartbeat(DIR_NAME).apply { writeText(heartbeat.encode()) }
        val goneBeat = paths.heartbeat("20260901-080000_Gone").apply { writeText(heartbeat.encode()) }
        val goneTmp = File(paths.stateDir, "20260901-080000_Gone.heartbeat.tmp").apply { writeText("v1") }
        val damaged = paths.directory("20260902-080000_Damaged").apply { mkdirs() }
        File(damaged, "session.json").writeText("{")
        val damagedBeat = paths.heartbeat("20260902-080000_Damaged").apply { writeText(heartbeat.encode()) }

        assertTrue(recovery.findOpen().isEmpty())

        assertFalse(closedBeat.exists())
        assertFalse(goneBeat.exists())
        assertFalse(goneTmp.exists())
        assertTrue(damagedBeat.exists())
    }

    @Test
    fun plannerAndRecoveryCloseAKilledSessionEndToEnd() {
        killedSession()
        val exits = listOf(ExitRecord(pid = PID, timestampWallMs = heartbeatWall + 40_000, reason = 14, description = "freezer"))

        val actions = RecoveryPlanner.plan(recovery.findOpen(), exits, activeDirName = null)
        assertEquals(listOf(interrupted()), actions)
        val outcome = recovery.close(actions.single() as RecoveryAction.CloseInterrupted)

        assertEquals("freezer", outcome.stoppedBy)
        assertTrue(outcome.interrupted)
        assertTrue(store.openSessions().isEmpty())
    }

    @Test
    fun withoutItsHeartbeatASessionStopsAtItsNewestWrite() {
        val directory = killedSession()
        // App data cleared, or the state directory lost: the session files outlive the heartbeat.
        assertTrue(paths.heartbeat(DIR_NAME).delete())
        val newest = heartbeatWall + 2_000
        for (file in SessionFile.entries) assertTrue(File(directory, file.fileName).setLastModified(newest - 1_000))
        assertTrue(File(directory, "events.csv").setLastModified(newest))

        val open = recovery.findOpen().single()
        assertNull(open.heartbeat)
        assertEquals(newest, open.lastWriteWallMs)
        val action = RecoveryPlanner.plan(listOf(open), emptyList(), activeDirName = null).single()
        assertEquals(RecoveryAction.CloseInterrupted(DIR_NAME, newest, "unknown", null), action)
        val outcome = recovery.close(action as RecoveryAction.CloseInterrupted)

        assertEquals(newest, outcome.stoppedUtcMs)
        assertEquals("unknown", outcome.stoppedBy)
        val closed = SessionJson.decode(File(directory, "session.json").readText())
        assertEquals(newest, closed.stoppedUtcMs)
        assertEquals("unknown", closed.summary.stoppedBy)
    }
}
