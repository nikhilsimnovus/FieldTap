package com.fieldtap.e2e

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldtap.core.capability.CapabilityJson
import com.fieldtap.core.capability.CapabilityReports
import com.fieldtap.core.capability.CapabilityVerdict
import com.fieldtap.core.capability.CaptureAnswer
import com.fieldtap.core.capability.DiagDevice
import com.fieldtap.core.capability.Layer3OnDevice
import com.fieldtap.core.capability.RootDetector
import com.fieldtap.core.capability.SuStatus
import com.fieldtap.platform.telephony.HandsetInfoReader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The capability/root/diag/USB-debugging detection, exercised on the CI emulator and asserted for honesty (the
 * capability spec's §0 stance). It drives the app's real [com.fieldtap.platform.capability.CapabilityInspector]
 * facade through `AppGraph.capability` — the same object the Capability screen binds — so nothing is faked:
 *
 *  1. The passive read (no su call): USB debugging must be reported ON (the emulator has `adb_enabled=1`), the
 *     developer-options state is read, the passive root signals carry the always-present root-hiding caveat, and
 *     the tiered verdict is Public-API = YES with Push-updates = NO (READ_PHONE_STATE is not granted).
 *  2. The explicit "Check with root": whatever the emulator's `su` allows, the outcome is asserted to be honest
 *     and self-consistent — never POSSIBLE, because this image genuinely has no `/dev/diag` (proved by a shell
 *     `ls`). When `su` grants root, `/dev/diag` is ABSENT and the layer-3 verdict is "not possible on this phone"
 *     with the laptop-over-USB path; when `su` is denied/absent/times out, the honest UNKNOWN/NOT_POSSIBLE path
 *     holds instead.
 *  3. The `fieldtap-capability/1` JSON export is written (via the same [CapabilityReports.build] +
 *     [CapabilityJson] path the screen uses) to `e2e/capability.json`, with a flat `e2e/capability-result.json`
 *     of the facts, both pulled by android/e2e/run_e2e.sh and asserted by check_e2e.py.
 *
 * The test gains no root and runs no exploit; it reads `/dev/diag` and `getenforce` only to record the device's
 * real state for the assertions. No identifier is read anywhere.
 */
@RunWith(AndroidJUnit4::class)
class CapabilityProbeTest {
    @Test
    fun detectsCapabilityRootDiagAndUsbDebuggingHonestly() {
        val capability = E2e.graph.capability
        val snapshot = runBlocking { capability.passive() }
        val probe = runBlocking { capability.checkWithRoot() }
        val folded = CapabilityVerdict.withRootProbe(snapshot.root, snapshot.usb, snapshot.cellular, probe)

        // What the device really is, read directly, so the honesty assertions rest on facts, not on the app.
        val diagLs = E2e.shell("ls -l /dev/diag").trim()
        val diagAbsentOnDevice = diagLs.contains("No such file", ignoreCase = true) ||
            diagLs.contains("cannot access", ignoreCase = true)
        val adbEnabledSetting = E2e.shell("settings get global adb_enabled").trim()
        val developerOptionsSetting = E2e.shell("settings get global development_settings_enabled").trim()
        val phoneGranted = E2e.granted(READ_PHONE_STATE)

        // ---- (1) The passive read ----
        // USB debugging is on: this is a live emulator, adb_enabled is 1, and the inspector must report it.
        assertEquals("adb_enabled read from Settings should be 1 on the emulator", "1", adbEnabledSetting)
        assertTrue("USB debugging (adb_enabled) must be reported ON on the emulator", snapshot.usb.adbEnabled)
        // The developer-options state is read; the inspector must agree with the raw Settings value.
        val developerOptionsOn = developerOptionsSetting == "1"
        assertEquals("developer-options state must match Settings", developerOptionsOn, snapshot.usb.developerOptionsEnabled)
        // The passive root signals carry the always-present root-hiding caveat.
        assertEquals("the root-hiding caveat must always be present", RootDetector.CAVEAT, snapshot.root.caveat)
        assertTrue("the caveat says a no-root reading is not proof", snapshot.root.caveat.contains("not proof"))
        // The tiered verdict: public-API measurements are always yes; push updates need the Phone permission.
        assertEquals(CaptureAnswer.YES, snapshot.verdict.publicApiMeasurements)
        assertFalse("READ_PHONE_STATE must not be granted for this check (push updates = needs Phone)", phoneGranted)
        assertEquals(CaptureAnswer.NO, snapshot.verdict.pushCellUpdates)

        // ---- (2) The explicit root check, asserted for honesty ----
        // The inspector's folded layer-3 answer must equal the probe's own.
        assertEquals(probe.layer3, folded.layer3Signalling)
        // This emulator image has no /dev/diag, so capture can never be POSSIBLE and the node is never PRESENT.
        assertTrue("the emulator must genuinely have no /dev/diag; ls said: $diagLs", diagAbsentOnDevice)
        assertNotEquals("layer-3 must never be POSSIBLE without a diag device", Layer3OnDevice.POSSIBLE, probe.layer3)
        assertNotEquals("the diag node must never be PRESENT on this image", DiagDevice.PRESENT, probe.diagDevice)
        when (probe.suStatus) {
            SuStatus.GRANTED ->
                if (probe.isRoot) {
                    // Rooted, but the kernel has no diag node: the honest "not possible on this phone" verdict.
                    assertEquals("a rooted phone with no /dev/diag must read ABSENT", DiagDevice.ABSENT, probe.diagDevice)
                    assertEquals(Layer3OnDevice.NOT_POSSIBLE, probe.layer3)
                    assertNotNull("NOT_POSSIBLE must offer the laptop-over-USB path", folded.laptopPath)
                } else {
                    // su ran but did not yield uid=0: no working root, so capture is not possible.
                    assertEquals(Layer3OnDevice.NOT_POSSIBLE, probe.layer3)
                }
            SuStatus.NOT_PRESENT -> assertEquals(Layer3OnDevice.NOT_POSSIBLE, probe.layer3)
            SuStatus.DENIED, SuStatus.TIMED_OUT, SuStatus.ERROR ->
                assertEquals("an untested device is honestly UNKNOWN, not a claim", Layer3OnDevice.UNKNOWN, probe.layer3)
        }
        // Whenever layer-3 is not definitively possible, the verdict offers the laptop-over-USB path.
        assertNotNull("the laptop-over-USB path must be shown when layer-3 is not possible", folded.laptopPath)

        // ---- (3) The fieldtap-capability/1 export, the real build + encode path ----
        val handset = HandsetInfoReader(E2e.context).read()
        val report = CapabilityReports.build(
            createdUtcMs = E2e.graph.clock.wallMillis(),
            appVersion = E2e.graph.appInfo.versionName,
            versionCode = E2e.graph.appInfo.versionCode,
            sdkInt = Build.VERSION.SDK_INT,
            handset = handset,
            snapshot = snapshot,
            rootProbe = probe,
        )
        val json = CapabilityJson.encode(report)
        File(E2e.outputDir(), CAPABILITY_JSON).writeText(json, Charsets.UTF_8)

        E2e.writeResult(
            "capability-result.json",
            linkedMapOf(
                "adb_enabled" to snapshot.usb.adbEnabled,
                "wireless_debug_enabled" to snapshot.usb.wirelessDebugEnabled,
                "developer_options_enabled" to snapshot.usb.developerOptionsEnabled,
                "adb_enabled_setting" to adbEnabledSetting,
                "developer_options_setting" to developerOptionsSetting,
                "root_confidence" to snapshot.root.confidence.name,
                "root_caveat" to snapshot.root.caveat,
                "caveat_matches" to (snapshot.root.caveat == RootDetector.CAVEAT),
                "su_status" to probe.suStatus.name,
                "is_root" to probe.isRoot,
                "selinux" to probe.selinux.name,
                "diag_device" to probe.diagDevice.name,
                "kernel_diag" to probe.kernelDiag.name,
                "layer3" to probe.layer3.name,
                "root_message" to probe.message,
                "public_api" to snapshot.verdict.publicApiMeasurements.name,
                "push_updates" to folded.pushCellUpdates.name,
                "layer3_verdict" to folded.layer3Signalling.name,
                "laptop_path" to (folded.laptopPath ?: ""),
                "read_phone_state_granted" to phoneGranted,
                "device_diag_ls" to diagLs,
                "diag_absent_on_device" to diagAbsentOnDevice,
                "capability_json" to CAPABILITY_JSON,
                "elapsed_ms" to probe.elapsedMs,
            ),
        )
    }

    private companion object {
        const val READ_PHONE_STATE = "android.permission.READ_PHONE_STATE"
        const val CAPABILITY_JSON = "capability.json"
    }
}
