package com.fieldtap.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.fieldtap.ui.theme.FieldTapIcons

/**
 * The top app bar of every screen: title, an optional back arrow, and actions. It keeps Material's
 * experimental opt-in in one place, so screens need none. Pass it to `Scaffold(topBar = ...)`.
 *
 * The back arrow shows only when both [onNavigateUp] and [navigateUpContentDescription] are given
 * ("Back" from a string resource). Use [TopBarAction] for actions, at most two plus an overflow menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldTapTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateUp: (() -> Unit)? = null,
    navigateUpContentDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        navigationIcon = {
            if (onNavigateUp != null && navigateUpContentDescription != null) {
                IconButton(onClick = onNavigateUp) {
                    Icon(imageVector = FieldTapIcons.ArrowBack, contentDescription = navigateUpContentDescription)
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/** A 48 dp icon action for [FieldTapTopBar]; [contentDescription] is required because it has no text. */
@Composable
fun TopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

@FieldTapPreviews
@Composable
private fun FieldTapTopBarPreview() {
    PreviewSurface {
        FieldTapTopBar(
            title = "Session detail",
            onNavigateUp = {},
            navigateUpContentDescription = "Back",
            actions = {
                TopBarAction(icon = FieldTapIcons.Share, contentDescription = "Share", onClick = {})
                TopBarAction(icon = FieldTapIcons.Delete, contentDescription = "Delete", onClick = {})
            },
        )
        FieldTapTopBar(
            title = "Live",
            actions = {
                TopBarAction(icon = FieldTapIcons.Sessions, contentDescription = "Sessions", onClick = {})
                TopBarAction(icon = FieldTapIcons.Tune, contentDescription = "Settings", onClick = {})
            },
        )
    }
}
