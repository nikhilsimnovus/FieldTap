package com.fieldtap.debug

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.fieldtap.app.SessionStatus
import com.fieldtap.app.StartResult
import com.fieldtap.app.appGraph
import com.fieldtap.core.nettest.TestSettings
import com.fieldtap.core.privacy.Consent
import com.fieldtap.core.session.SessionOutcome
import com.fieldtap.core.session.StartRequest
import com.fieldtap.format.JsonText
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 * `am start -W` returns once the activity is shown, before START has waited for Recording or STOP for
 * Idle. The result file is removed when an action begins and written atomically when it ends, so a harness
 * waits until the file exists (or for the logcat line) and never reads the previous action's result. An
 * action cut short because Android destroyed the activity still writes a result, with error `interrupted`.
 *
 * Extras may be given with any `am start` flag: `--ez tests true` and `--es tests true` both work, as do
 * `--el`, `--ei` and `--es` for numbers. A value that cannot be read as its type fails the action with
 * `invalid_<extra>` instead of silently running with a default.
 *
 * START also accepts overrides of the test settings (android/ARCHITECTURE.md decision 6), so the emulator
 * run can ping `10.0.2.2` and download 1 MB: `--es ping_target 10.0.2.2 --el ping_interval_ms 15000
 * --ei ping_count 3 --es download_url https://... --el download_interval_ms 60000
 * --el download_cap_bytes 1000000 --el session_budget_bytes 5000000`. They are saved to settings before
 * the start, as if typed in Settings; an empty `ping_target` or `download_url` turns that test off.
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

    /** String, optional for START: the ping target saved to settings; empty turns ping off. */
    const val EXTRA_PING_TARGET: String = "ping_target"

    /** Long, optional for START: milliseconds between pings. */
    const val EXTRA_PING_INTERVAL_MS: String = "ping_interval_ms"

    /** Int, optional for START: echoes per ping test. */
    const val EXTRA_PING_COUNT: String = "ping_count"

    /** String, optional for START: the HTTPS download URL saved to settings; empty turns the download off. */
    const val EXTRA_DOWNLOAD_URL: String = "download_url"

    /** Long, optional for START: milliseconds between downloads. */
    const val EXTRA_DOWNLOAD_INTERVAL_MS: String = "download_interval_ms"

    /** Long, optional for START: the most bytes one download reads. */
    const val EXTRA_DOWNLOAD_CAP_BYTES: String = "download_cap_bytes"

    /** Long, optional for START: the download bytes one session may use. */
    const val EXTRA_SESSION_BUDGET_BYTES: String = "session_budget_bytes"

    const val DEFAULT_TIMEOUT_MS: Long = 20_000
    const val LOG_TAG: String = "FieldTapAutomation"
    const val RESULT_FILE: String = "automation/last-result.json"

    /** [AutomationResult.action] values. */
    const val RESULT_START: String = "start"
    const val RESULT_MARK: String = "mark"
    const val RESULT_STOP: String = "stop"
    const val RESULT_UNKNOWN: String = "unknown"

    /** [AutomationResult.error] values besides the `StartRefusal` names. */
    const val ERROR_TIMEOUT: String = "timeout"
    const val ERROR_NOT_RECORDING: String = "not_recording"
    const val ERROR_PAUSED: String = "paused"
    const val ERROR_START_FAILED: String = "start_failed"
    const val ERROR_STOPPED_WHILE_STARTING: String = "stopped_while_starting"
    const val ERROR_MISSING_NAME: String = "missing_name"
    const val ERROR_UNKNOWN_ACTION: String = "unknown_action"
    const val ERROR_INTERRUPTED: String = "interrupted"
    const val ERROR_EXCEPTION: String = "exception"

    /** Followed by the extra's name: its value is unreadable or out of range, for example `invalid_timeout_ms`. */
    const val ERROR_INVALID_PREFIX: String = "invalid_"
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
    /**
     * One line of JSON with the keys in contract order and Python's default separators (`", "` and
     * `": "`), strings escaped by [JsonText.quote]. No line break, so it is one logcat line.
     */
    fun toJson(): String = buildString {
        append("{\"action\": ").append(JsonText.quote(action))
        append(", \"ok\": ").append(if (ok) "true" else "false")
        append(", \"dir_name\": ").append(dirName?.let { JsonText.quote(it) } ?: "null")
        append(", \"error\": ").append(error?.let { JsonText.quote(it) } ?: "null")
        append('}')
    }
}

/**
 * Test-setting overrides for START (android/ARCHITECTURE.md decision 6). A null field keeps the stored value.
 * Intervals, the ping count and the cap must be positive and the budget not negative; [problem] names the
 * first extra that is not, so a typo fails the action instead of silently running with defaults.
 */
data class TestOverrides(
    val pingTarget: String? = null,
    val pingIntervalMs: Long? = null,
    val pingCount: Int? = null,
    val downloadUrl: String? = null,
    val downloadIntervalMs: Long? = null,
    val downloadCapBytes: Long? = null,
    val sessionBudgetBytes: Long? = null,
) {
    val isEmpty: Boolean get() = this == NONE

    /** `invalid_<extra>` for the first out-of-range value, else null. */
    fun problem(): String? = when {
        pingIntervalMs != null && pingIntervalMs <= 0 -> invalid(AutomationContract.EXTRA_PING_INTERVAL_MS)
        pingCount != null && pingCount <= 0 -> invalid(AutomationContract.EXTRA_PING_COUNT)
        downloadIntervalMs != null && downloadIntervalMs <= 0 -> invalid(AutomationContract.EXTRA_DOWNLOAD_INTERVAL_MS)
        downloadCapBytes != null && downloadCapBytes <= 0 -> invalid(AutomationContract.EXTRA_DOWNLOAD_CAP_BYTES)
        sessionBudgetBytes != null && sessionBudgetBytes < 0 -> invalid(AutomationContract.EXTRA_SESSION_BUDGET_BYTES)
        downloadUrl != null && downloadUrl.isNotEmpty() && !downloadUrl.startsWith("https://") ->
            invalid(AutomationContract.EXTRA_DOWNLOAD_URL)
        else -> null
    }

    /** [tests] with every non-null override applied; an empty download URL becomes null (download off). */
    fun applyTo(tests: TestSettings): TestSettings = tests.copy(
        pingTarget = pingTarget?.trim() ?: tests.pingTarget,
        pingIntervalMs = pingIntervalMs ?: tests.pingIntervalMs,
        pingCount = pingCount ?: tests.pingCount,
        downloadUrl = if (downloadUrl != null) downloadUrl.trim().ifEmpty { null } else tests.downloadUrl,
        downloadIntervalMs = downloadIntervalMs ?: tests.downloadIntervalMs,
        downloadCapBytes = downloadCapBytes ?: tests.downloadCapBytes,
        sessionBudgetBytes = sessionBudgetBytes ?: tests.sessionBudgetBytes,
    )

    private fun invalid(extra: String): String = AutomationContract.ERROR_INVALID_PREFIX + extra

    companion object {
        val NONE: TestOverrides = TestOverrides()
    }
}

/** What START was asked to do. [problem] is the first unreadable extra (`invalid_<extra>`), else null. */
internal data class StartArguments(
    val name: String,
    val note: String?,
    val location: String?,
    val tests: Boolean,
    val acceptConsent: Boolean,
    val markReady: Boolean,
    val timeoutMs: Long,
    val overrides: TestOverrides,
    val problem: String?,
)

/** What STOP was asked to do. [problem] is `invalid_timeout_ms` when the timeout is unreadable, else null. */
internal data class StopArguments(val timeoutMs: Long, val problem: String?)

/**
 * Reads the hook's extras from the raw values `am start` put in the intent, whichever flag carried them, so
 * no typed `Intent` getter logs a ClassCastException for a mismatched type. An extra that is present but
 * cannot be read as its type becomes the action's problem, never a silent default. Pure apart from [of], so
 * it is unit-tested.
 */
internal object AutomationExtras {
    /** The extras of [bundle] by name; empty when there are none. */
    fun of(bundle: Bundle?): Map<String, Any?> {
        if (bundle == null) return emptyMap()
        return bundle.keySet().associateWith { key ->
            // The type-safe getters need the type up front, and the hook accepts every flag for a value.
            @Suppress("DEPRECATION")
            bundle.get(key)
        }
    }

    /** START's arguments from [extras]; absent booleans are false, the timeout defaults, overrides stay null. */
    fun start(extras: Map<String, Any?>): StartArguments {
        val read = Reader(extras)
        val name = read.text(AutomationContract.EXTRA_NAME).orEmpty()
        val note = read.text(AutomationContract.EXTRA_NOTE)
        val location = read.text(AutomationContract.EXTRA_LOCATION)
        val tests = read.flag(AutomationContract.EXTRA_TESTS)
        val acceptConsent = read.flag(AutomationContract.EXTRA_ACCEPT_CONSENT)
        val markReady = read.flag(AutomationContract.EXTRA_MARK_READY)
        val timeoutMs = read.number(AutomationContract.EXTRA_TIMEOUT_MS) ?: AutomationContract.DEFAULT_TIMEOUT_MS
        val overrides = TestOverrides(
            pingTarget = read.text(AutomationContract.EXTRA_PING_TARGET),
            pingIntervalMs = read.number(AutomationContract.EXTRA_PING_INTERVAL_MS),
            // Clamped into Int, so a huge value stays positive and a hugely negative one fails TestOverrides.problem().
            pingCount = read.number(AutomationContract.EXTRA_PING_COUNT)?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt(),
            downloadUrl = read.text(AutomationContract.EXTRA_DOWNLOAD_URL),
            downloadIntervalMs = read.number(AutomationContract.EXTRA_DOWNLOAD_INTERVAL_MS),
            downloadCapBytes = read.number(AutomationContract.EXTRA_DOWNLOAD_CAP_BYTES),
            sessionBudgetBytes = read.number(AutomationContract.EXTRA_SESSION_BUDGET_BYTES),
        )
        return StartArguments(name, note, location, tests, acceptConsent, markReady, timeoutMs, overrides, read.problem)
    }

    /** STOP's arguments from [extras]. */
    fun stop(extras: Map<String, Any?>): StopArguments {
        val read = Reader(extras)
        val timeoutMs = read.number(AutomationContract.EXTRA_TIMEOUT_MS) ?: AutomationContract.DEFAULT_TIMEOUT_MS
        return StopArguments(timeoutMs, read.problem)
    }

    /** MARK's note from [extras], or null. */
    fun note(extras: Map<String, Any?>): String? = Reader(extras).text(AutomationContract.EXTRA_NOTE)

    /** A Boolean, or a String saying `true` or `false` in any case; else null. */
    fun booleanOf(raw: Any?): Boolean? = when (raw) {
        is Boolean -> raw
        is String -> when (raw.trim().lowercase(Locale.ROOT)) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }

    /** A Long, Int, Short or Byte, or a String holding a whole number; else null. */
    fun longOf(raw: Any?): Long? = when (raw) {
        is Long -> raw
        is Int -> raw.toLong()
        is Short -> raw.toLong()
        is Byte -> raw.toLong()
        is String -> raw.trim().toLongOrNull()
        else -> null
    }

    /** Reads typed values from one set of extras and remembers the first one that could not be read. */
    private class Reader(private val extras: Map<String, Any?>) {
        var problem: String? = null
            private set

        /** The value as text (a number given with `--ei` is used as its digits); null when absent. */
        fun text(key: String): String? = when (val raw = extras[key]) {
            null -> null
            is String -> raw
            else -> raw.toString()
        }

        /** False when absent; the value when readable; false and a problem otherwise. */
        fun flag(key: String): Boolean {
            if (!extras.containsKey(key)) return false
            return booleanOf(extras[key]) ?: run {
                fail(key)
                false
            }
        }

        /** Null when absent; the value when readable; null and a problem otherwise. */
        fun number(key: String): Long? {
            if (!extras.containsKey(key)) return null
            return longOf(extras[key]) ?: run {
                fail(key)
                null
            }
        }

        private fun fail(key: String) {
            if (problem == null) problem = AutomationContract.ERROR_INVALID_PREFIX + key
        }
    }
}

/**
 * The operations behind the hook, through the same `AppGraph.sessionControl` the Live screen uses.
 *
 * - [start]: optionally records consent and readiness, calls `SessionControl.start`, waits up to
 *   [timeoutMs] for `SessionStatus.Recording`.
 * - [mark]: `SessionControl.mark(note)`; `ok` false with `not_recording` or `paused`.
 * - [stop]: `SessionControl.stop()`, waits up to [timeoutMs] for `SessionStatus.Idle`; `dir_name` from
 *   `lastOutcome`.
 * - [report]: logcat line and result file. [clearResult]: removes the result file when an action begins.
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
        overrides: TestOverrides = TestOverrides.NONE,
    ): AutomationResult {
        val start = AutomationContract.RESULT_START
        if (name.isBlank()) return AutomationResult(start, false, null, AutomationContract.ERROR_MISSING_NAME)
        overrides.problem()?.let { return AutomationResult(start, false, null, it) }
        val graph = context.appGraph
        if (acceptConsent || markReady || !overrides.isEmpty) {
            val nowMs = graph.clock.wallMillis()
            graph.settings.update { current ->
                current.copy(
                    consent = if (acceptConsent) Consent.record(nowMs) else current.consent,
                    readinessLastRunUtcMs = if (markReady) nowMs else current.readinessLastRunUtcMs,
                    tests = overrides.applyTo(current.tests),
                )
            }
        }
        val control = graph.sessionControl
        val result = control.start(StartRequest(name = name.trim(), note = note, location = location, testsEnabled = tests))
        if (result is StartResult.Refused) return AutomationResult(start, false, null, result.refusal.name)
        // start() applies the state machine before returning, so Idle right now means the start already failed.
        if (control.status.value == SessionStatus.Idle) {
            return AutomationResult(start, false, null, AutomationContract.ERROR_START_FAILED)
        }
        val settled = withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) {
            control.status.first { it is SessionStatus.Recording || it is SessionStatus.Stopping || it is SessionStatus.Idle }
        }
        return when (settled) {
            is SessionStatus.Recording -> AutomationResult(start, true, settled.snapshot.dirName, null)
            is SessionStatus.Stopping ->
                AutomationResult(start, false, settled.snapshot.dirName, AutomationContract.ERROR_STOPPED_WHILE_STARTING)
            is SessionStatus.Idle -> AutomationResult(start, false, null, AutomationContract.ERROR_START_FAILED)
            is SessionStatus.Starting, null -> AutomationResult(start, false, null, AutomationContract.ERROR_TIMEOUT)
        }
    }

    suspend fun mark(context: Context, note: String?): AutomationResult {
        val action = AutomationContract.RESULT_MARK
        val control = context.appGraph.sessionControl
        val status = control.status.value
        if (status !is SessionStatus.Recording) return AutomationResult(action, false, null, AutomationContract.ERROR_NOT_RECORDING)
        if (status.snapshot.paused) return AutomationResult(action, false, null, AutomationContract.ERROR_PAUSED)
        if (control.mark(note)) return AutomationResult(action, true, null, null)
        val now = control.status.value
        val error = if (now is SessionStatus.Recording && now.snapshot.paused) AutomationContract.ERROR_PAUSED else AutomationContract.ERROR_NOT_RECORDING
        return AutomationResult(action, false, null, error)
    }

    suspend fun stop(context: Context, timeoutMs: Long): AutomationResult {
        val action = AutomationContract.RESULT_STOP
        val control = context.appGraph.sessionControl
        val previousOutcome = control.lastOutcome.value
        val dirName = when (val status = control.status.value) {
            is SessionStatus.Recording -> status.snapshot.dirName
            is SessionStatus.Stopping -> status.snapshot.dirName
            is SessionStatus.Starting -> null
            is SessionStatus.Idle -> return AutomationResult(action, false, previousOutcome?.dirName, AutomationContract.ERROR_NOT_RECORDING)
        }
        control.stop()
        val outcome: SessionOutcome? = withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) {
            combine(control.status, control.lastOutcome) { status, last -> if (status == SessionStatus.Idle) last else null }
                .first { last -> last != null && last !== previousOutcome && (dirName == null || last.dirName == dirName) }
        }
        return if (outcome != null) {
            AutomationResult(action, true, outcome.dirName, null)
        } else {
            AutomationResult(action, false, dirName, AutomationContract.ERROR_TIMEOUT)
        }
    }

    /** Writes [result] as one logcat line and replaces the result file atomically. Blocking IO: call it off the main thread. */
    fun report(context: Context, result: AutomationResult) {
        val json = result.toJson()
        Log.i(AutomationContract.LOG_TAG, json)
        val root = context.getExternalFilesDir(null)
        if (root == null) {
            Log.w(AutomationContract.LOG_TAG, "External app storage is unavailable; the result is only in logcat")
            return
        }
        val target = File(root, AutomationContract.RESULT_FILE)
        try {
            val directory = target.parentFile ?: throw IOException("No parent directory for ${target.path}")
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Could not create ${directory.path}")
            }
            val temporary = File(directory, target.name + ".tmp")
            temporary.writeText(json + "\n", Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            Log.w(AutomationContract.LOG_TAG, "Could not write ${target.path}", e)
        }
    }

    /**
     * Removes the previous action's result file, so while an action runs the file cannot be mistaken for its
     * result. Blocking IO: call it off the main thread.
     */
    fun clearResult(context: Context) {
        val root = context.getExternalFilesDir(null) ?: return
        val target = File(root, AutomationContract.RESULT_FILE)
        try {
            Files.deleteIfExists(target.toPath())
        } catch (e: IOException) {
            Log.w(AutomationContract.LOG_TAG, "Could not remove ${target.path}", e)
        }
    }
}

/** Translucent, no UI: reads the intent, runs one [DebugAutomation] action, reports, finishes. */
class AutomationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = intent
        val actionName = resultActionOf(request?.action)
        if (savedInstanceState != null) {
            // Recreated mid-action: the first instance's work was cancelled, and repeating it could start or
            // mark twice. Report that instead.
            lifecycleScope.launch { finishWith(AutomationResult(actionName, false, null, AutomationContract.ERROR_INTERRUPTED)) }
            return
        }
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { DebugAutomation.clearResult(applicationContext) }
                run(request)
            } catch (e: CancellationException) {
                // Destroyed mid-action (for example recreated, or finished by the system): leave a result anyway.
                withContext(NonCancellable + Dispatchers.IO) {
                    DebugAutomation.report(applicationContext, AutomationResult(actionName, false, null, AutomationContract.ERROR_INTERRUPTED))
                }
                throw e
            } catch (e: Exception) {
                Log.w(AutomationContract.LOG_TAG, "Automation action failed", e)
                AutomationResult(actionName, false, null, AutomationContract.ERROR_EXCEPTION)
            }
            finishWith(result)
        }
    }

    private suspend fun run(request: Intent?): AutomationResult {
        val context = applicationContext
        val extras = AutomationExtras.of(request?.extras)
        return when (request?.action) {
            AutomationContract.ACTION_START -> {
                val arguments = AutomationExtras.start(extras)
                val problem = arguments.problem
                if (problem != null) {
                    AutomationResult(AutomationContract.RESULT_START, false, null, problem)
                } else {
                    DebugAutomation.start(
                        context = context,
                        name = arguments.name,
                        note = arguments.note,
                        location = arguments.location,
                        tests = arguments.tests,
                        acceptConsent = arguments.acceptConsent,
                        markReady = arguments.markReady,
                        timeoutMs = arguments.timeoutMs,
                        overrides = arguments.overrides,
                    )
                }
            }
            AutomationContract.ACTION_MARK -> DebugAutomation.mark(context, AutomationExtras.note(extras))
            AutomationContract.ACTION_STOP -> {
                val arguments = AutomationExtras.stop(extras)
                val problem = arguments.problem
                if (problem != null) {
                    AutomationResult(AutomationContract.RESULT_STOP, false, null, problem)
                } else {
                    DebugAutomation.stop(context, arguments.timeoutMs)
                }
            }
            else -> AutomationResult(AutomationContract.RESULT_UNKNOWN, false, null, AutomationContract.ERROR_UNKNOWN_ACTION)
        }
    }

    /** Writes [result] even if the activity is being destroyed, then finishes. */
    private suspend fun finishWith(result: AutomationResult) {
        withContext(NonCancellable + Dispatchers.IO) { DebugAutomation.report(applicationContext, result) }
        finish()
    }

    private fun resultActionOf(action: String?): String = when (action) {
        AutomationContract.ACTION_START -> AutomationContract.RESULT_START
        AutomationContract.ACTION_MARK -> AutomationContract.RESULT_MARK
        AutomationContract.ACTION_STOP -> AutomationContract.RESULT_STOP
        else -> AutomationContract.RESULT_UNKNOWN
    }
}
