package com.fieldtap.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldtap.ui.theme.Durations
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.FieldTapIcons
import com.fieldtap.ui.theme.LocalReducedMotion
import com.fieldtap.ui.theme.Motion
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
 *   the running state unmistakable at a glance. Where the button is too narrow for all of it ([recordingIsCompact]:
 *   beside Mark on a 320 dp screen, or at font scale 1.3 on a 360 dp one) the label and the Stop word are left out,
 *   so the elapsed time stays whole; it never wraps, and shrinks before it would clip. TalkBack reads
 *   "Recording, 12:34, Stop" in both layouts.
 *
 * Full width, 64 dp tall. Stopping ends the session for good, so confirm it with a dialog in the
 * screen before calling the view model.
 *
 * [stacked], for the narrow action rail beside the content in landscape: IDLE puts the play icon above a one-line
 * [startLabel] that shrinks to 14 sp before it would wrap; pass a short label ("Start") and the full words as
 * [startContentDescription] ("Start session"), which TalkBack reads instead.
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
    stacked: Boolean = false,
    startContentDescription: String? = null,
) {
    val colors = FieldTapDesign.colors
    val recording = state == SessionButtonState.RECORDING
    val busy = state == SessionButtonState.STARTING || state == SessionButtonState.STOPPING
    val reduced = LocalReducedMotion.current
    val container by animateColorAsState(
        targetValue = if (recording) colors.recording.color else MaterialTheme.colorScheme.primary,
        animationSpec = Motion.effect(reduced),
        label = "sessionButtonContainer",
    )
    val content by animateColorAsState(
        targetValue = if (recording) colors.recording.onColor else MaterialTheme.colorScheme.onPrimary,
        animationSpec = Motion.effect(reduced),
        label = "sessionButtonContent",
    )
    Button(
        onClick = { if (recording) onStop() else onStart() },
        enabled = enabled && !busy,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.PrimaryButtonHeight)
            .then(
                when {
                    recording -> Modifier.semantics { contentDescription = listOfNotNull(recordingLabel, elapsedText, stopLabel).joinToString(", ") }
                    state == SessionButtonState.IDLE && startContentDescription != null ->
                        Modifier.semantics { contentDescription = startContentDescription }
                    else -> Modifier
                },
            ),
        shape = ShapeRoles.Control,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = if (stacked) Spacing.Md else Spacing.Xl, vertical = Spacing.Sm),
    ) {
        when (state) {
            SessionButtonState.IDLE -> if (stacked) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.Xxs)) {
                    Icon(imageVector = FieldTapIcons.Play, contentDescription = null, modifier = Modifier.size(Sizes.Icon))
                    val labelStyle = MaterialTheme.typography.titleMedium
                    Text(
                        text = startLabel,
                        style = labelStyle,
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = MIN_STACKED_LABEL_SP.sp, maxFontSize = labelStyle.fontSize, stepSize = 1.sp),
                    )
                }
            } else {
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
            SessionButtonState.RECORDING -> BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val compact = recordingIsCompact(maxWidth, LocalDensity.current.fontScale)
                val elapsedStyle = FieldTapDesign.numeric.medium
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RecordingDot(color = content)
                    Spacer(modifier = Modifier.width(if (compact) Spacing.Sm else Spacing.Md))
                    Column(modifier = Modifier.weight(1f)) {
                        if (!compact && recordingLabel != null) {
                            Text(text = recordingLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (elapsedText != null) {
                            Text(
                                text = elapsedText,
                                style = elapsedStyle,
                                maxLines = 1,
                                softWrap = false,
                                autoSize = TextAutoSize.StepBased(
                                    minFontSize = MIN_ELAPSED_FONT_SIZE_SP.sp,
                                    maxFontSize = elapsedStyle.fontSize,
                                    stepSize = 1.sp,
                                ),
                            )
                        }
                    }
                    Icon(imageVector = FieldTapIcons.Stop, contentDescription = null, modifier = Modifier.size(Sizes.Icon))
                    if (!compact) {
                        Spacer(modifier = Modifier.width(Spacing.Sm))
                        Text(text = stopLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The content width the recording state needs for its label and the Stop word at font scale 1. */
internal val RecordingFullContentWidth: Dp = 168.dp

/** The smallest the elapsed time shrinks to before it would clip, for hours on a narrow button. */
private const val MIN_ELAPSED_FONT_SIZE_SP: Int = 12

/** The smallest a stacked button's label shrinks to before it would clip. */
private const val MIN_STACKED_LABEL_SP: Int = 14

/**
 * Whether the recording state leaves out its label and the Stop word to keep the elapsed time whole: when the
 * content of the button is narrower than [RecordingFullContentWidth] grown by the font scale. Before this rule the
 * end-to-end screenshots showed "Rec" and "1:" beside the Mark button on the 320 dp emulator screen.
 */
internal fun recordingIsCompact(contentWidth: Dp, fontScale: Float): Boolean = contentWidth < RecordingFullContentWidth * fontScale

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
    val dotAlpha = if (LocalReducedMotion.current) {
        // Reduced motion: a steady (non-pulsing) dot; the "Recording" label still says it is live.
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "recordingDot")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(animation = tween(Durations.PULSE), repeatMode = RepeatMode.Reverse),
            label = "recordingDotAlpha",
        )
        alpha
    }
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { this.alpha = dotAlpha }
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
