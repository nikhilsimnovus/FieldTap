package com.fieldtap.debug

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * The debug-only automation hook for the end-to-end test. Exists only in `src/debug`; release builds
 * have neither these classes nor the manifest entry.
 *
 * From adb, with the app installed and permissions granted (`adb install -g`, or `pm grant` for
 * ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION and POST_NOTIFICATIONS):
 * ```
 * adb shell am start -W -n <applicationId>/com.fieldtap.debug.AutomationActivity \
 *     -a com.fieldtap.debug.START_SESSION --es name e2e-walk --ez accept_consent true --ez mark_ready true
 * adb shell am start -W -n <applicationId>/com.fieldtap.debug.AutomationActivity \
 *     -a com.fieldtap.debug.MARK --es note "checkpoint 1"
 * adb shell am start -W -n <applicationId>/com.fieldtap.debug.AutomationActivity \
 *     -a com.fieldtap.debug.STOP_SESSION
 * ```
 * `am start` brings the (translucent) activity to the foreground, which is what lets it start the
 * location foreground service. Each action writes one [AutomationResult] as a single logcat line
 * (tag [AutomationContract.LOG_TAG], message the JSON) and to
 * `<getExternalFilesDir(null)>/automation/last-result.json`
 * (`/sdcard/Android/data/<applicationId>/files/automation/last-result.json`), then finishes. The
 * session directory is `/sdcard/Android/data/<applicationId>/files/sessions/<dirName>`.
 *
 * From an instrumentation test: launch the activity with the same intent via `ActivityScenario`, or
 * call [DebugAutomation] directly from a coroutine while an activity of the app is resumed.
 *
 * Owner: workstream `ui-session`.
 */
object AutomationContract {
    const val ACTION_START: String = "com.fieldtap.debug.START_SESSION"
    const val ACTION_MARK: String = "com.fieldtap.debug.MARK"
    const val ACTION_STOP: String = "com.fieldtap.debug.STOP_SESSION"

    /** String, required for START: the session name. */
    const val EXTRA_NAME: String = "name"

    /** String, optional: session note (START) or marker note (MARK). */
    const val EXTRA_NOTE: String = "note"

    /** String, optional for START: place name. */
    const val EXTRA_LOCATION: String = "location"

    /** Boolean, default false: run ping and download tests. */
    const val EXTRA_TESTS: String = "tests"

    /** Boolean, default false: record consent to the current text first (debug only). */
    const val EXTRA_ACCEPT_CONSENT: String = "accept_consent"

    /** Boolean, default false: record the readiness check as run now (debug only). */
    const val EXTRA_MARK_READY: String = "mark_ready"

    /** Long, default [DEFAULT_TIMEOUT_MS]: how long START waits for Recording and STOP for Idle. */
    const val EXTRA_TIMEOUT_MS: String = "timeout_ms"

    const val DEFAULT_TIMEOUT_MS: Long = 20_000
    const val LOG_TAG: String = "FieldTapAutomation"
    const val RESULT_FILE: String = "automation/last-result.json"
}

/**
 * One action's result. JSON: `{"action": "start|mark|stop", "ok": true, "dir_name": "...", "error": null}`
 * rendered with `com.fieldtap.format.JsonText`. `dir_name` is the session directory for start and stop,
 * null for mark; `error` a short reason (a StartRefusal name, `timeout`, `not_recording`).
 */
data class AutomationResult(
    val action: String,
    val ok: Boolean,
    val dirName: String?,
    val error: String?,
) {
    fun toJson(): String = TODO("ui-session")
}

/**
 * The operations behind the hook, through the same `AppGraph.sessionControl` the Live screen uses.
 *
 * - [start]: optionally records consent and readiness, calls `SessionControl.start`, waits up to
 *   [timeoutMs] for `SessionStatus.Recording`.
 * - [mark]: `SessionControl.mark(note)`; `ok` false with `not_recording` or `paused`.
 * - [stop]: `SessionControl.stop()`, waits up to [timeoutMs] for `SessionStatus.Idle`; `dir_name` from
 *   `lastOutcome`.
 * - [report]: logcat line and result file.
 *
 * Owner: workstream `ui-session`.
 */
object DebugAutomation {
    suspend fun start(
        context: Context,
        name: String,
        note: String?,
        location: String?,
        tests: Boolean,
        acceptConsent: Boolean,
        markReady: Boolean,
        timeoutMs: Long,
    ): AutomationResult = TODO("ui-session")

    suspend fun mark(context: Context, note: String?): AutomationResult = TODO("ui-session")

    suspend fun stop(context: Context, timeoutMs: Long): AutomationResult = TODO("ui-session")

    fun report(context: Context, result: AutomationResult): Unit = TODO("ui-session")
}

/** Translucent, no UI: reads the intent, runs one [DebugAutomation] action, reports, finishes. */
class AutomationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TODO("ui-session")
    }
}
