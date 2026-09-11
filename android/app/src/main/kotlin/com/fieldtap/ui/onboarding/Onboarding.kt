package com.fieldtap.ui.onboarding

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fieldtap.R
import com.fieldtap.app.AppGraph
import com.fieldtap.app.FieldTapApplication
import com.fieldtap.core.privacy.Consent
import com.fieldtap.core.privacy.ConsentPoint
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
import com.fieldtap.ui.theme.Durations
import com.fieldtap.ui.theme.FieldTapBrandMark
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.ShapeRoles
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

    /** `Consent.CURRENT`, whose text is [summary] and [notice] word for word. */
    val consentText: ConsentText get() = Consent.CURRENT

    /** The four points the disclosure shows first. */
    val summary: List<ConsentPoint> get() = Consent.SUMMARY

    /** The full notice, paragraph by paragraph, in the disclosure's expandable section. */
    val notice: List<String> get() = Consent.NOTICE

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
 * Full-screen disclosure, shown before any location prompt: why precise location is asked for, the four points of the
 * consent notice with an icon each, the full notice in an expandable section, the limits statement, then Accept and Not
 * now. Every word shown of the notice, the points included, is the hashed consent text. Declining leaves the app usable
 * for About only; no prompt is shown.
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
        summary = viewModel.summary,
        notice = viewModel.notice,
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
    summary: List<ConsentPoint>,
    notice: List<String>,
    consentCurrent: Boolean,
    acceptedAtUtcMs: Long?,
    acceptState: AcceptState,
    onAccept: () -> Unit,
    onContinue: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var noticeExpanded by rememberSaveable { mutableStateOf(false) }
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
    val window = LocalWindowInfo.current.containerDpSize
    // A phone in landscape: the 88 dp mark and a large headline filled the first page before any of the four points.
    val short = window.height < Sizes.ShortWindowMaxHeight
    val twoColumns = short && window.width >= Sizes.WideLayoutMinWidth
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
        if (twoColumns) {
            // What you agree to beside the full notice and the limits, so the first page holds the decision.
            item(key = "columns") {
                Row(
                    modifier = Modifier.setupContentWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.SectionGap),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.SectionGap)) {
                        DisclosureHeading(headingText = headingText, introText = introText, short = true)
                        if (acceptedText != null) StatusBanner(message = acceptedText, tone = StatusTone.SUCCESS)
                        if (acceptState == AcceptState.FAILED) StatusBanner(message = saveFailedText, tone = StatusTone.ERROR)
                        SummaryCard(title = consentTitle, summary = summary)
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.SectionGap)) {
                        FullNotice(paragraphs = notice, expanded = noticeExpanded, onToggle = { noticeExpanded = !noticeExpanded })
                        LimitsStatementCard(title = limitsTitle, statement = limitsStatement)
                    }
                }
            }
            return@SetupScreenScaffold
        }
        item(key = "heading") {
            DisclosureHeading(headingText = headingText, introText = introText, short = short, modifier = Modifier.setupContentWidth())
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
        item(key = "summary") {
            SummaryCard(title = consentTitle, summary = summary, modifier = Modifier.setupContentWidth())
        }
        item(key = "notice") {
            FullNotice(
                paragraphs = notice,
                expanded = noticeExpanded,
                onToggle = { noticeExpanded = !noticeExpanded },
                modifier = Modifier.setupContentWidth(),
            )
        }
        item(key = "limits") {
            LimitsStatementCard(title = limitsTitle, statement = limitsStatement, modifier = Modifier.setupContentWidth())
        }
    }
}

/**
 * The brand mark, the heading and why precise location is asked for. In a short window (a phone in landscape) the mark is
 * 40 dp beside a smaller heading, with less space above.
 */
@Composable
private fun DisclosureHeading(headingText: String, introText: String, short: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(top = if (short) Spacing.Sm else Spacing.Xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        if (short) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                FieldTapBrandMark(modifier = Modifier.size(Sizes.BrandMarkCompact))
                Text(
                    text = headingText,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
            }
        } else {
            FieldTapBrandMark(modifier = Modifier.size(Sizes.EmptyStateBadge))
            Text(
                text = headingText,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(
            text = introText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The four points of what you agree to, each with its icon. */
@Composable
private fun SummaryCard(title: String, summary: List<ConsentPoint>, modifier: Modifier = Modifier) {
    SectionCard(title = title, icon = FieldTapIcons.Shield, modifier = modifier) {
        summary.forEachIndexed { index, point -> SummaryPoint(topic = SummaryTopic.of(index), point = point) }
    }
}

/**
 * What each point of [Consent.SUMMARY] is about, in order, for its icon: what is recorded, that it stays on the phone,
 * identifiers, withdrawing. A point past the list, from a later text, takes [GENERAL].
 */
internal enum class SummaryTopic {
    RECORDED,
    STAYS_ON_PHONE,
    IDENTIFIERS,
    WITHDRAW,
    GENERAL,
    ;

    val icon: ImageVector
        get() = when (this) {
            RECORDED -> FieldTapIcons.SignalBars
            STAYS_ON_PHONE -> FieldTapIcons.Phone
            IDENTIFIERS -> FieldTapIcons.Sim
            WITHDRAW -> FieldTapIcons.Tune
            GENERAL -> FieldTapIcons.Info
        }

    companion object {
        /** The topics of the current summary's points, one each. */
        val CURRENT: List<SummaryTopic> = listOf(RECORDED, STAYS_ON_PHONE, IDENTIFIERS, WITHDRAW)

        fun of(index: Int): SummaryTopic = CURRENT.getOrElse(index) { GENERAL }
    }
}

/**
 * One point of the summary: an icon in a tonal circle, the title and the body, read by TalkBack as one item. The icon is
 * decoration.
 */
@Composable
private fun SummaryPoint(topic: SummaryTopic, point: ConsentPoint) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.Lg),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(modifier = Modifier.size(Sizes.IconContainer), contentAlignment = Alignment.Center) {
                Icon(imageVector = topic.icon, contentDescription = null, modifier = Modifier.size(Sizes.Icon))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Xxs)) {
            Text(text = point.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(text = point.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The full notice, word for word, behind a header that opens and closes it. The header is one 48 dp button that TalkBack
 * reads with its state and what a double tap does; the notice starts closed, and stays as the user left it across a
 * rotation.
 */
@Composable
private fun FullNotice(paragraphs: List<String>, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val stateText = stringResource(if (expanded) R.string.disclosure_notice_expanded else R.string.disclosure_notice_collapsed)
    val actionText = stringResource(if (expanded) R.string.disclosure_notice_hide else R.string.disclosure_notice_show)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) HALF_TURN_DEGREES else 0f,
        animationSpec = tween(Durations.SHORT),
        label = "notice chevron",
    )
    Surface(modifier = modifier.fillMaxWidth(), shape = ShapeRoles.Card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget)
                    .clickable(onClickLabel = actionText, role = Role.Button, onClick = onToggle)
                    .semantics(mergeDescendants = true) { stateDescription = stateText }
                    .padding(Spacing.CardPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                Icon(
                    imageVector = FieldTapIcons.File,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Sizes.Icon),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.disclosure_notice_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.disclosure_notice_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = FieldTapIcons.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(Sizes.Icon)
                        .rotate(chevronRotation),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(Durations.MEDIUM)) + fadeIn(tween(Durations.MEDIUM)),
                exit = shrinkVertically(tween(Durations.SHORT)) + fadeOut(tween(Durations.SHORT)),
            ) {
                Column(
                    modifier = Modifier.padding(start = Spacing.CardPadding, end = Spacing.CardPadding, bottom = Spacing.CardPadding),
                    verticalArrangement = Arrangement.spacedBy(Spacing.ItemGap),
                ) {
                    paragraphs.forEachIndexed { index, paragraph -> NoticeParagraph(topic = ConsentTopic.of(index), text = paragraph) }
                }
            }
        }
    }
}

private const val HALF_TURN_DEGREES = 180f

/**
 * What each paragraph of [Consent.NOTICE] is about, in order, so the full notice still scans paragraph by paragraph: the
 * session, what it records, location and privacy zones, where recordings go, identifiers, withdrawing. A paragraph past
 * the list, from a later text, takes [GENERAL].
 */
internal enum class ConsentTopic {
    SESSION,
    RECORDED,
    LOCATION,
    SHARING,
    IDENTIFIERS,
    WITHDRAW,
    GENERAL,
    ;

    companion object {
        /** The topics of the current text's paragraphs, one each. */
        val CURRENT: List<ConsentTopic> = listOf(SESSION, RECORDED, LOCATION, SHARING, IDENTIFIERS, WITHDRAW)

        fun of(index: Int): ConsentTopic = CURRENT.getOrElse(index) { GENERAL }
    }
}

/** One paragraph of the full notice, word for word, after an icon for its topic. The icon is decoration: TalkBack reads the text. */
@Composable
private fun NoticeParagraph(topic: ConsentTopic, text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Md)) {
        Icon(
            imageVector = when (topic) {
                ConsentTopic.SESSION -> FieldTapIcons.Play
                ConsentTopic.RECORDED -> FieldTapIcons.SignalBars
                ConsentTopic.LOCATION -> FieldTapIcons.Location
                ConsentTopic.SHARING -> FieldTapIcons.Share
                ConsentTopic.IDENTIFIERS -> FieldTapIcons.Shield
                ConsentTopic.WITHDRAW -> FieldTapIcons.Tune
                ConsentTopic.GENERAL -> FieldTapIcons.Info
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = Spacing.Xxs)
                .size(Sizes.Icon),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

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
                .padding(horizontal = Spacing.ScreenGutter, vertical = Spacing.Sm),
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

/**
 * Continue, with why it is disabled beside it on the same row. Stacked, hint above button, the bar took 124 dp, a third of
 * a landscape screen, and hid the location card's Allow button.
 */
@Composable
private fun PermissionsContinueBar(enabled: Boolean, onContinue: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.ScreenGutter, vertical = Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            if (!enabled) {
                Text(
                    text = stringResource(R.string.permissions_continue_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
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
            summary = Consent.SUMMARY,
            notice = Consent.NOTICE,
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
