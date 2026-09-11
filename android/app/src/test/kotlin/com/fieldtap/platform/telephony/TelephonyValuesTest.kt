package com.fieldtap.platform.telephony

import android.telephony.AccessNetworkConstants
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import com.fieldtap.core.input.DataConnState
import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.RadioListener
import com.fieldtap.core.input.ServiceRegState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelephonyValuesTest {

    @Test
    fun literalConstantsMatchTheSdk() {
        assertEquals(ServiceState.STATE_IN_SERVICE, TelephonyValues.STATE_IN_SERVICE)
        assertEquals(ServiceState.STATE_OUT_OF_SERVICE, TelephonyValues.STATE_OUT_OF_SERVICE)
        assertEquals(ServiceState.STATE_EMERGENCY_ONLY, TelephonyValues.STATE_EMERGENCY_ONLY)
        assertEquals(ServiceState.STATE_POWER_OFF, TelephonyValues.STATE_POWER_OFF)
        assertEquals(TelephonyManager.DATA_UNKNOWN, TelephonyValues.DATA_UNKNOWN)
        assertEquals(TelephonyManager.DATA_DISCONNECTED, TelephonyValues.DATA_DISCONNECTED)
        assertEquals(TelephonyManager.DATA_CONNECTING, TelephonyValues.DATA_CONNECTING)
        assertEquals(TelephonyManager.DATA_CONNECTED, TelephonyValues.DATA_CONNECTED)
        assertEquals(TelephonyManager.DATA_SUSPENDED, TelephonyValues.DATA_SUSPENDED)
        assertEquals(TelephonyManager.DATA_DISCONNECTING, TelephonyValues.DATA_DISCONNECTING)
        assertEquals(TelephonyManager.DATA_HANDOVER_IN_PROGRESS, TelephonyValues.DATA_HANDOVER_IN_PROGRESS)
        assertEquals(NetworkRegistrationInfo.SERVICE_TYPE_EMERGENCY, TelephonyValues.SERVICE_TYPE_EMERGENCY)
        assertEquals(AccessNetworkConstants.TRANSPORT_TYPE_WWAN, TelephonyValues.TRANSPORT_TYPE_WWAN)
        assertEquals(TelephonyManager.CellInfoCallback.ERROR_TIMEOUT, TelephonyValues.ERROR_TIMEOUT)
        assertEquals(TelephonyManager.CellInfoCallback.ERROR_MODEM_ERROR, TelephonyValues.ERROR_MODEM_ERROR)
        assertEquals(TelephonyManager.NETWORK_TYPE_UNKNOWN, TelephonyValues.NETWORK_TYPE_UNKNOWN)
    }

    @Test
    fun serviceStatesMapByName() {
        assertEquals(ServiceRegState.IN_SERVICE, TelephonyValues.regState(0))
        assertEquals(ServiceRegState.OUT_OF_SERVICE, TelephonyValues.regState(1))
        assertEquals(ServiceRegState.EMERGENCY_ONLY, TelephonyValues.regState(2))
        assertEquals(ServiceRegState.POWER_OFF, TelephonyValues.regState(3))
        assertEquals(ServiceRegState.UNKNOWN, TelephonyValues.regState(4))
        assertEquals(ServiceRegState.UNKNOWN, TelephonyValues.regState(-1))
    }

    @Test
    fun emergencyOnlyComesFromTheStateOrFromUnregisteredEmergencyService() {
        // getState() says so.
        assertTrue(TelephonyValues.emergencyOnly(2, wwanRegistered = false, wwanEmergencyAvailable = false))
        // The SIM-less phone: out of service, nothing registered, emergency calls available.
        assertTrue(TelephonyValues.emergencyOnly(1, wwanRegistered = false, wwanEmergencyAvailable = true))
        // Registered service also lists emergency calls: not emergency-only.
        assertFalse(TelephonyValues.emergencyOnly(0, wwanRegistered = true, wwanEmergencyAvailable = true))
        assertFalse(TelephonyValues.emergencyOnly(1, wwanRegistered = true, wwanEmergencyAvailable = true))
        // No service at all.
        assertFalse(TelephonyValues.emergencyOnly(1, wwanRegistered = false, wwanEmergencyAvailable = false))
        assertFalse(TelephonyValues.emergencyOnly(3, wwanRegistered = false, wwanEmergencyAvailable = false))
    }

    @Test
    fun roamingIsUnknownOutOfService() {
        assertEquals(true, TelephonyValues.roaming(0, true))
        assertEquals(false, TelephonyValues.roaming(0, false))
        assertNull(TelephonyValues.roaming(1, true))
        assertNull(TelephonyValues.roaming(2, false))
    }

    @Test
    fun dataStatesMapByName() {
        val expected = mapOf(
            -1 to DataConnState.UNKNOWN,
            0 to DataConnState.DISCONNECTED,
            1 to DataConnState.CONNECTING,
            2 to DataConnState.CONNECTED,
            3 to DataConnState.SUSPENDED,
            4 to DataConnState.DISCONNECTING,
            5 to DataConnState.HANDOVER_IN_PROGRESS,
            6 to DataConnState.UNKNOWN,
            99 to DataConnState.UNKNOWN,
        )
        for ((raw, state) in expected) assertEquals("DATA $raw", state, TelephonyValues.dataConnState(raw))
    }

    @Test
    fun blankTextBecomesNullAndOtherTextIsKept() {
        assertNull(TelephonyValues.text(null))
        assertNull(TelephonyValues.text(""))
        assertNull(TelephonyValues.text("   "))
        assertEquals("T-Mobile", TelephonyValues.text("T-Mobile"))
        assertEquals("010", TelephonyValues.text(StringBuilder("010")))
    }

    @Test
    fun buildFieldsDropAndroidsUnknownPlaceholder() {
        assertNull(TelephonyValues.buildValue(null))
        assertNull(TelephonyValues.buildValue(""))
        assertNull(TelephonyValues.buildValue("unknown"))
        assertEquals("Pixel 8", TelephonyValues.buildValue("Pixel 8"))
        assertEquals("Unknown", TelephonyValues.buildValue("Unknown"))
    }

    @Test
    fun additionalPlmnsAreCleanDistinctAndSorted() {
        assertEquals(listOf("310260", "311480"), TelephonyValues.plmns(listOf("311480", "", null, "310260", "311480", " ")))
        assertTrue(TelephonyValues.plmns(emptySet()).isEmpty())
    }

    @Test
    fun networkTypeNamesUseTheFallbackWhenTheReadIsRefusedOrUnknown() {
        assertEquals("LTE", TelephonyValues.networkTypeName(13, null))
        assertEquals("LTE", TelephonyValues.networkTypeName(13, 20))
        assertEquals("NR", TelephonyValues.networkTypeName(0, 20))
        assertEquals("NR", TelephonyValues.networkTypeName(null, 20))
        assertNull(TelephonyValues.networkTypeName(null, null))
        assertNull(TelephonyValues.networkTypeName(0, 0))
        assertNull(TelephonyValues.networkTypeName(99, null))
    }

    @Test
    fun securityExceptionsForRestrictedListenersArePlatformRefusals() {
        val privileged = "listen: uid 10123 does not have android.permission.READ_PRECISE_PHONE_STATE."
        for (listener in listOf(
            RadioListener.PHYSICAL_CHANNEL_CONFIG,
            RadioListener.BARRING_INFO,
            RadioListener.REGISTRATION_FAILED,
        )) {
            assertEquals(ListenerOutcome.REFUSED_BY_PLATFORM, TelephonyValues.outcomeForSecurityException(listener, privileged))
            assertEquals(ListenerOutcome.REFUSED_BY_PLATFORM, TelephonyValues.outcomeForSecurityException(listener, null))
        }
        assertEquals(
            ListenerOutcome.REFUSED_BY_PLATFORM,
            TelephonyValues.outcomeForSecurityException(RadioListener.CELL_INFO_PUSH, privileged),
        )
    }

    @Test
    fun securityExceptionsForOrdinaryListenersAreMissingPermissions() {
        val location = "uid 10123 does not have android.permission.ACCESS_FINE_LOCATION."
        assertEquals(
            ListenerOutcome.MISSING_PERMISSION,
            TelephonyValues.outcomeForSecurityException(RadioListener.CELL_INFO_PUSH, location),
        )
        assertEquals(
            ListenerOutcome.MISSING_PERMISSION,
            TelephonyValues.outcomeForSecurityException(RadioListener.CELL_INFO_REQUEST, null),
        )
    }

    @Test
    fun securityDetailsNamePermissionsAndNothingElse() {
        val message = "com.fieldtap uid 10123 pid 4567: requires android.permission.READ_PHONE_STATE " +
            "or android.permission.ACCESS_FINE_LOCATION; android.permission.READ_PHONE_STATE"

        val detail = TelephonyValues.securityDetail(message)
        assertEquals("requires READ_PHONE_STATE and ACCESS_FINE_LOCATION", detail)
        assertFalse(detail.contains("10123"))
        assertFalse(detail.contains("com.fieldtap"))
        assertEquals("permission denied", TelephonyValues.securityDetail("not allowed"))
        assertEquals("permission denied", TelephonyValues.securityDetail(null))
    }

    @Test
    fun failureDetailsAreTrimmedAndCut() {
        assertEquals("IllegalStateException: telephony service is null",
            TelephonyValues.failureDetail("IllegalStateException", " telephony service is null "))
        assertEquals("IllegalStateException", TelephonyValues.failureDetail("IllegalStateException", null))
        assertEquals("IllegalStateException", TelephonyValues.failureDetail("IllegalStateException", "  "))
        val long = TelephonyValues.failureDetail("E", "x".repeat(500))
        assertEquals("E: " + "x".repeat(120), long)
    }

    @Test
    fun cellInfoErrorsAreNamed() {
        assertEquals("timeout", TelephonyValues.cellInfoErrorDetail(1, null))
        assertEquals("modem error: RADIO_NOT_AVAILABLE", TelephonyValues.cellInfoErrorDetail(2, "RADIO_NOT_AVAILABLE"))
        assertEquals("error 7", TelephonyValues.cellInfoErrorDetail(7, " "))
    }
}
