package com.fieldtap.core.session

import com.fieldtap.core.session.SessionFixtures.DIR_NAME
import com.fieldtap.core.session.SessionFixtures.START_ELAPSED_MS
import com.fieldtap.core.session.SessionFixtures.START_WALL_MS
import com.fieldtap.core.session.SessionFixtures.identity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryPlannerTest {
    /** The last heartbeat of the session under test: ten minutes in. */
    private val stop = START_WALL_MS + 600_000

    private fun session(
        dirName: String,
        heartbeat: HeartbeatRecord?,
        lastWriteWallMs: Long = stop + 3_000,
        startedUtcMs: Long = START_WALL_MS,
    ): OpenSession = OpenSession(
        listing = SessionListing(dirName, File(dirName), SessionMetaFactory.open(identity(startedUtcMs)), error = null, sizeBytes = 0),
        heartbeat = heartbeat,
        lastWriteWallMs = lastWriteWallMs,
    )

    private fun beat(wallMs: Long, pid: Int): HeartbeatRecord = HeartbeatRecord(wallMs, START_ELAPSED_MS + 600_000, pid)

    @Test
    fun theExitRecordOfTheHeartbeatsProcessWins() {
        val exits = listOf(
            ExitRecord(pid = 999, timestampWallMs = stop + 1_000, reason = 10, description = "swiped away"),
            ExitRecord(pid = 4242, timestampWallMs = stop + 30_000, reason = 14, description = "freezer"),
        )

        val actions = RecoveryPlanner.plan(listOf(session(DIR_NAME, beat(stop, 4242))), exits, activeDirName = null)

        assertEquals(listOf(RecoveryAction.CloseInterrupted(DIR_NAME, stop, "freezer", "freezer")), actions)
    }

    @Test
    fun aReusedPidFromLongBeforeTheHeartbeatIsNotTheDeath() {
        val exits = listOf(
            ExitRecord(pid = 4242, timestampWallMs = stop - 3_600_000, reason = 4, description = "an older crash"),
            ExitRecord(pid = 77, timestampWallMs = stop + 2_000, reason = 3, description = null),
        )

        val actions = RecoveryPlanner.plan(listOf(session(DIR_NAME, beat(stop, 4242))), exits, activeDirName = null)

        assertEquals(listOf(RecoveryAction.CloseInterrupted(DIR_NAME, stop, "low_memory", null)), actions)
    }

    @Test
    fun withoutAPidMatchTheEarliestRecordFromTenSecondsBeforeTheStopIsUsed() {
        val exits = listOf(
            ExitRecord(pid = 5, timestampWallMs = stop + 5_000, reason = 4, description = "crash"),
            ExitRecord(pid = 6, timestampWallMs = stop - 10_000, reason = 3, description = "lmk"),
            ExitRecord(pid = 7, timestampWallMs = stop - 10_001, reason = 6, description = "anr"),
        )

        val actions = RecoveryPlanner.plan(listOf(session(DIR_NAME, beat(stop, 1))), exits, activeDirName = null)

        assertEquals(listOf(RecoveryAction.CloseInterrupted(DIR_NAME, stop, "low_memory", "lmk")), actions)
    }

    @Test
    fun withoutAHeartbeatTheNewestFileTimeIsTheStop() {
        val lastWrite = stop + 3_000
        val exits = listOf(ExitRecord(pid = 4242, timestampWallMs = lastWrite + 500, reason = 6, description = null))

        val actions = RecoveryPlanner.plan(listOf(session(DIR_NAME, null, lastWriteWallMs = lastWrite)), exits, activeDirName = null)

        assertEquals(listOf(RecoveryAction.CloseInterrupted(DIR_NAME, lastWrite, "anr", null)), actions)
    }

    @Test
    fun aRebootLeavesNoRecordAndGivesUnknown() {
        val actions = RecoveryPlanner.plan(listOf(session(DIR_NAME, beat(stop, 4242))), emptyList(), activeDirName = null)

        assertEquals(listOf(RecoveryAction.CloseInterrupted(DIR_NAME, stop, "unknown", null)), actions)
    }

    @Test
    fun theRunningSessionIsLeftAlone() {
        val running = "20260910-160000_Running"
        val exits = listOf(ExitRecord(pid = 4242, timestampWallMs = stop + 1_000, reason = 14, description = "freezer"))

        val actions = RecoveryPlanner.plan(
            listOf(session(running, beat(stop + 5_000, 4242)), session(DIR_NAME, beat(stop, 4242))),
            exits,
            activeDirName = running,
        )

        assertEquals(
            listOf(RecoveryAction.LeaveRunning(running), RecoveryAction.CloseInterrupted(DIR_NAME, stop, "freezer", "freezer")),
            actions,
        )
    }

    @Test
    fun eachSessionTakesTheRecordOfItsOwnProcess() {
        val first = "20260910-100000_First"
        val second = "20260910-120000_Second"
        val laterStop = stop + 7_200_000
        val exits = listOf(
            ExitRecord(pid = 20, timestampWallMs = laterStop + 1_000, reason = 4, description = null),
            ExitRecord(pid = 10, timestampWallMs = stop + 500, reason = 3, description = null),
        )

        val actions = RecoveryPlanner.plan(
            listOf(session(first, beat(stop, 10)), session(second, beat(laterStop, 20), startedUtcMs = START_WALL_MS + 7_000_000)),
            exits,
            activeDirName = null,
        )

        assertEquals(
            listOf(
                RecoveryAction.CloseInterrupted(first, stop, "low_memory", null),
                RecoveryAction.CloseInterrupted(second, laterStop, "crash", null),
            ),
            actions,
        )
    }

    @Test
    fun oneRecordNeverExplainsTwoSessions() {
        val first = "20260910-100000_First"
        val second = "20260910-120000_Second"
        val exits = listOf(ExitRecord(pid = 1, timestampWallMs = stop + 60_000, reason = 13, description = null))

        val actions = RecoveryPlanner.plan(
            listOf(session(second, null, lastWriteWallMs = stop + 10_000), session(first, null, lastWriteWallMs = stop)),
            exits,
            activeDirName = null,
        )

        // The earlier stop takes the only record; the later one is left unknown. Actions keep the input order.
        assertEquals(
            listOf(
                RecoveryAction.CloseInterrupted(second, stop + 10_000, "unknown", null),
                RecoveryAction.CloseInterrupted(first, stop, "other", null),
            ),
            actions,
        )
    }

    @Test
    fun theStopIsNeverBeforeTheStart() {
        val skewed = session(DIR_NAME, beat(START_WALL_MS - 5_000, 4242))

        val actions = RecoveryPlanner.plan(listOf(skewed), emptyList(), activeDirName = null)

        assertEquals(listOf(RecoveryAction.CloseInterrupted(DIR_NAME, START_WALL_MS, "unknown", null)), actions)
        assertEquals(START_WALL_MS, RecoveryPlanner.stopTime(skewed))
    }

    @Test
    fun aHeartbeatWithAStopTokenClosesAsThatStopAndLeavesTheExitRecordsToOthers() {
        val stoppedDir = "20260910-150000_Stopped"
        val stopped = session(stoppedDir, HeartbeatRecord(stop, START_ELAPSED_MS + 600_000, 4242, stoppedBy = "storage_full"))
        val killed = session(DIR_NAME, beat(stop + 60_000, 77))
        // Hours later a process that reused pid 4242 died. It says nothing about the session the app stopped.
        val exits = listOf(ExitRecord(pid = 4242, timestampWallMs = stop + 3 * 3_600_000, reason = 3, description = "lmk"))

        val actions = RecoveryPlanner.plan(listOf(stopped, killed), exits, activeDirName = null)

        assertEquals(
            listOf(
                RecoveryAction.CloseStopped(stoppedDir, stop, "storage_full"),
                RecoveryAction.CloseInterrupted(DIR_NAME, stop + 60_000, "low_memory", "lmk"),
            ),
            actions,
        )
        assertEquals(listOf(RecoveryAction.LeaveRunning(stoppedDir)), RecoveryPlanner.plan(listOf(stopped), exits, stoppedDir))
    }

    @Test
    fun nothingOpenMeansNothingToDo() {
        val exits = listOf(ExitRecord(pid = 1, timestampWallMs = stop, reason = 4, description = null))

        assertEquals(emptyList<RecoveryAction>(), RecoveryPlanner.plan(emptyList(), exits, activeDirName = DIR_NAME))
    }
}
