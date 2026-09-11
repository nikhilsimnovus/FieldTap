package com.fieldtap.core.session

import com.fieldtap.core.session.SessionFixtures.START_WALL_MS
import com.fieldtap.format.EventKind
import com.fieldtap.format.EventRat
import com.fieldtap.format.EventRow
import com.fieldtap.format.Severity
import com.fieldtap.format.TrafficTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventsTest {
    private val at = START_WALL_MS + 45_200

    @Test
    fun aMarkerCarriesItsTrimmedNote() {
        assertEquals(
            EventRow(at, EventRat.NONE, EventKind.MARKER, Severity.INFO, "Marker", "North entrance – badge reader, door 3"),
            SessionEvents.marker(at, "  North entrance – badge reader, door 3 "),
        )
        assertNull(SessionEvents.marker(at, null).detail)
        assertNull(SessionEvents.marker(at, " \n ").detail)
    }

    @Test
    fun aFailedTestNamesTheTestAndTheError() {
        assertEquals(
            EventRow(at, EventRat.NONE, EventKind.TEST_FAILED, Severity.ERROR, "Ping failed", "no cellular network"),
            SessionEvents.testFailed(at, TrafficTest.PING, "no cellular network"),
        )
        assertEquals("Download failed", SessionEvents.testFailed(at, TrafficTest.DOWNLOAD, "http status not 200").title)
    }

    @Test
    fun anInterruptionNamesTheExitReason() {
        assertEquals(
            EventRow(
                at,
                EventRat.NONE,
                EventKind.SESSION_INTERRUPTED,
                Severity.ERROR,
                "Session interrupted",
                "Android stopped the app: freezer",
                cause = "freezer",
            ),
            SessionEvents.sessionInterrupted(at, "freezer", "freezer"),
        )
        assertEquals("Android stopped the app", SessionEvents.sessionInterrupted(at, "unknown", null).detail)
        assertEquals("Android stopped the app", SessionEvents.sessionInterrupted(at, "unknown", "  ").detail)
        assertEquals("unknown", SessionEvents.sessionInterrupted(at, "Low Memory", null).cause)
    }

    @Test
    fun exitDescriptionsAreTidiedAndNeverCarryLongNumbers() {
        val killed = SessionEvents.sessionInterrupted(at, "other", "Killing 12345:com.fieldtap/u0a123\n(adj 900):  serial 358240051111110")
        assertEquals("Android stopped the app: Killing 12345:com.fieldtap/u0a123 (adj 900): serial #", killed.detail)

        val long = SessionEvents.sessionInterrupted(at, "other", "x".repeat(500))
        assertEquals("Android stopped the app: " + "x".repeat(200), long.detail)
    }

    @Test
    fun everyEventUsesARatAndSeverityItsKindAllows() {
        val rows = listOf(
            SessionEvents.marker(at, "note"),
            SessionEvents.testFailed(at, TrafficTest.PING, "timeout"),
            SessionEvents.sessionInterrupted(at, "anr", null),
        )

        for (row in rows) {
            assertTrue(row.kind.wire, row.rat in row.kind.rats)
            assertTrue(row.kind.wire, row.severity in row.kind.severities)
            assertNull(row.pci)
            assertNull(row.arfcn)
        }
    }
}
