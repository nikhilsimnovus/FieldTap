package com.fieldtap.recovery

import com.fieldtap.core.session.ExitRecord
import com.fieldtap.core.session.HeartbeatRecord
import com.fieldtap.core.session.OpenSession
import com.fieldtap.core.session.RecoveryAction
import com.fieldtap.core.session.SessionListing
import com.fieldtap.core.session.SessionOutcome
import java.io.File
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchRecoveryTest {
    private val a = open("20260910-100000_a")
    private val b = open("20260910-110000_b")
    private val c = open("20260910-120000_c")

    @Test
    fun closesInterruptedSessionsInPlanOrderAfterReadingExitReasons() = runTest {
        val events = synchronizedList()
        val recovery = recovery(
            events = events,
            findOpen = { listOf(a, b, c) },
            activeDirName = { b.listing.dirName },
            plan = { open, exits, active ->
                events += "plan active=$active exits=${exits.size}"
                open.map { session ->
                    val dirName = session.listing.dirName
                    if (dirName == active) {
                        RecoveryAction.LeaveRunning(dirName)
                    } else {
                        RecoveryAction.CloseInterrupted(dirName, 1_000, "low_memory", null)
                    }
                }.reversed()
            },
        )

        val outcomes = recovery.run()

        assertEquals(
            listOf("findOpen", "exits", "plan active=${b.listing.dirName} exits=1", "close ${c.listing.dirName}", "close ${a.listing.dirName}"),
            events.toList(),
        )
        assertEquals(listOf(c.listing.dirName, a.listing.dirName), outcomes.map { it.dirName })
        assertEquals(outcomes, recovery.closed.value)
        assertTrue(recovery.completed)
    }

    @Test
    fun runsOnceAndLaterCallsReturnTheSameOutcomes() = runTest {
        val events = synchronizedList()
        val recovery = recovery(events = events, findOpen = { listOf(a) })

        val first = recovery.run()
        val second = recovery.run()

        assertEquals(first, second)
        assertEquals(1, events.count { it == "findOpen" })
    }

    @Test
    fun concurrentCallersWaitForTheFirstRun() = runTest {
        val events = synchronizedList()
        val recovery = recovery(events = events, findOpen = { listOf(a, c) })

        val one = async { recovery.run() }
        val two = async { recovery.run() }

        assertEquals(one.await(), two.await())
        assertEquals(1, events.count { it == "findOpen" })
        assertEquals(2, recovery.closed.value.size)
    }

    @Test
    fun aSessionThatFailsToCloseIsLoggedWithoutItsNameAndStaysOpen() = runTest {
        val events = synchronizedList()
        val logs = synchronizedList()
        val recovery = recovery(
            events = events,
            logs = logs,
            findOpen = { listOf(a, c) },
            close = { action ->
                if (action.dirName == a.listing.dirName) throw java.io.IOException("disk full at ${action.dirName}")
                outcome(action.dirName)
            },
        )

        val outcomes = recovery.run()

        assertEquals(listOf(c.listing.dirName), outcomes.map { it.dirName })
        assertEquals(listOf(c.listing.dirName), recovery.closed.value.map { it.dirName })
        assertEquals(1, logs.size)
        assertFalse("logs never carry session content", logs.single().contains("_a"))
        assertTrue(logs.single().contains("IOException"))
    }

    @Test
    fun aSessionTooLargeToCloseNeitherKeepsTheOthersOpenNorRunsAgainAtTheNextStart() = runTest {
        val events = synchronizedList()
        val logs = synchronizedList()
        val recovery = recovery(
            events = events,
            logs = logs,
            findOpen = { listOf(a, c) },
            close = { action ->
                if (action.dirName == a.listing.dirName) throw OutOfMemoryError("rebuilding ${action.dirName}")
                outcome(action.dirName)
            },
        )

        val first = recovery.run()
        val second = recovery.run()

        assertEquals(listOf(c.listing.dirName), first.map { it.dirName })
        assertEquals(first, second)
        assertTrue(recovery.completed)
        assertEquals("a start after the failure does not run recovery again", 1, events.count { it == "findOpen" })
        assertTrue(logs.single().contains("OutOfMemoryError"))
        assertFalse("logs never carry session content", logs.single().contains("_a"))
    }

    @Test
    fun anErrorOutsideAnySessionStillCompletes() = runTest {
        val logs = synchronizedList()
        val recovery = recovery(logs = logs, findOpen = { throw StackOverflowError() })

        assertTrue(recovery.run().isEmpty())
        assertTrue(recovery.completed)
        assertEquals(1, logs.size)
    }

    @Test
    fun aFailureToFindOpenSessionsStillCompletes() = runTest {
        val logs = synchronizedList()
        val recovery = recovery(logs = logs, findOpen = { throw SecurityException("no access") })

        assertTrue(recovery.run().isEmpty())
        assertTrue(recovery.completed)
        assertEquals(1, logs.size)
    }

    @Test
    fun unreadableExitReasonsStillCloseWithNoRecords() = runTest {
        val events = synchronizedList()
        val recovery = recovery(
            events = events,
            findOpen = { listOf(a) },
            exitRecords = { throw IllegalStateException("activity manager unavailable") },
        )

        val outcomes = recovery.run()

        assertEquals(1, outcomes.size)
        assertTrue(events.contains("plan exits=0"))
    }

    @Test
    fun noOpenSessionsMeansNoExitReasonsAreRead() = runTest {
        val events = synchronizedList()
        val recovery = recovery(events = events, findOpen = { emptyList() })

        assertTrue(recovery.run().isEmpty())
        assertFalse(events.contains("exits"))
        assertTrue(recovery.closed.value.isEmpty())
    }

    @Test
    fun acknowledgingRemovesOnlyThatSession() = runTest {
        val recovery = recovery(findOpen = { listOf(a, c) })
        recovery.run()

        recovery.acknowledge(a.listing.dirName)
        recovery.acknowledge("20260101-000000_unknown")

        assertEquals(listOf(c.listing.dirName), recovery.closed.value.map { it.dirName })
    }

    private fun TestScope.recovery(
        events: MutableList<String> = synchronizedList(),
        logs: MutableList<String> = synchronizedList(),
        findOpen: () -> List<OpenSession>,
        exitRecords: () -> List<ExitRecord> = { listOf(ExitRecord(pid = 42, timestampWallMs = 1_500, reason = 3, description = null)) },
        close: (RecoveryAction.Close) -> SessionOutcome = { action -> outcome(action.dirName) },
        activeDirName: () -> String? = { null },
        plan: ((List<OpenSession>, List<ExitRecord>, String?) -> List<RecoveryAction>)? = null,
    ): LaunchRecovery = LaunchRecovery(
        findOpen = {
            events += "findOpen"
            findOpen()
        },
        exitRecords = {
            events += "exits"
            exitRecords()
        },
        close = { action ->
            events += "close ${action.dirName}"
            close(action)
        },
        activeDirName = activeDirName,
        dispatcher = StandardTestDispatcher(testScheduler),
        log = { message -> logs += message },
        plan = plan ?: { open: List<OpenSession>, exits: List<ExitRecord>, _: String? ->
            events += "plan exits=${exits.size}"
            open.map { RecoveryAction.CloseInterrupted(it.listing.dirName, 1_000, "low_memory", null) }
        },
    )

    private fun outcome(dirName: String) = SessionOutcome(
        dirName = dirName,
        startedUtcMs = 0,
        stoppedUtcMs = 1_000,
        stoppedBy = "low_memory",
        interrupted = true,
        freshSamples = 3,
    )

    private fun synchronizedList(): MutableList<String> = Collections.synchronizedList(mutableListOf())

    private companion object {
        fun open(dirName: String) = OpenSession(
            listing = SessionListing(dirName, File(dirName), meta = null, error = null, sizeBytes = 0),
            heartbeat = HeartbeatRecord(wallMs = 1_000, elapsedMs = 2_000, pid = 42),
            lastWriteWallMs = 1_000,
        )
    }
}
