package com.fieldtap.core.probe

import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.input.ServiceStateSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStateProbeAccumulatorTest {

    @Test
    fun noCallbackIsNoted() {
        val probe = ServiceStateProbeAccumulator()

        assertEquals(ServiceStateProbe(0, false, false, emptyList()), probe.result())
        assertEquals(listOf("No service-state callback arrived."), probe.notes())
    }

    @Test
    fun statesAreDistinctInFirstSeenOrder() {
        val probe = ServiceStateProbeAccumulator()
        for (state in listOf(
            ServiceRegState.IN_SERVICE,
            ServiceRegState.OUT_OF_SERVICE,
            ServiceRegState.IN_SERVICE,
            ServiceRegState.POWER_OFF,
        )) {
            probe.onServiceState(snapshot(state, operatorNumeric = "311480"))
        }

        val result = probe.result()
        assertEquals(4, result.snapshots)
        assertEquals(listOf("IN_SERVICE", "OUT_OF_SERVICE", "POWER_OFF"), result.states)
        assertTrue(result.operatorNumericPresent)
        assertTrue(probe.notes().isEmpty())
    }

    @Test
    fun aBlankOperatorCodeDoesNotCount() {
        val probe = ServiceStateProbeAccumulator()
        probe.onServiceState(snapshot(ServiceRegState.IN_SERVICE, operatorNumeric = null))
        probe.onServiceState(snapshot(ServiceRegState.IN_SERVICE, operatorNumeric = " "))

        assertFalse(probe.result().operatorNumericPresent)
        assertEquals(listOf("Service state never carried an operator code."), probe.notes())
    }

    @Test
    fun emergencyOnlyComesFromTheFlagOrTheState() {
        val flagged = ServiceStateProbeAccumulator()
        flagged.onServiceState(snapshot(ServiceRegState.OUT_OF_SERVICE, emergencyOnly = true, operatorNumeric = "311480"))
        assertTrue(flagged.result().emergencyOnlySeen)
        assertTrue(flagged.notes().contains("Service was emergency-only at least once."))

        val stated = ServiceStateProbeAccumulator()
        stated.onServiceState(snapshot(ServiceRegState.EMERGENCY_ONLY, emergencyOnly = false, operatorNumeric = "311480"))
        assertTrue(stated.result().emergencyOnlySeen)

        val normal = ServiceStateProbeAccumulator()
        normal.onServiceState(snapshot(ServiceRegState.IN_SERVICE, operatorNumeric = "311480"))
        assertFalse(normal.result().emergencyOnlySeen)
    }

    private fun snapshot(
        state: ServiceRegState,
        emergencyOnly: Boolean = false,
        operatorNumeric: String?,
    ): ServiceStateSnapshot = ServiceStateSnapshot(
        state = state,
        emergencyOnly = emergencyOnly,
        operatorNumeric = operatorNumeric,
        operatorAlphaLong = null,
        roaming = null,
        observedWallMs = 1_789_050_600_000L,
        observedElapsedMs = 80_000L,
    )
}
