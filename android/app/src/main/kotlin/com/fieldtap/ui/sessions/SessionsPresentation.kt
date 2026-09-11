package com.fieldtap.ui.sessions

import com.fieldtap.app.SessionDetail
import com.fieldtap.app.SessionStatus
import com.fieldtap.core.export.ExportException
import com.fieldtap.core.session.StorageStatus
import com.fieldtap.format.GapMeta
import com.fieldtap.ui.theme.StatusTone

/** The reasons [ExportState.Failed] carries: `ExportException.Reason` names, or [UNKNOWN]. */
object ExportFailure {
    const val SESSION_OPEN: String = "SESSION_OPEN"
    const val MISSING_SESSION_JSON: String = "MISSING_SESSION_JSON"
    const val TOO_LARGE: String = "TOO_LARGE"
    const val IO: String = "IO"
    const val UNKNOWN: String = "UNKNOWN"

    /** The reason of an [ExportException], else [UNKNOWN]. */
    fun of(error: Throwable): String = (error as? ExportException)?.reason?.name ?: UNKNOWN
}

/** Why a delete did not happen. */
enum class DeleteError {
    /** The session is recording; stop it first. */
    RECORDING,

    /** The repository refused or failed. */
    FAILED,
}

/** A `collection.gaps` reason token. */
enum class GapReason { APP_PAUSED, NO_SERVICE, SCREEN_OFF, UNKNOWN, OTHER }

/**
 * The decisions behind the Sessions and Session detail screens, pure so they are unit-tested.
 *
 * Owner: workstream `ui-session`.
 */
object SessionsPresentation {
    /** How many sampling gaps the detail screen lists before pointing to session.json. */
    const val MAX_LISTED_GAPS: Int = 50

    /** Above this share of the cap the Sessions list shows storage as a card with its bar, above the sessions. */
    const val STORAGE_CARD_FRACTION: Float = 0.8f

    /** Above this share of RSRP values below -105 dBm, in percent, Session detail draws the share in the warning tone. */
    const val BELOW_FAIR_WARNING_PCT: Double = 10.0

    /** Used bytes as a share of the cap, 0..1; a cap of zero or less counts as full. */
    fun storageFraction(storage: StorageStatus): Float {
        val cap = storage.policy.capBytes
        if (cap <= 0) return 1f
        return (storage.usedBytes.toDouble() / cap).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Whether storage needs the card with its bar: sessions cannot start, or more than [STORAGE_CARD_FRACTION] of the cap
     * is used. Otherwise one line under the list says how much is used and free.
     */
    fun storageCardShown(storage: StorageStatus): Boolean = !storage.canStart || storageFraction(storage) > STORAGE_CARD_FRACTION

    /** WARNING when more than [BELOW_FAIR_WARNING_PCT] of the RSRP values are below -105 dBm; otherwise no tone. */
    fun belowFairTone(belowFairPct: Double): StatusTone? = if (belowFairPct > BELOW_FAIR_WARNING_PCT) StatusTone.WARNING else null

    /** ERROR when sampling had any gap, since samples are missing for those seconds; otherwise no tone. */
    fun gapsTone(gaps: Int): StatusTone? = if (gaps > 0) StatusTone.ERROR else null

    /** True when [dirName] is the running (or stopping) session, by its summary or by the session status. */
    fun isRunning(dirName: String, detail: SessionDetail?, status: SessionStatus): Boolean {
        if (detail?.summary?.recording == true) return true
        return when (status) {
            is SessionStatus.Recording -> status.snapshot.dirName == dirName
            is SessionStatus.Stopping -> status.snapshot.dirName == dirName
            is SessionStatus.Idle, is SessionStatus.Starting -> false
        }
    }

    fun gapReason(token: String): GapReason = when (token) {
        "app_paused" -> GapReason.APP_PAUSED
        "no_service" -> GapReason.NO_SERVICE
        "screen_off" -> GapReason.SCREEN_OFF
        "unknown" -> GapReason.UNKNOWN
        else -> GapReason.OTHER
    }

    /** Ends the version of consent wording that has not been legally reviewed (android/ARCHITECTURE.md decision 2). */
    private const val DRAFT_SUFFIX: String = "-draft"

    /**
     * The consent version as the detail screen shows it: "2026-09-10" for "2026-09-10-draft". session.json keeps the
     * whole version; the suffix is a note for the team, not something a tester can act on.
     */
    fun consentVersionText(version: String): String = version.removeSuffix(DRAFT_SUFFIX).ifEmpty { version }

    /** The gaps to list, in time order, at most [limit]. */
    fun listedGaps(gaps: List<GapMeta>, limit: Int = MAX_LISTED_GAPS): List<GapMeta> =
        gaps.sortedBy { it.startUtcMs }.take(limit.coerceAtLeast(0))

    /**
     * A coarse key for the session phase, so a screen reloads when a session starts or ends but not on every
     * recorder snapshot.
     */
    fun phaseKey(status: SessionStatus): Int = when (status) {
        is SessionStatus.Idle -> 0
        is SessionStatus.Starting -> 1
        is SessionStatus.Recording -> 2
        is SessionStatus.Stopping -> 3
    }
}
