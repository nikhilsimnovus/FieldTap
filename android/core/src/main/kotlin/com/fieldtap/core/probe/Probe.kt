package com.fieldtap.core.probe

import com.fieldtap.core.input.CellInfoAnswer
import com.fieldtap.core.input.CellInfoRequestFailed
import com.fieldtap.core.input.CellSnapshot
import com.fieldtap.core.input.DisplayInfoSnapshot
import com.fieldtap.core.input.ListenerOutcome
import com.fieldtap.core.input.ListenerReport
import com.fieldtap.core.input.MeasurementInput
import com.fieldtap.core.input.RadioListener
import com.fieldtap.core.input.ServiceRegState
import com.fieldtap.core.input.ServiceStateSnapshot
import com.fieldtap.core.radio.NetworkTypeNames
import com.fieldtap.core.radio.ServingCellSelector
import com.fieldtap.format.HandsetMeta
import com.fieldtap.format.JsonArr
import com.fieldtap.format.JsonBool
import com.fieldtap.format.JsonInt
import com.fieldtap.format.JsonNode
import com.fieldtap.format.JsonNul
import com.fieldtap.format.JsonObj
import com.fieldtap.format.JsonStr
import com.fieldtap.format.JsonText
import com.fieldtap.format.Ranges
import com.fieldtap.format.ServingRat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.EnumMap
import java.util.Locale

/** What `requestCellInfoUpdate` and `CellInfoListener` returned during the probe. */
data class CellInfoProbe(
    /** `requestCellInfoUpdate` calls, plus the initial `getAllCellInfo` read. */
    val requests: Int,
    /** Answers from either source, empty ones included. */
    val answers: Int,
    /** Failed requests: `CellInfoCallback.onError`, or a call Android refused. */
    val errors: Int,
    val maxCellsPerAnswer: Int,
    /** An unregistered cell that was neither primary nor secondary serving was reported. */
    val neighboursSeen: Boolean,
    /** `rat` wire names of primary serving cells seen, in first-seen order. */
    val servingRats: List<String>,
    val bandListsPresent: Boolean,
    val connectionStatusReported: Boolean,
    /** An NR cell with connection status 2 was reported under an LTE primary. */
    val nsaSecondarySeen: Boolean,
    /**
     * True when the primary's `timestampMs` advanced at least once; null until at least two answers
     * carried a primary serving cell (fewer than two answers, or no serving cell to compare).
     */
    val timestampsAdvance: Boolean?,
    /** The smallest step between successive newer primary `timestampMs` values; null when none advanced. */
    val minFreshIntervalMs: Long?,
    /** Over every cell of every answer. */
    val rsrpMin: Int?,
    val rsrpMax: Int?,
    val sinrMin: Int?,
    val sinrMax: Int?,
)

data class ServiceStateProbe(
    val snapshots: Int,
    val operatorNumericPresent: Boolean,
    val emergencyOnlySeen: Boolean,
    /** `ServiceRegState` names seen. */
    val states: List<String>,
)

/**
 * The capability probe's result: what each API returns on this handset, exportable as JSON to
 * qualify pilot phones. Contains no subscriber or device identifier and no location.
 */
data class ProbeReport(
    val createdUtcMs: Long,
    val durationMs: Long,
    val appVersion: String,
    val versionCode: Long,
    val sdkInt: Int,
    val handset: HandsetMeta,
    /** Permission name -> granted. */
    val permissions: Map<String, Boolean>,
    val listeners: Map<RadioListener, ListenerOutcome>,
    val cellInfo: CellInfoProbe,
    val serviceState: ServiceStateProbe,
    /** Override network type names seen from the display-info listener. */
    val displayOverridesSeen: List<String>,
    val notes: List<String>,
) {
    companion object {
        const val FORMAT: String = "fieldtap-probe/1"
    }
}

/**
 * Pure accumulation of probe observations, fed by com.fieldtap.platform.probe.CapabilityProbeRunner through
 * [ProbeRecorder]. Not thread-safe on its own: [ProbeRecorder] serialises calls.
 *
 * - The primary and NSA secondary serving cells come from `com.fieldtap.core.radio.ServingCellSelector`, so the
 *   probe predicts what the recorder will do: the first cell with connection status 1, or, when no cell of the
 *   answer reports a status, the first registered LTE or NR cell; the NSA leg is an NR cell with status 2 under
 *   an LTE primary.
 * - A neighbour is an unregistered cell whose status is neither primary (1) nor secondary (2) serving.
 * - Ranges for [notes] are the schema's cellinfo ranges (`com.fieldtap.format.Ranges`): a value outside
 *   them is written blank by the recorder, so the note says so.
 *
 * Owner: workstream `platform-adapters`.
 */
class CellInfoProbeAccumulator {
    private var requests = 0
    private var answers = 0
    private var errors = 0
    private var emptyAnswers = 0
    private var maxCellsPerAnswer = 0
    private var neighboursSeen = false
    private val servingRats = LinkedHashSet<String>()
    private var bandListsPresent = false
    private var connectionStatusReported = false
    private var nsaSecondarySeen = false
    private var answersWithPrimary = 0
    private var newestPrimaryTimestampMs: Long? = null
    private var timestampAdvanced = false
    private var minFreshIntervalMs: Long? = null
    private var rsrpMin: Int? = null
    private var rsrpMax: Int? = null
    private var sinrMin: Int? = null
    private var sinrMax: Int? = null
    private val outOfRange = LinkedHashMap<String, OutOfRange>()

    /** One `requestCellInfoUpdate` call, or the initial `getAllCellInfo` read. */
    fun onRequest() {
        requests++
    }

    /** One failed request. */
    fun onError() {
        errors++
    }

    fun onAnswer(answer: CellInfoAnswer) {
        val cells = answer.cells
        answers++
        if (cells.isEmpty()) emptyAnswers++
        maxCellsPerAnswer = maxOf(maxCellsPerAnswer, cells.size)
        if (cells.any { it.bands.isNotEmpty() }) bandListsPresent = true
        if (cells.any { it.connectionStatus != null }) connectionStatusReported = true
        if (cells.any { isNeighbour(it) }) neighboursSeen = true

        val serving = ServingCellSelector.select(cells)
        val primary = serving.primary
        if (primary != null) {
            servingRats += primary.rat.wire
            if (serving.nsaSecondary != null) nsaSecondarySeen = true
            onPrimaryTimestamp(primary.timestampMs)
        }
        cells.forEach { onValues(it) }
    }

    fun result(): CellInfoProbe = CellInfoProbe(
        requests = requests,
        answers = answers,
        errors = errors,
        maxCellsPerAnswer = maxCellsPerAnswer,
        neighboursSeen = neighboursSeen,
        servingRats = servingRats.toList(),
        bandListsPresent = bandListsPresent,
        connectionStatusReported = connectionStatusReported,
        nsaSecondarySeen = nsaSecondarySeen,
        timestampsAdvance = if (answersWithPrimary < 2) null else timestampAdvanced,
        minFreshIntervalMs = minFreshIntervalMs,
        rsrpMin = rsrpMin,
        rsrpMax = rsrpMax,
        sinrMin = sinrMin,
        sinrMax = sinrMax,
    )

    /** Findings about the cell-info answers, one plain sentence each, in a fixed order. */
    fun notes(): List<String> = buildList {
        if (answers == 0) {
            add(
                if (requests == 0) {
                    "No cell information was requested."
                } else {
                    "No cell-info answer arrived for ${counted(requests, "request")}."
                },
            )
        }
        if (errors > 0) add("${counted(errors, "cell-info request")} failed.")
        if (emptyAnswers > 0) add("${counted(emptyAnswers, "answer")} held no cells.")
        if (answers > emptyAnswers) {
            if (servingRats.isEmpty()) add("No primary serving cell was identified, so no KPI rows would be written.")
            if (!connectionStatusReported) {
                add(
                    "No cell reported a connection status, so the serving cell is taken to be the first " +
                        "registered LTE or NR cell.",
                )
            }
            if (!neighboursSeen) add("No neighbour cells were reported.")
            if (!bandListsPresent) add("No cell reported a band list.")
        }
        if (answersWithPrimary >= 2 && !timestampAdvanced) {
            add("The serving cell's modem timestamp never advanced, so every answer after the first counts as a repeat.")
        }
        outOfRange.values.forEach { range ->
            add(
                "${range.label} outside ${range.range.first}..${range.range.last} ${range.unit} in " +
                    "${counted(range.count, "sample")} (first ${range.first}), so those values are written blank.",
            )
        }
    }

    private fun onPrimaryTimestamp(timestampMs: Long) {
        answersWithPrimary++
        val newest = newestPrimaryTimestampMs
        if (newest == null) {
            newestPrimaryTimestampMs = timestampMs
        } else if (timestampMs > newest) {
            timestampAdvanced = true
            val step = timestampMs - newest
            minFreshIntervalMs = minFreshIntervalMs?.let { minOf(it, step) } ?: step
            newestPrimaryTimestampMs = timestampMs
        }
    }

    private fun onValues(cell: CellSnapshot) {
        val rsrp = cell.rsrp
        if (rsrp != null) {
            rsrpMin = minOf(rsrpMin ?: rsrp, rsrp)
            rsrpMax = maxOf(rsrpMax ?: rsrp, rsrp)
        }
        val sinr = cell.sinr
        if (sinr != null) {
            sinrMin = minOf(sinrMin ?: sinr, sinr)
            sinrMax = maxOf(sinrMax ?: sinr, sinr)
        }
        val rat = cell.rat.servingRat ?: return
        checkRange("RSRP", "dBm", rat, cell.rsrp, Ranges.CELLINFO_RSRP)
        checkRange("RSRQ", "dB", rat, cell.rsrq, Ranges.CELLINFO_RSRQ)
        checkRange("SINR", "dB", rat, cell.sinr, Ranges.CELLINFO_SINR)
    }

    private fun checkRange(metric: String, unit: String, rat: ServingRat, value: Int?, ranges: Map<ServingRat, IntRange>) {
        if (value == null) return
        val range = ranges[rat] ?: return
        if (value in range) return
        val label = "${rat.name} $metric"
        val entry = outOfRange.getOrPut(label) { OutOfRange(label, range, unit, value) }
        entry.count++
    }

    private class OutOfRange(val label: String, val range: IntRange, val unit: String, val first: Int) {
        var count: Int = 0
    }

    private companion object {
        fun isNeighbour(cell: CellSnapshot): Boolean =
            !cell.registered &&
                cell.connectionStatus != CellSnapshot.CONNECTION_PRIMARY_SERVING &&
                cell.connectionStatus != CellSnapshot.CONNECTION_SECONDARY_SERVING
    }
}

/**
 * Pure accumulation of service-state observations for the probe. Not thread-safe on its own.
 *
 * Owner: workstream `platform-adapters`.
 */
class ServiceStateProbeAccumulator {
    private var snapshots = 0
    private var operatorNumericPresent = false
    private var emergencyOnlySeen = false
    private val states = LinkedHashSet<ServiceRegState>()

    fun onServiceState(state: ServiceStateSnapshot) {
        snapshots++
        if (!state.operatorNumeric.isNullOrBlank()) operatorNumericPresent = true
        if (ServingCellSelector.isEmergencyOnly(state)) emergencyOnlySeen = true
        states += state.state
    }

    fun result(): ServiceStateProbe = ServiceStateProbe(
        snapshots = snapshots,
        operatorNumericPresent = operatorNumericPresent,
        emergencyOnlySeen = emergencyOnlySeen,
        states = states.map { it.name },
    )

    /** Findings about service state, one plain sentence each. */
    fun notes(): List<String> = buildList {
        if (snapshots == 0) {
            add("No service-state callback arrived.")
            return@buildList
        }
        if (!operatorNumericPresent) add("Service state never carried an operator code.")
        if (emergencyOnlySeen) add("Service was emergency-only at least once.")
    }
}

/**
 * Everything the capability probe observes during one run, and the [ProbeReport] built from it. Fed by
 * com.fieldtap.platform.probe.CapabilityProbeRunner from two threads (the telephony adapter's request hook and
 * the flow's collector), so every call holds one lock.
 *
 * - [onRequest] counts one `requestCellInfoUpdate` call, or the initial `getAllCellInfo` read.
 * - [record] takes every input. Cell-info answers and failed requests go to [CellInfoProbeAccumulator], service
 *   states to [ServiceStateProbeAccumulator]. Display info adds its override name (distinct, in first-seen
 *   order) and becomes [lastDisplayNetworkType]. A listener report replaces that listener's outcome, so the
 *   newest wins. Signal strength and location inputs are ignored.
 * - [report] names all nine listeners in [RadioListener] order; one that never reported is
 *   [ListenerOutcome.UNREGISTERED]. Its notes are the cell-info notes, then the service-state notes, then the
 *   listener notes. It changes nothing, so it can be called again.
 *
 * Owner: workstream `platform-adapters`.
 */
class ProbeRecorder {
    private val lock = Any()
    private val cells = CellInfoProbeAccumulator()
    private val services = ServiceStateProbeAccumulator()
    private val listeners = EnumMap<RadioListener, ListenerOutcome>(RadioListener::class.java)
    private val overrides = LinkedHashSet<String>()
    private var answerCount = 0
    private var newestNetworkType: Int? = null

    fun onRequest() {
        synchronized(lock) { cells.onRequest() }
    }

    fun record(input: MeasurementInput) {
        synchronized(lock) { recordLocked(input) }
    }

    /** Cell-info answers recorded so far, for the progress text. */
    fun answers(): Int = synchronized(lock) { answerCount }

    /**
     * The newest `DisplayInfoSnapshot.networkType`, for `handset.network_type` when Android refuses its own
     * read without the Phone permission; null before any display info arrived.
     */
    fun lastDisplayNetworkType(): Int? = synchronized(lock) { newestNetworkType }

    fun report(
        createdUtcMs: Long,
        durationMs: Long,
        appVersion: String,
        versionCode: Long,
        sdkInt: Int,
        handset: HandsetMeta,
        permissions: Map<String, Boolean>,
    ): ProbeReport = synchronized(lock) {
        val outcomes: Map<RadioListener, ListenerOutcome> =
            RadioListener.entries.associateWith { listener -> listeners[listener] ?: ListenerOutcome.UNREGISTERED }
        ProbeReport(
            createdUtcMs = createdUtcMs,
            durationMs = durationMs,
            appVersion = appVersion,
            versionCode = versionCode,
            sdkInt = sdkInt,
            handset = handset,
            permissions = LinkedHashMap(permissions),
            listeners = outcomes,
            cellInfo = cells.result(),
            serviceState = services.result(),
            displayOverridesSeen = overrides.toList(),
            notes = cells.notes() + services.notes() + ProbeNotes.listeners(outcomes),
        )
    }

    private fun recordLocked(input: MeasurementInput) {
        when (input) {
            is CellInfoAnswer -> {
                cells.onAnswer(input)
                answerCount++
            }
            is CellInfoRequestFailed -> cells.onError()
            is ServiceStateSnapshot -> services.onServiceState(input)
            is DisplayInfoSnapshot -> {
                overrides.add(NetworkTypeNames.overrideType(input.overrideNetworkType))
                newestNetworkType = input.networkType
            }
            is ListenerReport -> {
                listeners[input.listener] = input.outcome
            }
            else -> Unit
        }
    }
}

/**
 * Plain-sentence notes about how the telephony listeners registered. The three restricted listeners
 * ([RESTRICTED]) are expected to be refused, so a refusal gives no note and a registration does.
 *
 * Owner: workstream `platform-adapters`.
 */
object ProbeNotes {
    /** The listeners that need READ_PRECISE_PHONE_STATE, which no ordinary app can hold. */
    val RESTRICTED: Set<RadioListener> = setOf(
        RadioListener.PHYSICAL_CHANNEL_CONFIG,
        RadioListener.BARRING_INFO,
        RadioListener.REGISTRATION_FAILED,
    )

    /** The Android API name engineers know the listener by. */
    fun apiName(listener: RadioListener): String = when (listener) {
        RadioListener.CELL_INFO_REQUEST -> "requestCellInfoUpdate"
        RadioListener.CELL_INFO_PUSH -> "CellInfoListener"
        RadioListener.SIGNAL_STRENGTHS -> "SignalStrengthsListener"
        RadioListener.SERVICE_STATE -> "ServiceStateListener"
        RadioListener.DISPLAY_INFO -> "DisplayInfoListener"
        RadioListener.DATA_CONNECTION_STATE -> "DataConnectionStateListener"
        RadioListener.PHYSICAL_CHANNEL_CONFIG -> "PhysicalChannelConfigListener"
        RadioListener.BARRING_INFO -> "BarringInfoListener"
        RadioListener.REGISTRATION_FAILED -> "RegistrationFailedListener"
    }

    /** One note per listener whose outcome is worth reading, in [RadioListener] order. */
    fun listeners(outcomes: Map<RadioListener, ListenerOutcome>): List<String> =
        RadioListener.entries.mapNotNull { listener -> outcomes[listener]?.let { noteFor(listener, it) } }

    private fun noteFor(listener: RadioListener, outcome: ListenerOutcome): String? {
        val name = apiName(listener)
        if (listener in RESTRICTED) {
            return when (outcome) {
                ListenerOutcome.REGISTERED -> "$name registered, although it normally needs READ_PRECISE_PHONE_STATE."
                ListenerOutcome.REFUSED_BY_PLATFORM, ListenerOutcome.UNREGISTERED -> null
                ListenerOutcome.MISSING_PERMISSION -> "$name was refused for a missing permission."
                ListenerOutcome.FAILED -> "$name could not be tried."
            }
        }
        return when (outcome) {
            ListenerOutcome.REGISTERED -> null
            ListenerOutcome.MISSING_PERMISSION -> if (listener == RadioListener.CELL_INFO_PUSH) {
                "$name is off: it needs the Phone permission (Instant cell updates) and precise location."
            } else {
                "$name did not run: a permission is missing."
            }
            ListenerOutcome.REFUSED_BY_PLATFORM -> "$name was refused by the platform."
            ListenerOutcome.UNREGISTERED -> "$name never registered."
            ListenerOutcome.FAILED -> "$name failed to register."
        }
    }
}

/**
 * Progress text for the Probe screen while it runs.
 *
 * Owner: workstream `platform-adapters`.
 */
object ProbeText {
    /** For example `Listening: 12 s of 30 s, 12 answers`. Elapsed rounds down, the duration up. */
    fun progress(elapsedMs: Long, durationMs: Long, answers: Int): String {
        val total = (durationMs.coerceAtLeast(0) + 999) / 1000
        val done = elapsedMs.coerceIn(0, durationMs.coerceAtLeast(0)) / 1000
        return "Listening: $done s of $total s, ${counted(answers, "answer")}"
    }
}

/**
 * The probe export: `{"format": "fieldtap-probe/1", ...}` rendered with `com.fieldtap.format.JsonText`,
 * keys snake_case in [ProbeReport] field order, enums by name.
 *
 * - `handset` leaves out keys with no value, as session.json does; every other nullable value is `null`.
 * - `listeners` is written in [RadioListener] order, `permissions` in the map's order.
 * - [fileName]: `probe-<manufacturer>-<model>-<yyyyMMdd-HHmmss of createdUtcMs, UTC>.json`, where each name
 *   keeps only `A-Z a-z 0-9 . _ -` (other runs become one `-`), is cut at 40 characters, and is `unknown`
 *   when nothing is left.
 *
 * Owner: workstream `platform-adapters`.
 */
object ProbeJson {
    private const val MAX_NAME_PART = 40
    private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]+")
    private val FILE_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun encode(report: ProbeReport): String = JsonText.render(tree(report))

    /** The JSON tree [encode] renders. */
    fun tree(report: ProbeReport): JsonObj = obj(
        "format" to JsonStr(ProbeReport.FORMAT),
        "created_utc_ms" to JsonInt(report.createdUtcMs),
        "duration_ms" to JsonInt(report.durationMs),
        "app_version" to JsonStr(report.appVersion),
        "version_code" to JsonInt(report.versionCode),
        "sdk_int" to JsonInt(report.sdkInt.toLong()),
        "handset" to handset(report.handset),
        "permissions" to JsonObj(report.permissions.map { (name, granted) -> name to JsonBool(granted) }),
        "listeners" to JsonObj(
            report.listeners.entries
                .sortedBy { it.key.ordinal }
                .map { (listener, outcome) -> listener.name to JsonStr(outcome.name) },
        ),
        "cell_info" to cellInfo(report.cellInfo),
        "service_state" to serviceState(report.serviceState),
        "display_overrides_seen" to JsonArr(report.displayOverridesSeen.map { JsonStr(it) }),
        "notes" to JsonArr(report.notes.map { JsonStr(it) }),
    )

    fun fileName(report: ProbeReport): String {
        val stamp = FILE_STAMP.format(Instant.ofEpochMilli(report.createdUtcMs))
        return "probe-${namePart(report.handset.manufacturer)}-${namePart(report.handset.model)}-$stamp.json"
    }

    private fun handset(handset: HandsetMeta): JsonObj = JsonObj(
        listOf(
            "manufacturer" to handset.manufacturer,
            "model" to handset.model,
            "device" to handset.device,
            "android_version" to handset.androidVersion,
            "android_build" to handset.androidBuild,
            "security_patch" to handset.securityPatch,
            "baseband" to handset.baseband,
            "soc" to handset.soc,
            "platform" to handset.platform,
            "hardware" to handset.hardware,
            "operator_mccmnc" to handset.operatorMccmnc,
            "operator_name" to handset.operatorName,
            "sim_mccmnc" to handset.simMccmnc,
            "sim_operator_name" to handset.simOperatorName,
            "network_type" to handset.networkType,
        ).mapNotNull { (key, value) -> value?.let { key to JsonStr(it) } },
    )

    private fun cellInfo(probe: CellInfoProbe): JsonObj = obj(
        "requests" to JsonInt(probe.requests.toLong()),
        "answers" to JsonInt(probe.answers.toLong()),
        "errors" to JsonInt(probe.errors.toLong()),
        "max_cells_per_answer" to JsonInt(probe.maxCellsPerAnswer.toLong()),
        "neighbours_seen" to JsonBool(probe.neighboursSeen),
        "serving_rats" to JsonArr(probe.servingRats.map { JsonStr(it) }),
        "band_lists_present" to JsonBool(probe.bandListsPresent),
        "connection_status_reported" to JsonBool(probe.connectionStatusReported),
        "nsa_secondary_seen" to JsonBool(probe.nsaSecondarySeen),
        "timestamps_advance" to boolOrNull(probe.timestampsAdvance),
        "min_fresh_interval_ms" to longOrNull(probe.minFreshIntervalMs),
        "rsrp_min" to intOrNull(probe.rsrpMin),
        "rsrp_max" to intOrNull(probe.rsrpMax),
        "sinr_min" to intOrNull(probe.sinrMin),
        "sinr_max" to intOrNull(probe.sinrMax),
    )

    private fun serviceState(probe: ServiceStateProbe): JsonObj = obj(
        "snapshots" to JsonInt(probe.snapshots.toLong()),
        "operator_numeric_present" to JsonBool(probe.operatorNumericPresent),
        "emergency_only_seen" to JsonBool(probe.emergencyOnlySeen),
        "states" to JsonArr(probe.states.map { JsonStr(it) }),
    )

    private fun obj(vararg members: Pair<String, JsonNode>): JsonObj = JsonObj(members.toList())

    private fun intOrNull(value: Int?): JsonNode = if (value == null) JsonNul else JsonInt(value.toLong())

    private fun longOrNull(value: Long?): JsonNode = if (value == null) JsonNul else JsonInt(value)

    private fun boolOrNull(value: Boolean?): JsonNode = if (value == null) JsonNul else JsonBool(value)

    private fun namePart(text: String?): String =
        text.orEmpty()
            .replace(UNSAFE_NAME_CHARS, "-")
            .trim('-')
            .take(MAX_NAME_PART)
            .trimEnd('-')
            .ifEmpty { "unknown" }
}

/** "1 answer", "2 answers". */
private fun counted(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"
