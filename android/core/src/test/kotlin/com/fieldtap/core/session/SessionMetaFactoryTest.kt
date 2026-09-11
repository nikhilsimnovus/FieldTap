package com.fieldtap.core.session

import com.fieldtap.core.session.SessionFixtures.START_WALL_MS
import com.fieldtap.core.session.SessionFixtures.collection
import com.fieldtap.core.session.SessionFixtures.identity
import com.fieldtap.format.Capabilities
import com.fieldtap.format.CollectionMeta
import com.fieldtap.format.LocationPrecision
import com.fieldtap.format.PrivacyMeta
import com.fieldtap.format.SessionFile
import com.fieldtap.format.SummaryMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SessionMetaFactoryTest {
    @Test
    fun openDescribesASessionThatIsStillRecording() {
        val identity = identity()

        val meta = SessionMetaFactory.open(identity)

        assertEquals(identity.sessionId, meta.sessionId)
        assertNull(meta.groupId)
        assertEquals(identity.name, meta.name)
        assertEquals(identity.note, meta.note)
        assertEquals(identity.location, meta.location)
        assertEquals(START_WALL_MS, meta.startedUtcMs)
        assertNull(meta.stoppedUtcMs)
        assertEquals(identity.transport, meta.transport)
        assertEquals(identity.handset, meta.handset)
        assertEquals(identity.device, meta.device)
        assertEquals(SessionFile.CSV, meta.files)
        assertEquals(SummaryMeta(stoppedBy = "recording", plmns = emptyMap()), meta.summary)
        assertEquals(Capabilities(layer3 = false), meta.capabilities)
        assertEquals(CollectionMeta.EMPTY, meta.collection)
        assertEquals(PrivacyMeta(LocationPrecision.FULL, 0, identity.consent.version, identity.consent.sha256), meta.privacy)
        assertEquals("kpi", meta.privacy.dataClass)
    }

    @Test
    fun aSnapshotCarriesTheLiveValuesAndKeepsThePlmnOrder() {
        val plmns = linkedMapOf("311480" to 40, "310260" to 14)

        val meta = SessionMetaFactory.snapshot(
            identity(),
            plmns,
            collection(fresh = 54, repeats = 66),
            zonePauses = 2,
            stoppedUtcMs = START_WALL_MS + 120_000,
            stoppedBy = "user",
        )

        assertEquals(listOf("311480", "310260"), meta.summary.plmns.keys.toList())
        assertEquals(plmns, meta.summary.plmns)
        assertEquals("user", meta.summary.stoppedBy)
        assertEquals(START_WALL_MS + 120_000, meta.stoppedUtcMs)
        assertEquals(collection(fresh = 54, repeats = 66), meta.collection)
        assertEquals(2, meta.privacy.zonePauses)
        assertEquals(LocationPrecision.FULL, meta.privacy.locationPrecision)

        // Later changes to the caller's map do not reach a snapshot already taken.
        plmns["310410"] = 1
        assertEquals(2, meta.summary.plmns.size)
    }

    @Test
    fun aSnapshotRefusesValuesTheFormatForbids() {
        for (notAToken in listOf("", "User", "low memory", "crash!", "stopped-by")) {
            try {
                SessionMetaFactory.snapshot(identity(), emptyMap(), CollectionMeta.EMPTY, 0, null, notAToken)
                fail("'$notAToken' must be refused as summary.stopped_by")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }
        try {
            SessionMetaFactory.snapshot(identity(), emptyMap(), CollectionMeta.EMPTY, -1, null, "user")
            fail("a negative zone pause count must be refused")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }
}
