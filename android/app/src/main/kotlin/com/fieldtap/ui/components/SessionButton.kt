package com.fieldtap.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fieldtap.ui.theme.Durations
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing

/** The states of [SessionButton], mapped from `SessionStatus` (Idle, Starting, Recording, Stopping). */
enum class SessionButtonState {
    IDLE,
    STARTING,
    RECORDING,
    STOPPING,
}

/**
 * The primary action of the Live screen: Start a session, or Stop the running one.
 *
 * - IDLE: brand blue, play icon, [startLabel].
 * - STARTING / STOPPING: disabled with a spinner and [busyLabel], so a double tap cannot start twice.
 * - RECORDING: the recording colour, a pulsing dot, [recordingLabel] and [elapsedText] on the start
 *   side, and a stop icon with [stopLabel] on the end side. The change of colour, icon and words makes
 *   the running state unmistakable at a glance.
 *
 * Full width, 64 dp tall. Stopping ends the session for good, so confirm it with a dialog in the
 * screen before calling the view model.
 */
@Composable
fun SessionButton(
    state: SessionButtonState,
    startLabel: String,
    stopLabel: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    busyLabel: String? = null,
    recordingLabel: String? = null,
    elapsedText: String? = null,
    enabled: Boolean = true,
) {
    val colors = FieldTapDesign.colors
    val recording = state == SessionButtonState.RECORDING
    val busy = state == SessionButtonState.STARTING || state == SessionButtonState.STOPPING
    val container by animateColorAsState(
        targetValue = if (recording) colors.recording.color else MaterialTheme.colorScheme.primary,
        animationSpec = tween(Durations.MEDIUM),
        label = "sessionButtonContainer",
    )
    val content by animateColorAsState(
        targetValue = if (recording) colors.recording.onColor else MaterialTheme.colorScheme.onPrimary,
        animationSpec = tween(Durations.MEDIUM),
        label = "sessionButtonContent",
    )
    Button(
        onClick = { if (recording) onStop() else onStart() },
        enabled = enabled && !busy,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.PrimaryButtonHeight),
        shape = ShapeRoles.Pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = Spacing.Xl, vertical = Spacing.Sm),
    ) {
        when (state) {
            SessionButtonState.IDLE -> {
                Icon(imageVector = FieldTapIcons.Play, contentDescription = null, modifier = Modifier.size(Sizes.Icon))
                Spacer(modifier = Modifier.width(Spacing.Sm))
                Text(text = startLabel, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            SessionButtonState.STARTING, SessionButtonState.STOPPING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(Sizes.InlineProgress),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(Spacing.Md))
                Text(
                    text = busyLabel ?: if (state == SessionButtonState.STARTING) startLabel else stopLabel,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SessionButtonState.RECORDING -> {
                RecordingDot(color = content)
                Spacer(modifier = Modifier.width(Spacing.Md))
                Column(modifier = Modifier.weight(1f)) {
                    if (recordingLabel != null) {
                        Text(text = recordingLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                    if (elapsedText != null) {
                        Text(text = elapsedText, style = FieldTapDesign.numeric.medium, maxLines = 1)
                    }
                }
                Icon(imageVector = FieldTapIcons.Stop, contentDescription = null, modifier = Modifier.size(Sizes.Icon))
                Spacer(modifier = Modifier.width(Spacing.Sm))
                Text(text = stopLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        }
    }
}

/**
 * A softly pulsing dot that says "recording now". Use it next to a "Recording" label (on the button,
 * in a status row); it is decorative, so the label must be there.
 */
@Composable
fun RecordingDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.RecordingDot,
) {
    val transition = rememberInfiniteTransition(label = "recordingDot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(animation = tween(Durations.PULSE), repeatMode = RepeatMode.Reverse),
        label = "recordingDotAlpha",
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { this.alpha = alpha }
            .background(color, CircleShape),
    )
}

@FieldTapPreviews
@Composable
private fun SessionButtonPreview() {
    PreviewSurface {
        SessionButton(state = SessionButtonState.IDLE, startLabel = "Start session", stopLabel = "Stop", onStart = {}, onStop = {})
        SessionButton(state = SessionButtonState.STARTING, startLabel = "Start session", stopLabel = "Stop", busyLabel = "Starting…", onStart = {}, onStop = {})
        SessionButton(
            state = SessionButtonState.RECORDING,
            startLabel = "Start session",
            stopLabel = "Stop",
            recordingLabel = "Recording",
            elapsedText = "12:34",
            onStart = {},
            onStop = {},
        )
        SessionButton(state = SessionButtonState.STOPPING, startLabel = "Start session", stopLabel = "Stop", busyLabel = "Stopping…", onStart = {}, onStop = {})
    }
}
