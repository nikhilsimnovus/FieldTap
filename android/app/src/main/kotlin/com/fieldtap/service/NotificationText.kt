package com.fieldtap.service

import com.fieldtap.app.SessionStatus
import com.fieldtap.app.SoakState
import com.fieldtap.core.session.RecorderSnapshot
import com.fieldtap.format.ServingRat
import java.util.Locale

/**
 * What the session notification shows, decided without Android so it can be tested on a JVM.
 *
 * Owner: workstream `service-and-tests`.
 */
internal sealed interface NotificationModel {
    /** Nothing runs: the service is on its way out. */
    data object None : NotificationModel

    data object Starting : NotificationModel

    data class Recording(val snapshot: RecorderSnapshot, val notice: MarkNotice? = null) : NotificationModel

    data class Soak(val elapsedMs: Long, val durationMs: Long) : NotificationModel

    companion object {
        /** [notice] only on a recording session's notification; a session that is saving says so instead. */
        fun of(status: SessionStatus, soak: SoakState, notice: MarkNotice? = null): NotificationModel = when (status) {
            is SessionStatus.Starting -> Starting
            is SessionStatus.Recording -> Recording(status.snapshot, notice)
            is SessionStatus.Stopping -> Recording(status.snapshot.copy(stopping = true))
            is SessionStatus.Idle -> if (soak is SoakState.Running) Soak(soak.elapsedMs, soak.durationMs) else None
        }
    }
}

/**
 * What the session notification says about markers for a few seconds, instead of the state's own words. It never
 * carries a marker's note: the notification shows on the lock screen.
 *
 * Owner: workstream `service-and-tests`.
 */
internal sealed interface MarkNotice {
    /** The session it is about: a notice never shows on another session's notification. */
    val dirName: String

    /** Marker [number] of the session, counting from 1, was accepted at [wallMs] and is written. */
    data class Added(override val dirName: String, val number: Int, val wallMs: Long) : MarkNotice

    /** Marker [number] was accepted while inputs wait for a location fix: it is written once one shows where it was tapped. */
    data class Held(override val dirName: String, val number: Int) : MarkNotice

    /** [count] markers accepted earlier were just dropped: no fix showed they were tapped outside the privacy zones. */
    data class Dropped(override val dirName: String, val count: Int) : MarkNotice
}

/** The first line of a recording notification. */
internal enum class RecordingHeadline { RECORDING, PAUSED, WAITING_FOR_LOCATION, LOCATION_OFF, SAVING }

/**
 * Text pieces of the session notification. Words come from string resources; numbers are formatted here.
 *
 * Owner: workstream `service-and-tests`.
 */
internal object NotificationText {
    /**
     * Saving wins; then location off, because nothing at all is measured then; then waiting for a location fix
     * (privacy zones set and no fix shows where the phone is); then paused in a zone; else recording.
     */
    fun headline(snapshot: RecorderSnapshot): RecordingHeadline = when {
        snapshot.stopping -> RecordingHeadline.SAVING
        !snapshot.locationEnabled -> RecordingHeadline.LOCATION_OFF
        snapshot.waitingForLocation -> RecordingHeadline.WAITING_FOR_LOCATION
        snapshot.paused -> RecordingHeadline.PAUSED
        else -> RecordingHeadline.RECORDING
    }

    /** The marker notice the text shows instead of the headline's words: [notice] about this session, unless it is saving. */
    fun shownNotice(snapshot: RecorderSnapshot, notice: MarkNotice?): MarkNotice? =
        notice?.takeIf { it.dirName == snapshot.dirName && headline(snapshot) != RecordingHeadline.SAVING }

    fun ratLabel(rat: ServingRat): String = when (rat) {
        ServingRat.LTE -> "LTE"
        ServingRat.NR -> "NR"
    }

    /** A sample age: `0.4 s` below 10 s (one decimal, in [locale]), whole seconds below 2 min, then whole minutes. */
    fun age(ageMs: Long, locale: Locale): String {
        val ms = ageMs.coerceAtLeast(0)
        return when {
            ms < 9_950 -> String.format(locale, "%.1f s", ms / 1000.0)
            ms < 120_000 -> "${(ms + 500) / 1000} s"
            else -> "${ms / 60_000} min"
        }
    }

    /** A duration as `m:ss`, for the soak test's progress. */
    fun minutesSeconds(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }
}
