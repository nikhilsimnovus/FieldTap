package com.fieldtap.ui.live

import com.fieldtap.core.readiness.ReadinessCheck
import com.fieldtap.core.readiness.ReadinessLevel
import com.fieldtap.core.readiness.ReadinessReport
import com.fieldtap.core.readiness.SettingsTarget
import com.fieldtap.core.session.StartRefusal
import com.fieldtap.core.session.StartRequest

/**
 * Where the Start flow stands (android/ARCHITECTURE.md decision 7): tapping Start runs the readiness
 * checks, shows the pre-start sheet when they find something, and only then calls
 * `SessionControl.start`.
 */
sealed interface PrestartState {
    /** Nothing in progress. */
    data object None : PrestartState

    /** The readiness checks, consent and storage are being read for [request]. */
    data class Checking(val request: StartRequest) : PrestartState

    /** The pre-start sheet: [issues] were found for [request]; empty after a re-check that found none. */
    data class Review(val request: StartRequest, val issues: List<PrestartIssue>) : PrestartState {
        /** "Start anyway" is offered only when nothing blocks. */
        val canStartAnyway: Boolean get() = issues.none { it.blocking }
    }

    /** `SessionControl.start` is running for [request]. */
    data class Starting(val request: StartRequest) : PrestartState
}

/** A problem the pre-start sheet can name. */
enum class PrestartIssueKind {
    NO_CONSENT,
    NO_PRECISE_LOCATION,
    LOCATION_OFF,
    STORAGE_FULL,
    NOTIFICATIONS_OFF,
    BATTERY_OPTIMISATION,
    BACKGROUND_RESTRICTED,
    STANDBY_BUCKET,
    NO_SIM,
    WIFI_ON_BATTERY,

    /** The phone maker is known for killing background apps. */
    AGGRESSIVE_OEM,

    /** The readiness check itself failed; advice, never a block. */
    CHECK_FAILED,
}

/**
 * One problem on the pre-start sheet. [blocking] problems hide "Start anyway". [target] is the settings
 * screen the readiness check named, [SettingsTarget.NONE] when it named none.
 */
data class PrestartIssue(
    val kind: PrestartIssueKind,
    val blocking: Boolean,
    val target: SettingsTarget = SettingsTarget.NONE,
)

/**
 * Turns what Start found into the sheet's problems. Pure, so it is unit-tested.
 *
 * - Blocking: consent not current, no precise location, location off, storage full (the four
 *   `SessionStateMachine` refuses on), and any readiness item at level BLOCKER.
 * - Advice: every other readiness item that is not OK (the phone permission never is), a maker known for
 *   killing background apps, and a readiness check that could not run.
 * - A refusal from `SessionControl.start` adds its problem when the facts did not already show it.
 * - Each kind appears once; the result lists blocking problems first and otherwise keeps this order:
 *   consent, readiness items in check order, storage, the refusal, the maker, the failed check.
 *
 * Owner: workstream `ui-session`.
 */
object PrestartPlanner {
    /**
     * @param report the readiness report, or null when the check could not run.
     * @param consentCurrent whether the stored consent matches the current text; null when settings could not be read.
     * @param storageCanStart `StorageStatus.canStart`; null when storage could not be measured.
     * @param refusal what `SessionControl.start` refused with, if it was called.
     * @param checkFailed true when the readiness check threw.
     */
    fun issues(
        report: ReadinessReport?,
        consentCurrent: Boolean?,
        storageCanStart: Boolean?,
        refusal: StartRefusal?,
        checkFailed: Boolean = false,
    ): List<PrestartIssue> {
        val found = ArrayList<PrestartIssue>()
        if (consentCurrent == false) found += PrestartIssue(PrestartIssueKind.NO_CONSENT, blocking = true)
        report?.items?.forEach { item ->
            if (item.level == ReadinessLevel.OK) return@forEach
            val kind = kindOf(item.check) ?: return@forEach
            found += PrestartIssue(kind, blocking = item.level == ReadinessLevel.BLOCKER, target = item.target)
        }
        if (storageCanStart == false) found += PrestartIssue(PrestartIssueKind.STORAGE_FULL, blocking = true)
        refusal?.let { kindOf(it) }?.let { kind -> found += PrestartIssue(kind, blocking = true) }
        if (report?.aggressiveOem == true) found += PrestartIssue(PrestartIssueKind.AGGRESSIVE_OEM, blocking = false)
        if (checkFailed) found += PrestartIssue(PrestartIssueKind.CHECK_FAILED, blocking = false)
        return merged(found).sortedByDescending { it.blocking }
    }

    /** The sheet problem for a readiness check; null for the phone permission, which is never a problem. */
    fun kindOf(check: ReadinessCheck): PrestartIssueKind? = when (check) {
        ReadinessCheck.PRECISE_LOCATION -> PrestartIssueKind.NO_PRECISE_LOCATION
        ReadinessCheck.LOCATION_ENABLED -> PrestartIssueKind.LOCATION_OFF
        ReadinessCheck.NOTIFICATIONS -> PrestartIssueKind.NOTIFICATIONS_OFF
        ReadinessCheck.PHONE_PERMISSION -> null
        ReadinessCheck.BATTERY_OPTIMISATION -> PrestartIssueKind.BATTERY_OPTIMISATION
        ReadinessCheck.BACKGROUND_RESTRICTION -> PrestartIssueKind.BACKGROUND_RESTRICTED
        ReadinessCheck.STANDBY_BUCKET -> PrestartIssueKind.STANDBY_BUCKET
        ReadinessCheck.SIM_PRESENT -> PrestartIssueKind.NO_SIM
        ReadinessCheck.WIFI_OFF -> PrestartIssueKind.WIFI_ON_BATTERY
    }

    /** The sheet problem for a refusal; null for refusals the sheet does not show (blank name, running session). */
    fun kindOf(refusal: StartRefusal): PrestartIssueKind? = when (refusal) {
        StartRefusal.NO_CONSENT -> PrestartIssueKind.NO_CONSENT
        StartRefusal.NO_PRECISE_LOCATION -> PrestartIssueKind.NO_PRECISE_LOCATION
        StartRefusal.LOCATION_OFF -> PrestartIssueKind.LOCATION_OFF
        StartRefusal.STORAGE_FULL -> PrestartIssueKind.STORAGE_FULL
        StartRefusal.BLANK_NAME, StartRefusal.SESSION_RUNNING -> null
    }

    /**
     * The system settings screen that fixes [issue]: the one the readiness check named, else the usual one
     * for its kind; [SettingsTarget.NONE] for problems fixed inside the app or not fixable in settings.
     */
    fun settingsTarget(issue: PrestartIssue): SettingsTarget {
        if (issue.target != SettingsTarget.NONE) return issue.target
        return when (issue.kind) {
            PrestartIssueKind.NO_PRECISE_LOCATION, PrestartIssueKind.BACKGROUND_RESTRICTED, PrestartIssueKind.STANDBY_BUCKET ->
                SettingsTarget.APP_DETAILS
            PrestartIssueKind.LOCATION_OFF -> SettingsTarget.LOCATION_SOURCE
            PrestartIssueKind.NOTIFICATIONS_OFF -> SettingsTarget.APP_NOTIFICATIONS
            PrestartIssueKind.BATTERY_OPTIMISATION -> SettingsTarget.BATTERY_OPTIMISATION
            PrestartIssueKind.WIFI_ON_BATTERY -> SettingsTarget.WIFI
            PrestartIssueKind.NO_CONSENT, PrestartIssueKind.STORAGE_FULL, PrestartIssueKind.NO_SIM,
            PrestartIssueKind.AGGRESSIVE_OEM, PrestartIssueKind.CHECK_FAILED -> SettingsTarget.NONE
        }
    }

    /** One issue per kind, at its first position; blocking if any report of it blocks. */
    private fun merged(issues: List<PrestartIssue>): List<PrestartIssue> {
        val byKind = LinkedHashMap<PrestartIssueKind, PrestartIssue>()
        for (issue in issues) {
            val existing = byKind[issue.kind]
            byKind[issue.kind] = if (existing == null) {
                issue
            } else {
                existing.copy(
                    blocking = existing.blocking || issue.blocking,
                    target = if (existing.target != SettingsTarget.NONE) existing.target else issue.target,
                )
            }
        }
        return byKind.values.toList()
    }
}
