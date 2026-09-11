package com.fieldtap.ui.onboarding

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fieldtap.R
import com.fieldtap.app.AppGraph
import com.fieldtap.app.FieldTapApplication
import com.fieldtap.core.privacy.Consent
import com.fieldtap.core.privacy.ConsentText
import com.fieldtap.core.readiness.SettingsTarget
import com.fieldtap.platform.Permissions
import com.fieldtap.ui.components.FieldTapPreviews
import com.fieldtap.ui.components.LimitsStatementCard
import com.fieldtap.ui.components.PermissionRationale
import com.fieldtap.ui.components.PermissionStatus
import com.fieldtap.ui.components.PreviewSurface
import com.fieldtap.ui.components.SectionCard
import com.fieldtap.ui.components.StatusBanner
import com.fieldtap.ui.setup.ButtonProgress
import com.fieldtap.ui.setup.PermissionAction
import com.fieldtap.ui.setup.PermissionRules
import com.fieldtap.ui.setup.PermissionUi
import com.fieldtap.ui.setup.SettingsIntents
import com.fieldtap.ui.setup.SetupFormats
import com.fieldtap.ui.setup.SetupScreenScaffold
import com.fieldtap.ui.setup.setupContentWidth
import com.fieldtap.ui.theme.FieldTapBrandMark
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Where saving the user's consent stands. */
enum class AcceptState {
    /** Nothing is being saved. */
    IDLE,

    /** The consent record is being written. */
    SAVING,

    /** The last write failed; Accept can be tapped again. */
    FAILED,
}

/**
 * The disclosure's state and the consent it records.
 *
 * - [consentCurrent] follows the stored settings: true once the stored `ConsentRecord` matches [Consent.CURRENT]
 *   by version and SHA-256.
 * - [accept] builds `Consent.record(clock.wallMillis())` at the tap and stores it through
 *   `SettingsRepository.update`, whose atomic edit never loses a concurrent settings change. When the stored consent
 *   is already current it writes nothing, so the original grant time stays. A stored (or already current) consent
 *   emits once on [accepted]; a failed write sets [acceptState] to [AcceptState.FAILED] and emits nothing.
 *
 * Owner: workstream `ui-setup`.
 */
class OnboardingViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutableConsentCurrent = MutableStateFlow(false)
    private val mutableAcceptedAtUtcMs = MutableStateFlow<Long?>(null)
    private val mutableAcceptState = MutableStateFlow(AcceptState.IDLE)
    private val acceptedEvents = Channel<Unit>(Channel.BUFFERED)
    private var saveJob: Job? = null

    /** `Consent.CURRENT`, shown verbatim. */
    val consentText: ConsentText get() = Consent.CURRENT

    /** True once the stored consent matches the current text. */
    val consentCurrent: StateFlow<Boolean> = mutableConsentCurrent.asStateFlow()

    /** When the current consent was granted (Unix milliseconds), or null when the stored consent is not current. */
    val acceptedAtUtcMs: StateFlow<Long?> = mutableAcceptedAtUtcMs.asStateFlow()

    /** Whether [accept] is saving, or failed to. */
    val acceptState: StateFlow<AcceptState> = mutableAcceptState.asStateFlow()

    /** Emits once each time [accept] has stored, or found, a current consent: the screen moves on. */
    val accepted: Flow<Unit> = acceptedEvents.receiveAsFlow()

    init {
        viewModelScope.launch {
            graph.settings.settings
                // The repository already turns unreadable settings into defaults; keep the last known state otherwise.
                .catch { error -> if (error !is Exception) throw error }
                .collect { settings ->
                    val current = Consent.isCurrent(settings.consent)
                    mutableConsentCurrent.value = current
                    mutableAcceptedAtUtcMs.value = if (current) settings.consent?.grantedUtcMs else null
                }
        }
    }

    /** Stores a `ConsentRecord` for the current version with the wall clock. */
    fun accept() {
        if (saveJob?.isActive == true) return
        if (mutableConsentCurrent.value) {
            acceptedEvents.trySend(Unit)
            return
        }
        val record = Consent.record(graph.clock.wallMillis())
        mutableAcceptState.value = AcceptState.SAVING
        saveJob = viewModelScope.launch {
            val saved = try {
                graph.settings.update { settings ->
                    if (Consent.isCurrent(settings.consent)) settings else settings.copy(consent = record)
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                false
            } catch (e: RuntimeException) {
                false
            }
            if (saved) {
                mutableAcceptState.value = AcceptState.IDLE
                acceptedEvents.send(Unit)
            } else {
                mutableAcceptState.value = AcceptState.FAILED
            }
        }
    }
}

/**
 * Full-screen disclosure, shown before any location prompt: the consent text verbatim, the limits
 * statement, Accept and Not now. Declining leaves the app usable for About only; no prompt is shown.
 *
 * Owner: workstream `ui-setup`.
 */
@Composable
fun DisclosureScreen(
    viewModel: OnboardingViewModel,
    onAccepted: () -> Unit,
    onDeclined: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val consentCurrent by viewModel.consentCurrent.collectAsStateWithLifecycle()
    val acceptedAtUtcMs by viewModel.acceptedAtUtcMs.collectAsStateWithLifecycle()
    val acceptState by viewModel.acceptState.collectAsStateWithLifecycle()
    val currentOnAccepted by rememberUpdatedState(onAccepted)
    LaunchedEffect(viewModel) {
        viewModel.accepted.collect { currentOnAccepted() }
    }
    DisclosureContent(
        consentText = viewModel.consentText.text,
        consentCurrent = consentCurrent,
        acceptedAtUtcMs = acceptedAtUtcMs,
        acceptState = acceptState,
        onAccept = viewModel::accept,
        onContinue = onAccepted,
        onDecline = onDeclined,
        modifier = modifier,
    )
}

/** The disclosure without its view model, for previews. */
@Composable
internal fun DisclosureContent(
    consentText: String,
    consentCurrent: Boolean,
    acceptedAtUtcMs: Long?,
    acceptState: AcceptState,
    onAccept: () -> Unit,
    onContinue: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paragraphs = remember(consentText) { consentParagraphs(consentText) }
    val headingText = stringResource(R.string.disclosure_heading)
    val introText = stringResource(R.string.disclosure_intro)
    val consentTitle = stringResource(R.string.disclosure_consent_title)
    val limitsTitle = stringResource(R.string.disclosure_limits_title)
    val limitsStatement = stringResource(R.string.limits_statement)
    val saveFailedText = stringResource(R.string.disclosure_save_failed)
    val acceptedText = if (consentCurrent && acceptedAtUtcMs != null) {
        stringResource(R.string.disclosure_already_accepted, SetupFormats.date(acceptedAtUtcMs))
    } else {
        null
    }
    SetupScreenScaffold(
        title = null,
        modifier = modifier,
        bottomBar = {
            DisclosureActions(
                consentCurrent = consentCurrent,
                saving = acceptState == AcceptState.SAVING,
                onAccept = onAccept,
                onContinue = onContinue,
                onDecline = onDecline,
            )
        },
    ) {
        item(key = "heading") {
            Column(
                modifier = Modifier
                    .setupContentWidth()
                    .padding(top = Spacing.Xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                FieldTapBrandMark(modifier = Modifier.size(Sizes.EmptyStateBadge))
                Text(
                    text = headingText,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = introText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (acceptedText != null) {
            item(key = "accepted") {
                StatusBanner(message = acceptedText, tone = StatusTone.SUCCESS, modifier = Modifier.setupContentWidth())
            }
        }
        if (acceptState == AcceptState.FAILED) {
            item(key = "failed") {
                StatusBanner(message = saveFailedText, tone = StatusTone.ERROR, modifier = Modifier.setupContentWidth())
            }
        }
        item(key = "consent") {
            SectionCard(title = consentTitle, icon = FieldTapIcons.Shield, modifier = Modifier.setupContentWidth()) {
                paragraphs.forEach { paragraph ->
                    Text(
                        text = paragraph,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        item(key = "limits") {
            LimitsStatementCard(title = limitsTitle, statement = limitsStatement, modifier = Modifier.setupContentWidth())
        }
    }
}

/** The consent text split into its paragraphs, word for word: joined with a blank line they are the text again. */
internal fun consentParagraphs(text: String): List<String> = text.split(PARAGRAPH_BREAK).filter { it.isNotBlank() }

private const val PARAGRAPH_BREAK = "\n\n"

@Composable
private fun DisclosureActions(
    consentCurrent: Boolean,
    saving: Boolean,
    onAccept: () -> Unit,
    onContinue: () -> Unit,
    onDecline: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.ScreenGutter, vertical = Spacing.Md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (!consentCurrent) {
                TextButton(
                    onClick = onDecline,
                    enabled = !saving,
                    modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(text = stringResource(R.string.disclosure_decline))
                }
            }
            Button(
                onClick = if (consentCurrent) onContinue else onAccept,
                enabled = !saving,
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                if (saving) {
                    ButtonProgress()
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                }
                Text(text = stringResource(if (consentCurrent) R.string.disclosure_continue else R.string.disclosure_accept))
            }
        }
    }
}

/**
 * Permissions, in order, each with one sentence of why: precise location (fine and coarse together;
 * "approximate" is explained as returning no cell info), notifications (Stop and Mark in the
 * notification), and optionally phone (push updates). Refusals link to app settings. Uses
 * `rememberLauncherForActivityResult(RequestMultiplePermissions())`.
 *
 * Decision 9 of android/ARCHITECTURE.md overrides "optionally phone": the Phone permission is asked for only from
 * Settings' "Instant cell updates", so this screen only says that it exists. Nothing is requested until a button is
 * tapped, and the location request stays unavailable until the stored consent is current, whatever route led here.
 * The states are read again on every resume, so a change made in Android's settings shows at once.
 *
 * Owner: workstream `ui-setup`.
 */
@Composable
fun PermissionsScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val openFailedText = stringResource(R.string.setup_open_settings_failed)
    var snapshot by remember(context) { mutableStateOf(PermissionSnapshot.read(context, activity)) }
    var locationRequested by rememberSaveable { mutableStateOf(false) }
    var notificationsRequested by rememberSaveable { mutableStateOf(false) }
    val consentFlow = remember(context) { consentCurrentFlow(context) }
    val consentCurrent by consentFlow.collectAsStateWithLifecycle(initialValue = null)

    LifecycleResumeEffect(context) {
        snapshot = PermissionSnapshot.read(context, activity)
        onPauseOrDispose { }
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationRequested = true
        snapshot = PermissionSnapshot.read(context, activity)
    }
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notificationsRequested = true
        snapshot = PermissionSnapshot.read(context, activity)
    }
    val openSettings: (SettingsTarget) -> Unit = { target ->
        if (!SettingsIntents.open(context, target)) {
            scope.launch { snackbarHostState.showSnackbar(openFailedText) }
        }
    }
    val location = PermissionRules.location(
        fineGranted = snapshot.fineGranted,
        coarseGranted = snapshot.coarseGranted,
        requestedBefore = locationRequested,
        showRationale = snapshot.locationRationale,
    )
    val notifications = PermissionRules.notifications(
        sdkInt = Build.VERSION.SDK_INT,
        permissionGranted = snapshot.notificationPermissionGranted,
        notificationsEnabled = snapshot.notificationsEnabled,
        requestedBefore = notificationsRequested,
        showRationale = snapshot.notificationRationale,
    )
    PermissionsContent(
        location = location,
        notifications = notifications,
        locationServicesOn = snapshot.locationEnabled,
        consentCurrent = consentCurrent,
        onLocationAction = {
            when (location.action) {
                PermissionAction.REQUEST -> if (consentCurrent == true) locationLauncher.launch(Permissions.LOCATION.toTypedArray())
                PermissionAction.OPEN_APP_SETTINGS -> openSettings(SettingsTarget.APP_DETAILS)
                PermissionAction.OPEN_NOTIFICATION_SETTINGS -> openSettings(SettingsTarget.APP_NOTIFICATIONS)
                PermissionAction.NONE -> Unit
            }
        },
        onNotificationsAction = {
            when (notifications.action) {
                PermissionAction.REQUEST -> notificationsLauncher.launch(arrayOf(Permissions.POST_NOTIFICATIONS))
                PermissionAction.OPEN_APP_SETTINGS -> openSettings(SettingsTarget.APP_DETAILS)
                PermissionAction.OPEN_NOTIFICATION_SETTINGS -> openSettings(SettingsTarget.APP_NOTIFICATIONS)
                PermissionAction.NONE -> Unit
            }
        },
        onOpenLocationSettings = { openSettings(SettingsTarget.LOCATION_SOURCE) },
        onContinue = onDone,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/** The Permissions screen without Android state or launchers, for previews. */
@Composable
internal fun PermissionsContent(
    location: PermissionUi,
    notifications: PermissionUi,
    locationServicesOn: Boolean,
    consentCurrent: Boolean?,
    onLocationAction: () -> Unit,
    onNotificationsAction: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    val titleText = stringResource(R.string.permissions_title)
    val introText = stringResource(R.string.permissions_intro)
    val consentNeededText = stringResource(R.string.permissions_consent_needed)
    val locationOffText = stringResource(R.string.permissions_location_off)
    val locationSettingsText = stringResource(R.string.permissions_location_settings)
    val instantUpdatesNote = stringResource(R.string.permissions_instant_updates_note)
    val locationTitle = stringResource(R.string.permissions_location_title)
    val locationReason = stringResource(R.string.permissions_location_reason)
    val notificationsTitle = stringResource(R.string.permissions_notifications_title)
    val notificationsReason = stringResource(R.string.permissions_notifications_reason)
    val requiredTag = stringResource(R.string.permissions_tag_required)
    val recommendedTag = stringResource(R.string.permissions_tag_recommended)
    val locationStatusText = permissionStatusText(location)
    val notificationsStatusText = permissionStatusText(notifications)
    // No location dialog before the disclosure is accepted; opening settings asks for nothing.
    val locationActionText = if (consentCurrent == true || location.action != PermissionAction.REQUEST) {
        permissionActionText(location)
    } else {
        null
    }
    val notificationsActionText = permissionActionText(notifications)

    SetupScreenScaffold(
        title = titleText,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        bottomBar = {
            PermissionsContinueBar(enabled = location.status == PermissionStatus.GRANTED, onContinue = onContinue)
        },
    ) {
        item(key = "intro") {
            Text(
                text = introText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.setupContentWidth(),
            )
        }
        if (consentCurrent == false) {
            item(key = "consent") {
                StatusBanner(message = consentNeededText, tone = StatusTone.WARNING, modifier = Modifier.setupContentWidth())
            }
        }
        if (!locationServicesOn) {
            item(key = "location-off") {
                StatusBanner(
                    message = locationOffText,
                    tone = StatusTone.WARNING,
                    actionLabel = locationSettingsText,
                    onAction = onOpenLocationSettings,
                    modifier = Modifier.setupContentWidth(),
                )
            }
        }
        item(key = "location") {
            PermissionRationale(
                icon = FieldTapIcons.Location,
                title = locationTitle,
                reason = locationReason,
                status = location.status,
                statusText = locationStatusText,
                tagText = requiredTag,
                actionLabel = locationActionText,
                onAction = onLocationAction,
                modifier = Modifier.setupContentWidth(),
            )
        }
        item(key = "notifications") {
            PermissionRationale(
                icon = FieldTapIcons.Notifications,
                title = notificationsTitle,
                reason = notificationsReason,
                status = notifications.status,
                statusText = notificationsStatusText,
                tagText = recommendedTag,
                actionLabel = notificationsActionText,
                onAction = onNotificationsAction,
                modifier = Modifier.setupContentWidth(),
            )
        }
        item(key = "instant-updates") {
            StatusBanner(
                message = instantUpdatesNote,
                tone = StatusTone.NEUTRAL,
                icon = FieldTapIcons.Phone,
                modifier = Modifier.setupContentWidth(),
            )
        }
    }
}

@Composable
private fun permissionStatusText(ui: PermissionUi): String = when {
    ui.status == PermissionStatus.GRANTED -> stringResource(R.string.permissions_status_allowed)
    ui.approximateOnly -> stringResource(R.string.permissions_status_approximate)
    ui.turnedOffInSettings -> stringResource(R.string.permissions_status_notifications_off)
    ui.status == PermissionStatus.NOT_REQUESTED -> stringResource(R.string.permissions_status_not_requested)
    ui.status == PermissionStatus.DENIED -> stringResource(R.string.permissions_status_denied)
    else -> stringResource(R.string.permissions_status_blocked)
}

@Composable
private fun permissionActionText(ui: PermissionUi): String? = when (ui.action) {
    PermissionAction.NONE -> null
    PermissionAction.REQUEST -> stringResource(
        if (ui.status == PermissionStatus.NOT_REQUESTED) R.string.permissions_action_allow else R.string.permissions_action_ask_again,
    )
    PermissionAction.OPEN_APP_SETTINGS -> stringResource(R.string.setup_open_app_settings)
    PermissionAction.OPEN_NOTIFICATION_SETTINGS -> stringResource(R.string.permissions_action_notification_settings)
}

@Composable
private fun PermissionsContinueBar(enabled: Boolean, onContinue: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.ScreenGutter, vertical = Spacing.Md),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            if (!enabled) {
                Text(
                    text = stringResource(R.string.permissions_continue_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onContinue, enabled = enabled, modifier = Modifier.heightIn(min = Sizes.MinTouchTarget)) {
                Text(text = stringResource(R.string.permissions_continue))
            }
        }
    }
}

/** The permission facts the Permissions screen shows, read in one go on each resume and after each request. */
internal data class PermissionSnapshot(
    val fineGranted: Boolean,
    val coarseGranted: Boolean,
    val locationRationale: Boolean,
    val notificationPermissionGranted: Boolean,
    val notificationsEnabled: Boolean,
    val notificationRationale: Boolean,
    val locationEnabled: Boolean,
) {
    companion object {
        /** Quick local reads. A read Android fails shows the state that offers a fix, never a false "allowed". */
        fun read(context: Context, activity: Activity?): PermissionSnapshot {
            val notificationsEnabled = try {
                context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
            } catch (e: RuntimeException) {
                false
            }
            // An unreadable location switch shows no "location is off" banner rather than a wrong one.
            val locationEnabled = try {
                Permissions.locationEnabled(context)
            } catch (e: RuntimeException) {
                true
            }
            return PermissionSnapshot(
                fineGranted = granted(context, Permissions.FINE_LOCATION),
                coarseGranted = granted(context, Permissions.COARSE_LOCATION),
                locationRationale = rationale(activity, Permissions.FINE_LOCATION) || rationale(activity, Permissions.COARSE_LOCATION),
                notificationPermissionGranted = Build.VERSION.SDK_INT < PermissionRules.NOTIFICATIONS_RUNTIME_SDK ||
                    granted(context, Permissions.POST_NOTIFICATIONS),
                notificationsEnabled = notificationsEnabled,
                notificationRationale = rationale(activity, Permissions.POST_NOTIFICATIONS),
                locationEnabled = locationEnabled,
            )
        }

        private fun granted(context: Context, permission: String): Boolean =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        private fun rationale(activity: Activity?, permission: String): Boolean =
            activity?.shouldShowRequestPermissionRationale(permission) == true
    }
}

/**
 * Whether the stored consent is current, read from the app's settings. Outside the app's own process (previews,
 * tests) there is no graph and the check does not block. Unreadable settings count as no consent.
 */
private fun consentCurrentFlow(context: Context): Flow<Boolean> {
    val application = context.applicationContext as? FieldTapApplication ?: return flowOf(true)
    return application.graph.settings.settings
        .map { settings -> Consent.isCurrent(settings.consent) }
        .catch { error -> if (error is Exception) emit(false) else throw error }
}

@FieldTapPreviews
@Composable
private fun DisclosurePreview() {
    PreviewSurface {
        DisclosureContent(
            consentText = Consent.CURRENT.text,
            consentCurrent = false,
            acceptedAtUtcMs = null,
            acceptState = AcceptState.IDLE,
            onAccept = {},
            onContinue = {},
            onDecline = {},
        )
    }
}

@FieldTapPreviews
@Composable
private fun PermissionsPreview() {
    PreviewSurface {
        PermissionsContent(
            location = PermissionUi(PermissionStatus.DENIED, PermissionAction.REQUEST, approximateOnly = true),
            notifications = PermissionUi(PermissionStatus.GRANTED, PermissionAction.NONE),
            locationServicesOn = false,
            consentCurrent = true,
            onLocationAction = {},
            onNotificationsAction = {},
            onOpenLocationSettings = {},
            onContinue = {},
        )
    }
}
