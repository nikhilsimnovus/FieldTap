package com.fieldtap.core.session

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LifecycleTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun exitReasonsZeroToSixteenMapToTheirNames() {
        val expected = listOf(
            "unknown", "exit_self", "signaled", "low_memory", "crash", "crash_native", "anr",
            "initialization_failure", "permission_change", "excessive_resource_usage", "user_requested",
            "user_stopped", "dependency_died", "other", "freezer", "package_state_change", "package_updated",
        )

        assertEquals(expected, (0..16).map { ExitReasons.token(it) })
        for (reason in listOf(-1, 17, 99, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals("reason $reason", ExitReasons.UNKNOWN, ExitReasons.token(reason))
        }
        for (token in expected + listOf(ExitReasons.RECORDING, ExitReasons.USER, ExitReasons.CRASH)) {
            assertTrue(token, ExitReasons.isToken(token))
        }
        for (notToken in listOf("", "Low_memory", "low memory", "anr!", "REASON_ANR")) {
            assertFalse(notToken, ExitReasons.isToken(notToken))
        }
        assertEquals(listOf("user", "storage_full", "permission_revoked", "service_destroyed"), StopCause.entries.map { it.token })
    }

    @Test
    fun theHeartbeatIsOneVersionedLine() {
        val record = HeartbeatRecord(wallMs = 1_789_050_695_000L, elapsedMs = 25_418_456L, pid = 12_345)

        assertEquals("v1 1789050695000 25418456 12345\n", record.encode())
        assertEquals(record, HeartbeatRecord.decode(record.encode()))
        assertEquals(HeartbeatRecord(0, 0, 0), HeartbeatRecord.decode("v1 0 0 0\n"))
        assertEquals(
            HeartbeatRecord(Long.MAX_VALUE, Long.MAX_VALUE, Int.MAX_VALUE),
            HeartbeatRecord.decode(HeartbeatRecord(Long.MAX_VALUE, Long.MAX_VALUE, Int.MAX_VALUE).encode()),
        )
    }

    @Test
    fun theHeartbeatOfASessionTheAppStoppedCarriesItsStopToken() {
        val record = HeartbeatRecord(wallMs = 1_789_050_695_000L, elapsedMs = 25_418_456L, pid = 12_345, stoppedBy = "storage_full")

        assertEquals("v2 1789050695000 25418456 12345 storage_full\n", record.encode())
        assertEquals(record, HeartbeatRecord.decode(record.encode()))
        val unreadable = listOf(
            "v2 1789050695000 25418456 12345 \n",
            "v2 1789050695000 25418456 12345 Storage_Full\n",
            "v2 1789050695000 25418456 12345 storage full\n",
            "v1 1789050695000 25418456 12345 storage_full\n",
            "v2 1789050695000 25418456 12345 " + "a".repeat(65) + "\n",
        )
        for (text in unreadable) {
            assertNull("decoded '$text'", HeartbeatRecord.decode(text))
        }
        assertEquals(
            IllegalArgumentException::class,
            runCatching { HeartbeatRecord(1, 2, 3, stoppedBy = "Not a token") }.exceptionOrNull()?.let { it::class },
        )
    }

    @Test
    fun aTornOrForeignHeartbeatDecodesToNull() {
        val unreadable = listOf(
            "",
            "\n",
            "v1 1789050695000 25418456 12345",
            "v1 1789050695000 25418",
            "v1 1789050695000 25418456\n",
            "v2 1789050695000 25418456 12345\n",
            "v1 1789050695000 25418456 12345 7\n",
            "v1 -1789050695000 25418456 12345\n",
            "v1 +1789050695000 25418456 12345\n",
            "v1 01789050695000 25418456 12345\n",
            "v1 1789050695000 25418456 12345\r\n",
            "v1 1789050695000 25418456 12345\n\n",
            " v1 1789050695000 25418456 12345\n",
            "v1  1789050695000 25418456 12345\n",
            "v1 9999999999999999999 25418456 12345\n",
            "v1 1789050695000 25418456 9999999999\n",
            "v1 1789050695000 25418456 99999999999\n",
        )

        for (text in unreadable) {
            assertNull("decoded '$text'", HeartbeatRecord.decode(text))
        }
    }

    @Test
    fun actionsAreFirstDueOnePeriodAfterTheStart() {
        val start = 25_323_456L
        val schedule = WriteSchedule(WritePolicy(), start)

        assertEquals(emptySet<WriteAction>(), schedule.due(start))
        assertEquals(emptySet<WriteAction>(), schedule.due(start + 999))
        assertEquals(setOf(WriteAction.FLUSH), schedule.due(start + 1_000))
        assertEquals(emptySet<WriteAction>(), schedule.due(start + 1_000))
        for (second in 2..4) {
            assertEquals(setOf(WriteAction.FLUSH), schedule.due(start + second * 1_000L))
        }
        assertEquals(listOf(WriteAction.FLUSH, WriteAction.SYNC, WriteAction.HEARTBEAT), schedule.due(start + 5_000).toList())
        for (second in 6..59) schedule.due(start + second * 1_000L)
        assertEquals(WriteAction.entries.toList(), schedule.due(start + 60_000).toList())
    }

    @Test
    fun lateTicksKeepTheGridAndAStallDoesNotBurst() {
        val schedule = WriteSchedule(WritePolicy(), 0)

        assertEquals(setOf(WriteAction.FLUSH), schedule.due(1_003))
        // The next tick comes 998 ms later, yet it has reached the 2 s slot.
        assertEquals(setOf(WriteAction.FLUSH), schedule.due(2_001))
        assertEquals(emptySet<WriteAction>(), schedule.due(2_999))
        assertEquals(setOf(WriteAction.FLUSH), schedule.due(3_000))
        // A stall of 9.5 s: every action due inside it fires once, not once per missed slot.
        assertEquals(listOf(WriteAction.FLUSH, WriteAction.SYNC, WriteAction.HEARTBEAT), schedule.due(12_500).toList())
        assertEquals(emptySet<WriteAction>(), schedule.due(12_999))
        assertEquals(setOf(WriteAction.FLUSH), schedule.due(13_000))
        assertEquals(listOf(WriteAction.FLUSH, WriteAction.SYNC, WriteAction.HEARTBEAT), schedule.due(15_000).toList())
    }

    @Test
    fun aScheduleNeedsPositivePeriods() {
        for (policy in listOf(WritePolicy(flushEveryMs = 0), WritePolicy(syncEveryMs = -5), WritePolicy(storageCheckEveryMs = 0))) {
            try {
                WriteSchedule(policy, 0)
                fail("$policy must be refused")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun aSessionStartsOnlyBelowTheCapWithTwoHundredMegabytesFree() {
        val policy = StoragePolicy()

        assertTrue(StorageStatus(usedBytes = 1_999_999_999, freeBytes = 200_000_000, policy = policy).canStart)
        assertFalse(StorageStatus(usedBytes = 2_000_000_000, freeBytes = 10_000_000_000, policy = policy).canStart)
        assertFalse(StorageStatus(usedBytes = 0, freeBytes = 199_999_999, policy = policy).canStart)
    }

    @Test
    fun aRunningSessionStopsAtTheCapOrBelowFiftyMegabytesFree() {
        val policy = StoragePolicy()

        assertFalse(StorageStatus(usedBytes = 1_999_999_999, freeBytes = 50_000_000, policy = policy).mustStop)
        assertTrue(StorageStatus(usedBytes = 2_000_000_000, freeBytes = 10_000_000_000, policy = policy).mustStop)
        assertTrue(StorageStatus(usedBytes = 0, freeBytes = 49_999_999, policy = policy).mustStop)
    }

    @Test
    fun usedBytesCountsEveryFileBelowTheRoot() {
        val root = temp.newFolder("sessions")
        File(root, "20260910-143000_Walk/nested").mkdirs()
        File(root, "20260910-143000_Walk/kpi.csv").writeBytes(ByteArray(1_000))
        File(root, "20260910-143000_Walk/nested/left.bin").writeBytes(ByteArray(234))
        File(root, "top.txt").writeBytes(ByteArray(66))

        assertEquals(1_300L, StorageUsage.usedBytes(root))
        assertEquals(1_234L, StorageUsage.usedBytes(File(root, "20260910-143000_Walk")))
        assertEquals(0L, StorageUsage.usedBytes(File(root, "missing")))
    }

    @Test
    fun freeBytesAsksTheNearestExistingParent() {
        val notYetCreated = File(temp.root, "sessions/not/yet")

        assertTrue(StorageUsage.freeBytes(notYetCreated) > 0)
        assertFalse(notYetCreated.exists())
        val status = StorageUsage.status(temp.root, StoragePolicy(capBytes = 10, minFreeBytes = 1))
        assertEquals(StoragePolicy(capBytes = 10, minFreeBytes = 1), status.policy)
        assertEquals(0L, status.usedBytes)
    }

    @Test
    fun aCustomPolicyKeepsEachActionOnItsOwnGrid() {
        val policy = WritePolicy(
            flushEveryMs = 2_000,
            syncEveryMs = 4_000,
            snapshotEveryMs = 8_000,
            heartbeatEveryMs = 3_000,
            storageCheckEveryMs = 8_000,
        )
        val schedule = WriteSchedule(policy, startElapsedMs = 100)

        assertEquals(emptySet<WriteAction>(), schedule.due(1_100))
        assertEquals(setOf(WriteAction.FLUSH), schedule.due(2_100))
        assertEquals(setOf(WriteAction.HEARTBEAT), schedule.due(3_100))
        assertEquals(listOf(WriteAction.FLUSH, WriteAction.SYNC), schedule.due(4_100).toList())
        assertEquals(emptySet<WriteAction>(), schedule.due(5_099))
        assertEquals(listOf(WriteAction.FLUSH, WriteAction.HEARTBEAT), schedule.due(6_100).toList())
        assertEquals(WriteAction.entries.toList() - WriteAction.HEARTBEAT, schedule.due(8_100).toList())
    }
}
