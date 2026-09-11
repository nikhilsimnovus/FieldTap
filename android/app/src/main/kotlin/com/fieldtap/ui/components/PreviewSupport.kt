package com.fieldtap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.fieldtap.ui.FieldTapTheme
import com.fieldtap.ui.theme.Spacing

/**
 * The looks every component and screen preview is checked in: light, dark, and light at font scale 1.3
 * (where layouts break first). Use it instead of `@Preview` on a preview function.
 */
@PreviewLightDark
@Preview(name = "Font scale 1.3", fontScale = 1.3f)
annotation class FieldTapPreviews

/**
 * The frame of a preview: [FieldTapTheme] (light or dark from the preview's uiMode), the background
 * surface and the screen gutter, with [Spacing.Md] between items.
 */
@Composable
fun PreviewSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    FieldTapTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = modifier.padding(Spacing.Lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.Md),
                content = content,
            )
        }
    }
}
