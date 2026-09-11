package com.fieldtap.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.fieldtap.R
import com.fieldtap.app.SessionSummary
import com.fieldtap.core.session.ExitReasons
import com.fieldtap.core.session.StopCause
import com.fieldtap.ui.components.SessionRowStatus

/** What `summary.stopped_by` says about how a session ended. */
enum class StopKind {
    /** The running session. */
    RECORDING,

    /** Stop on the Live screen or in the notification. */
    USER,

    /** The app stopped it: storage full, precise location revoked, the service destroyed. */
    APP_STOP,

    /** Launch recovery closed it with Android's exit reason, or a token this version does not know. */
    ANDROID_EXIT,

    /** Still open on disk (`recording` or no value) but not the running session. */
    NOT_CLOSED,
}

/** [kind] with the token it came from, for the wording. */
data class StopDescription(val kind: StopKind, val token: String?)

/**
 * The rules for describing a session's end, pure so they are unit-tested. The words come from
 * [stopReasonText].
 *
 * Owner: workstream `ui-session`.
 */
object StopReasons {
    /** The stop tokens the app itself writes, other than `user`. */
    val APP_STOP_TOKENS: Set<String> = setOf(
        StopCause.STORAGE_FULL.token,
        StopCause.PERMISSION_REVOKED.token,
        StopCause.SERVICE_DESTROYED.token,
    )

    /** How a session with [stoppedBy] ended; [recording] wins over any token. */
    fun describe(stoppedBy: String?, recording: Boolean = false): StopDescription = when {
        recording -> StopDescription(StopKind.RECORDING, stoppedBy)
        stoppedBy == null || stoppedBy == ExitReasons.RECORDING -> StopDescription(StopKind.NOT_CLOSED, stoppedBy)
        stoppedBy == StopCause.USER.token -> StopDescription(StopKind.USER, stoppedBy)
        stoppedBy in APP_STOP_TOKENS -> StopDescription(StopKind.APP_STOP, stoppedBy)
        else -> StopDescription(StopKind.ANDROID_EXIT, stoppedBy)
    }

    /** The badge of a Sessions row: recording, unreadable, completed by a user or app stop, else interrupted. */
    fun rowStatus(summary: SessionSummary): SessionRowStatus = when {
        summary.recording -> SessionRowStatus.RECORDING
        !summary.readable -> SessionRowStatus.UNREADABLE
        else -> when (describe(summary.stoppedBy).kind) {
            StopKind.USER, StopKind.APP_STOP -> SessionRowStatus.COMPLETED
            StopKind.RECORDING, StopKind.ANDROID_EXIT, StopKind.NOT_CLOSED -> SessionRowStatus.INTERRUPTED
        }
    }

    /** Stop minus start, or null when either is unknown or they are out of order. */
    fun durationMs(startedUtcMs: Long?, stoppedUtcMs: Long?): Long? =
        if (startedUtcMs != null && stoppedUtcMs != null && stoppedUtcMs >= startedUtcMs) stoppedUtcMs - startedUtcMs else null
}

/** The phrase for an Android exit-reason token (`ExitReasons.token`), or null for a token Android does not define. */
@StringRes
fun exitReasonRes(token: String): Int? = when (token) {
    "unknown" -> R.string.exit_unknown
    "exit_self" -> R.string.exit_exit_self
    "signaled" -> R.string.exit_signaled
    "low_memory" -> R.string.exit_low_memory
    "crash" -> R.string.exit_crash
    "crash_native" -> R.string.exit_crash_native
    "anr" -> R.string.exit_anr
    "initialization_failure" -> R.string.exit_initialization_failure
    "permission_change" -> R.string.exit_permission_change
    "excessive_resource_usage" -> R.string.exit_excessive_resource_usage
    "user_requested" -> R.string.exit_user_requested
    "user_stopped" -> R.string.exit_user_stopped
    "dependency_died" -> R.string.exit_dependency_died
    "other" -> R.string.exit_other
    "freezer" -> R.string.exit_freezer
    "package_state_change" -> R.string.exit_package_state_change
    "package_updated" -> R.string.exit_package_updated
    else -> null
}

/** One line on how a session ended: "Stopped by you", "Interrupted by Android: low memory". */
@Composable
fun stopReasonText(description: StopDescription): String = when (description.kind) {
    StopKind.RECORDING -> stringResource(R.string.stop_recording)
    StopKind.USER -> stringResource(R.string.stop_user)
    StopKind.NOT_CLOSED -> stringResource(R.string.stop_not_closed)
    StopKind.APP_STOP -> when (description.token) {
        StopCause.STORAGE_FULL.token -> stringResource(R.string.stop_storage_full)
        StopCause.PERMISSION_REVOKED.token -> stringResource(R.string.stop_permission_revoked)
        else -> stringResource(R.string.stop_service_destroyed)
    }
    StopKind.ANDROID_EXIT -> {
        val token = description.token.orEmpty()
        val words = exitReasonRes(token)
        if (words != null) {
            stringResource(R.string.stop_android_exit, stringResource(words))
        } else {
            stringResource(R.string.stop_other, token)
        }
    }
}

/** The phrase for an exit-reason token ("low memory"), or the token itself when Android defines none. */
@Composable
fun exitReasonWords(token: String): String {
    val words = exitReasonRes(token)
    return if (words != null) stringResource(words) else token
}
