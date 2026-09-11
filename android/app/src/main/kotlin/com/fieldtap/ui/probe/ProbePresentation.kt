package com.fieldtap.ui.probe

import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.RadioListener
import com.fieldtap.core.probe.ProbeNotes
import com.fieldtap.platform.Permissions
import com.fieldtap.ui.theme.Formats
import com.fieldtap.ui.theme.StatusTone
import java.util.Locale

/** A listener outcome as the Probe screen words it, with the tone of its value. */
internal enum class ListenerWord(val tone: StatusTone) {
    REGISTERED(StatusTone.SUCCESS),
    MISSING_PERMISSION(StatusTone.WARNING),

    /** Refused although an ordinary app should get it. */
    REFUSED(StatusTone.ERROR),

    /** One of the three listeners that need READ_PRECISE_PHONE_STATE, refused as every ordinary app is. */
    REFUSED_EXPECTED(StatusTone.NEUTRAL),
    NOT_REGISTERED(StatusTone.WARNING),
    FAILED(StatusTone.ERROR),
}

/** An answer that can also be "not enough data". */
internal enum class ProbeAnswer { YES, NO, NOT_ENOUGH }

/** The permissions the probe reports, by the words the screen uses. */
internal enum class ProbePermissionLabel { PRECISE_LOCATION, APPROXIMATE_LOCATION, NOTIFICATIONS, PHONE, OTHER }

/**
 * The Probe screen's decisions, pure so they are unit-tested. The findings themselves come from
 * `com.fieldtap.core.probe`; this only decides how the report's values are worded and toned.
 *
 * Owner: workstream `ui-setup`.
 */
internal object ProbePresentation {
    fun listenerWord(listener: RadioListener, outcome: ListenerOutcome): ListenerWord {
        val restricted = listener in ProbeNotes.RESTRICTED
        return when (outcome) {
            ListenerOutcome.REGISTERED -> ListenerWord.REGISTERED
            ListenerOutcome.MISSING_PERMISSION -> ListenerWord.MISSING_PERMISSION
            ListenerOutcome.REFUSED_BY_PLATFORM -> if (restricted) ListenerWord.REFUSED_EXPECTED else ListenerWord.REFUSED
            ListenerOutcome.UNREGISTERED -> if (restricted) ListenerWord.REFUSED_EXPECTED else ListenerWord.NOT_REGISTERED
            ListenerOutcome.FAILED -> ListenerWord.FAILED
        }
    }

    /** Every listener in [RadioListener] order; one the report does not name counts as never registered. */
    fun listenerRows(listeners: Map<RadioListener, ListenerOutcome>): List<Pair<RadioListener, ListenerOutcome>> =
        RadioListener.entries.map { listener -> listener to (listeners[listener] ?: ListenerOutcome.UNREGISTERED) }

    fun timestamps(advance: Boolean?): ProbeAnswer = when (advance) {
        true -> ProbeAnswer.YES
        false -> ProbeAnswer.NO
        null -> ProbeAnswer.NOT_ENOUGH
    }

    /** The range between [min] and [max], or null unless both are known. */
    fun range(min: Int?, max: Int?): IntRange? {
        if (min == null || max == null) return null
        return if (min <= max) min..max else max..min
    }

    /** Milliseconds as seconds with one decimal, for "30.0 s". */
    fun seconds(ms: Long, locale: Locale = Locale.getDefault()): String = Formats.oneDecimal(ms / MS_PER_SECOND, locale)

    fun permissionLabel(name: String): ProbePermissionLabel = when (name) {
        Permissions.FINE_LOCATION -> ProbePermissionLabel.PRECISE_LOCATION
        Permissions.COARSE_LOCATION -> ProbePermissionLabel.APPROXIMATE_LOCATION
        Permissions.POST_NOTIFICATIONS -> ProbePermissionLabel.NOTIFICATIONS
        Permissions.READ_PHONE_STATE -> ProbePermissionLabel.PHONE
        else -> ProbePermissionLabel.OTHER
    }

    /** The values joined with a middle dot, or null when there are none. */
    fun joined(values: List<String>): String? = values.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)

    private const val MS_PER_SECOND = 1000.0
    private const val SEPARATOR = " · "
}
