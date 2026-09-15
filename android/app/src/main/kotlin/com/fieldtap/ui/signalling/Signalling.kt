package com.fieldtap.ui.signalling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fieldtap.R
import com.fieldtap.diag.SignallingEntry
import com.fieldtap.ui.components.EmptyState
import com.fieldtap.ui.components.Eyebrow
import com.fieldtap.ui.components.FieldTapTopBar
import com.fieldtap.ui.components.SectionCard
import com.fieldtap.ui.components.SectionDivider
import com.fieldtap.ui.common.FileSharer
import com.fieldtap.ui.components.StatusBanner
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.Spacing
import com.fieldtap.ui.theme.StatusTone

/**
 * The call flow, read on the phone that captured it.
 *
 * What it shows is what the handset can read honestly: NAS messages, their direction, and the cause
 * when the network refuses something. RRC is ASN.1 and is not decoded here — those records are counted
 * and carried by the exported `.qmdl`, which `fieldtap report` and Wireshark read in full. The screen
 * says so rather than leaving a reader to wonder why a flow looks short.
 *
 * Owner: workstream `diag-on-handset`.
 */
@Composable
fun SignallingScreen(viewModel: SignallingViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportSubject = stringResource(R.string.signalling_export_subject)
    val onExport = {
        state.captureFile?.let { file ->
            // The raw capture, not a decode: RRC is what the phone cannot read, and it is all in here.
            runCatching { FileSharer.share(context, file, SignallingViewModel.MIME, exportSubject, null) }
        }
        Unit
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
                .padding(horizontal = Spacing.Lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.SectionGap),
        ) {
            SignallingControls(state, viewModel, onExport)
            SignallingFlow(state, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SignallingControls(state: SignallingUiState, viewModel: SignallingViewModel, onExport: () -> Unit) {
    SectionCard(title = stringResource(R.string.signalling_capture_title)) {
        Text(
            text = stringResource(R.string.signalling_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.message?.let { StatusBanner(message = it, tone = state.tone, icon = FieldTapIcons.SignalBars) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.capturing) {
                Button(onClick = viewModel::stop, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.signalling_stop))
                }
            } else {
                Button(onClick = viewModel::start, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.signalling_start))
                }
            }
            if (state.canExport) {
                OutlinedButton(onClick = onExport, enabled = !state.busy) {
                    Text(text = stringResource(R.string.signalling_export))
                }
            }
        }
    }
}

@Composable
private fun SignallingFlow(state: SignallingUiState, modifier: Modifier = Modifier) {
    if (state.entries.isEmpty()) {
        EmptyState(
            icon = FieldTapIcons.SignalBars,
            title = stringResource(R.string.signalling_empty_title),
            message = stringResource(R.string.signalling_empty_message),
            modifier = modifier,
        )
        return
    }
    SectionCard(
        title = stringResource(R.string.signalling_flow_title),
        subtitle = stringResource(R.string.signalling_flow_subtitle, state.entries.size, state.rrcRecords),
        modifier = modifier,
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = FLOW_MAX_HEIGHT)) {
            items(state.entries) { entry ->
                SignallingRow(entry)
                SectionDivider()
            }
        }
    }
}

@Composable
private fun SignallingRow(entry: SignallingEntry) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Xxs)) {
        Eyebrow(text = directionLabel(entry))
        Text(
            text = entry.name ?: entry.fallback,
            style = MaterialTheme.typography.bodyLarge,
            color = if (entry.isReject) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        val cause = entry.causeName ?: entry.cause?.toString()
        if (cause != null) {
            Text(
                text = stringResource(R.string.signalling_cause, entry.cause ?: 0, cause),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun directionLabel(entry: SignallingEntry): String {
    val rat = entry.rat.uppercase()
    val arrow = when (entry.direction) {
        "ul" -> stringResource(R.string.signalling_uplink)
        "dl" -> stringResource(R.string.signalling_downlink)
        else -> ""
    }
    return listOf(rat, entry.sublayer?.uppercase().orEmpty(), arrow).filter { it.isNotEmpty() }
        .joinToString(stringResource(R.string.value_separator))
}

/** Tall enough for a flow, short enough that the controls above it stay on screen. */
private val FLOW_MAX_HEIGHT = androidx.compose.ui.unit.Dp(460f)

private val SignallingUiState.tone: StatusTone
    get() = if (failed) StatusTone.ERROR else StatusTone.INFO
