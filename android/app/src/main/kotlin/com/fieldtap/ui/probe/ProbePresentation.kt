package com.fieldtap.ui.probe

import com.fieldtap.core.capability.CaptureAnswer
import com.fieldtap.core.capability.CaptureVerdict
import com.fieldtap.core.capability.CapabilitySnapshot
import com.fieldtap.core.capability.CapabilityVerdict
import com.fieldtap.core.capability.CellularReadout
import com.fieldtap.core.capability.Layer3OnDevice
import com.fieldtap.core.capability.RootConfidence
import com.fieldtap.core.capability.RootProbeResult
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
 * The Probe screen's decisions, pure so they are unit-tested. The findings and the capability verdict
 * sentences themselves come from `com.fieldtap.core.probe` and `com.fieldtap.core.capability`; this only
 * decides how the report's values are worded and toned, and folds the passive capability snapshot with an
 * optional root-check result. Honesty is the feature: layer-3 being "not possible" on a phone is a normal,
 * neutral fact (grey), never an error, and this screen never implies the app decodes signalling.
 *
 * Owner: workstream `screens-setup`.
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

    // ---- Capability panel (the tiered "what this phone can capture" verdict + root/diag/USB) ----

    /**
     * The tiered verdict to show now: the passive snapshot's when no root check has run, otherwise the
     * snapshot folded with the root-check result (which decides the layer-3 answer). All the copy inside
     * lives in `com.fieldtap.core.capability.CapabilityMessages`.
     */
    fun verdict(snapshot: CapabilitySnapshot, rootProbe: RootProbeResult?): CaptureVerdict =
        if (rootProbe == null) {
            snapshot.verdict
        } else {
            CapabilityVerdict.withRootProbe(snapshot.root, snapshot.usb, snapshot.cellular, rootProbe)
        }

    /**
     * Whether the phone can measure a serving cell right now. Public-API measurement is always possible, but
     * it returns nothing without precise location and location services, so the top verdict chip warns when
     * either is missing. It never blocks anything: the tiers below still say the app can measure on any phone.
     */
    fun canMeasureNow(cellular: CellularReadout): Boolean =
        cellular.preciseLocationGranted && cellular.locationServicesEnabled

    /** Tier 1/2 dot tone: YES is good, NO is advice (a permission is missing), UNKNOWN is neutral. */
    fun captureAnswerTone(answer: CaptureAnswer): StatusTone = when (answer) {
        CaptureAnswer.YES -> StatusTone.SUCCESS
        CaptureAnswer.NO -> StatusTone.WARNING
        CaptureAnswer.UNKNOWN -> StatusTone.NEUTRAL
    }

    /**
     * Layer-3 dot tone. NOT_POSSIBLE is grey, not red: most phones (the lead's OnePlus included) simply have
     * no diag device, and that is a normal fact, not a fault. Only POSSIBLE earns a success dot.
     */
    fun layer3Tone(l3: Layer3OnDevice): StatusTone = when (l3) {
        Layer3OnDevice.POSSIBLE -> StatusTone.SUCCESS
        Layer3OnDevice.NOT_POSSIBLE -> StatusTone.NEUTRAL
        Layer3OnDevice.UNKNOWN -> StatusTone.NEUTRAL
    }

    /**
     * The layer-3 tier's detail line: the honest per-phone sentence, plus the USB-debugging line only when
     * capture is definitively NOT_POSSIBLE (so the "use a laptop over USB" advice comes with whether ADB is
     * ready). For UNKNOWN (root likely but untested) the USB line would be noise, so it is left off.
     */
    fun layer3Detail(verdict: CaptureVerdict): String {
        val line = verdict.lines.getOrNull(TIER_LAYER3).orEmpty()
        val laptop = verdict.laptopPath?.takeIf { verdict.layer3Signalling == Layer3OnDevice.NOT_POSSIBLE }
        return listOfNotNull(line.ifEmpty { null }, laptop).joinToString(" ")
    }

    fun tier1Detail(verdict: CaptureVerdict): String = verdict.lines.getOrNull(TIER_PUBLIC_API).orEmpty()

    fun tier2Detail(verdict: CaptureVerdict): String = verdict.lines.getOrNull(TIER_PUSH).orEmpty()

    /**
     * The passive root confidence stays calm: HIGH/MEDIUM are informative (cobalt), LOW/NONE neutral. The
     * confidence word is the cue, not a loud colour; the always-present caveat keeps "no root" from reading
     * as proof.
     */
    fun rootConfidenceTone(confidence: RootConfidence): StatusTone = when (confidence) {
        RootConfidence.HIGH, RootConfidence.MEDIUM -> StatusTone.INFO
        RootConfidence.LOW, RootConfidence.NONE -> StatusTone.NEUTRAL
    }

    private const val TIER_PUBLIC_API = 0
    private const val TIER_PUSH = 1
    private const val TIER_LAYER3 = 2
    private const val MS_PER_SECOND = 1000.0
    private const val SEPARATOR = " · "
}
