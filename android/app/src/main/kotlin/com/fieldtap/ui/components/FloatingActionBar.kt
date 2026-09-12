package com.fieldtap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.fieldtap.ui.theme.FieldTapDesign
import com.fieldtap.ui.theme.ShapeRoles
import com.fieldtap.ui.theme.Sizes
import com.fieldtap.ui.theme.Spacing

/**
 * The inset **floating action bar** that holds a screen's primary actions in portrait: the Live
 * "Start session" → Mark / Stop, or Share / Delete on Session detail. A plain raised [Surface] + [Row]
 * (Material 3 1.4.0 has no `FloatingToolbar`), inset [Sizes.ActionBarInset] from the screen's left, right
 * and bottom edges, over the navigation bar. It carries the card recipe — a soft shadow in light, a
 * hairline in both themes — and lifts on `surfaceContainerLow` (light) / `surfaceContainerHigh` (dark).
 *
 * It is not a focus trap; the [content] controls (a [SessionButton], or Share/Delete buttons) keep their
 * own semantics. In a short, wide window the screen uses the [Sizes.ActionRailWidth] column instead.
 */
@Composable
fun FieldTapFloatingActionBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val color = if (FieldTapDesign.colors.isDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Sizes.ActionBarInset, vertical = Sizes.ActionBarInset),
        shape = ShapeRoles.Card,
        color = color,
        shadowElevation = cardShadowElevation(raised = true),
        border = cardHairline(),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            content = content,
        )
    }
}

@FieldTapPreviews
@Composable
private fun FloatingActionBarPreview() {
    PreviewSurface {
        FieldTapFloatingActionBar {
            SessionButton(
                state = SessionButtonState.IDLE,
                startLabel = "Start session",
                stopLabel = "Stop",
                onStart = {},
                onStop = {},
            )
        }
    }
}
