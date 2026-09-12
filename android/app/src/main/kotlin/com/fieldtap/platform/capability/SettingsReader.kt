package com.fieldtap.platform.capability

import android.content.Context
import android.provider.Settings
import com.fieldtap.core.capability.UsbDebugState

/**
 * Reads the USB-debugging and mock-location settings. None of these needs a runtime permission and none
 * touches an identifier. Values are read as ints and mapped to booleans by the pure helpers ([usbState],
 * [mockLocationSet]), JVM-tested; only [readUsb]/[readMockLocation] touch the platform.
 *
 * Owner: workstream `capability-core`.
 */
class SettingsReader(private val context: Context) {
    /** `adb_enabled`, `adb_wifi_enabled` (API 30+) and `development_settings_enabled`. */
    fun readUsb(): UsbDebugState {
        val resolver = context.contentResolver
        val adb = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0)
        val wifi = Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0)
        val dev = Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
        return usbState(adb, wifi, dev)
    }

    /** Whether a mock-location app is set. The app's name is never read. */
    fun readMockLocation(): Boolean =
        mockLocationSet(Settings.Secure.getInt(context.contentResolver, MOCK_LOCATION, 0))

    companion object {
        /** No `Settings.Global` constant for wireless debugging exists below API 30; the key is stable. */
        const val ADB_WIFI_ENABLED: String = "adb_wifi_enabled"

        /** `Settings.Secure.ALLOW_MOCK_LOCATION` (deprecated since API 23), the stable key. */
        const val MOCK_LOCATION: String = "mock_location"

        /** Maps the three settings ints to [UsbDebugState]; each is on when the value is exactly 1. */
        fun usbState(adbEnabled: Int, wirelessDebug: Int, developerOptions: Int): UsbDebugState =
            UsbDebugState(
                adbEnabled = adbEnabled == 1,
                wirelessDebugEnabled = wirelessDebug == 1,
                developerOptionsEnabled = developerOptions == 1,
            )

        /** A mock-location app is set when the setting is non-zero. */
        fun mockLocationSet(value: Int): Boolean = value != 0
    }
}
