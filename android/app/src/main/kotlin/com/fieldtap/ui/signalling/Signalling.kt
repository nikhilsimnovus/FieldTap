package com.fieldtap.ui.signalling

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fieldtap.R
import com.fieldtap.ui.common.startedWords
import com.fieldtap.ui.components.EmptyState
import com.fieldtap.ui.components.Eyebrow
import com.fieldtap.ui.components.FieldTapTopBar
import com.fieldtap.ui.components.SectionCard
import com.fieldtap.ui.components.SectionDivider
import com.fieldtap.ui.components.StatusBanner
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.Formats
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone

/**
 * Signalling: one button, and what it is doing.
 *
 * The captures themselves are not listed here. They live in Recordings with the drives, because a kept
 * capture and a kept drive are the same thing to the person looking for one, and two lists in two tabs
 * meant remembering which tab held which.
 *
 * Owner: workstream `diag-on-handset`.
 */
@Composable
fun SignallingScreen(
    viewModel: SignallingViewModel,
    onOpenCapture: (String) -> Unit,
    onOpenRecordings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A finished capture opens itself. Recording it and then hunting for it were two steps too many.
    LaunchedEffect(state.justSaved) {
        state.justSaved?.let { name ->
            viewModel.consumeJustSaved()
            onOpenCapture(name)
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = { FieldTapTopBar(title = stringResource(R.string.signalling_title)) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.Lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.SectionGap),
        ) {
            Spacer(modifier = Modifier.height(Spacing.Lg))
            CaptureCard(state, viewModel)
            if (state.keptCount > 0) {
                KeptLine(count = state.keptCount, onClick = onOpenRecordings)
            }
            Spacer(modifier = Modifier.height(Spacing.Lg))
        }
    }
}

/** Where the captures went: they are listed with the drives, not here. One list, one place. */
@Composable
private fun KeptLine(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(R.plurals.signalling_kept_line, count, count),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = FieldTapIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CaptureCard(state: SignallingUiState, viewModel: SignallingViewModel) {
    SectionCard(title = stringResource(R.string.signalling_capture_title)) {
        Text(
            text = stringResource(R.string.signalling_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.message?.let {
            StatusBanner(
                message = it,
                tone = if (state.failed) StatusTone.ERROR else StatusTone.INFO,
                icon = FieldTapIcons.SignalBars,
            )
        }
        if (state.capturing) {
            Button(onClick = viewModel::stop, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.signalling_stop))
            }
        } else {
            Button(onClick = viewModel::start, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.signalling_start))
            }
        }
    }
}

/** The call flow of one capture, decoded from its file every time it is opened. */
@Composable
fun CaptureDetailScreen(
    viewModel: CaptureDetailViewModel,
    onBack: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nowUtcMs = remember { System.currentTimeMillis() }
    Scaffold(
        modifier = modifier,
        topBar = {
            // The directory name is a UTC stamp — "20260915-130400" tells the user nothing they were
            // looking for. The title says what it is and when, in their own clock.
            FieldTapTopBar(
                title = state.capture?.let { startedWords(it.startedUtcMs, nowUtcMs) }
                    ?: stringResource(R.string.recordings_capture_kind),
                onNavigateUp = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.Lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.SectionGap),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = Spacing.Lg),
        ) {
            item(key = "actions") {
                SectionCard(title = stringResource(R.string.signalling_flow_title)) {
                    Text(
                        text = when {
                            state.loading -> stringResource(R.string.signalling_reading)
                            state.failed -> stringResource(R.string.signalling_unreadable)
                            // A capture of a phone that is not attached is megabytes of the modem's own
                            // debug chatter and no signalling at all. "0 messages · 0 records" over a
                            // 7 MB file reads as a broken app; the size says plainly that it recorded.
                            state.entries.isEmpty() && state.otherRecords == 0 -> stringResource(
                                R.string.signalling_flow_nothing_read,
                                Formats.decimalBytes(state.capture?.bytes ?: 0L),
                            )

                            else -> stringResource(
                                R.string.signalling_flow_subtitle,
                                pluralStringResource(R.plurals.signalling_messages, state.entries.size, state.entries.size),
                                pluralStringResource(R.plurals.signalling_records, state.otherRecords, state.otherRecords),
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(R.string.signalling_export))
                    }
                }
            }
            if (!state.loading && state.entries.isEmpty()) {
                item(key = "quiet") {
                    EmptyState(
                        icon = FieldTapIcons.SignalBars,
                        title = stringResource(R.string.signalling_quiet_title),
                        message = stringResource(R.string.signalling_quiet_message),
                    )
                }
            }
            items(state.entries.size, key = { it }) { index ->
                SectionCard {
                    SignallingRow(state.entries[index])
                }
            }
        }
    }
}

@Composable
private fun SignallingRow(entry: com.fieldtap.diag.SignallingEntry) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Xxs)) {
        Eyebrow(text = directionLabel(entry))
        Text(
            text = entry.name ?: entry.fallback,
            style = MaterialTheme.typography.bodyLarge,
            color = if (entry.isReject) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        val causeName = entry.causeName
        val cause = entry.cause
        if (cause != null) {
            SectionDivider()
            Text(
                text = stringResource(R.string.signalling_cause, cause, causeName ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun directionLabel(entry: com.fieldtap.diag.SignallingEntry): String {
    val arrow = when (entry.direction) {
        "ul" -> stringResource(R.string.signalling_uplink)
        "dl" -> stringResource(R.string.signalling_downlink)
        else -> ""
    }
    return listOf(entry.rat.uppercase(), entry.sublayer?.uppercase().orEmpty(), arrow)
        .filter { it.isNotEmpty() }
        .joinToString(stringResource(R.string.value_separator))
}
