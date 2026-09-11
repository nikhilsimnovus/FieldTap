package com.fieldtap.platform

import android.Manifest
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class PermissionsTest {

    @Test
    fun namesAreAndroidsPermissionStrings() {
        assertEquals(Manifest.permission.ACCESS_FINE_LOCATION, Permissions.FINE_LOCATION)
        assertEquals(Manifest.permission.ACCESS_COARSE_LOCATION, Permissions.COARSE_LOCATION)
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, Permissions.POST_NOTIFICATIONS)
        assertEquals(Manifest.permission.READ_PHONE_STATE, Permissions.READ_PHONE_STATE)
    }

    @Test
    fun locationIsRequestedAsFineAndCoarseTogether() {
        assertEquals(listOf(Permissions.FINE_LOCATION, Permissions.COARSE_LOCATION), Permissions.LOCATION)
    }

    @Test
    fun everyCheckedPermissionIsDeclared() {
        val declared = declaredPermissions()

        for (name in listOf(
            Permissions.FINE_LOCATION,
            Permissions.COARSE_LOCATION,
            Permissions.POST_NOTIFICATIONS,
            Permissions.READ_PHONE_STATE,
        )) {
            assertTrue("$name is not declared in the manifest", name in declared)
        }
    }

    @Test
    fun noPermissionThatReadsIdentifiersOrRunsInTheBackgroundIsDeclared() {
        val declared = declaredPermissions()

        for (name in listOf(
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.READ_PRECISE_PHONE_STATE",
            "android.permission.READ_PRIVILEGED_PHONE_STATE",
            "android.permission.READ_PHONE_NUMBERS",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.WAKE_LOCK",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        )) {
            assertFalse("$name must not be declared", name in declared)
        }
    }

    private fun declaredPermissions(): Set<String> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val nodes = factory.newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
            .getElementsByTagName("uses-permission")
        return (0 until nodes.length)
            .map { (nodes.item(it) as Element).getAttributeNS(ANDROID_NAMESPACE, "name") }
            .toSet()
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
