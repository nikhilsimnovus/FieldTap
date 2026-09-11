package com.fieldtap.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.fieldtap.app.AppGraph

/**
 * Routes. Directory names are `[A-Za-z0-9._-]` only, so they go into a route unescaped.
 *
 * Owner: workstream `ui-session`.
 */
object Routes {
    const val DISCLOSURE: String = "disclosure"
    const val PERMISSIONS: String = "permissions"
    const val LIVE: String = "live"
    const val SESSIONS: String = "sessions"
    const val SESSION_DETAIL: String = "sessions/{dirName}"
    const val READINESS: String = "readiness"
    const val PROBE: String = "probe"
    const val SETTINGS: String = "settings"
    const val ABOUT: String = "about"

    const val ARG_DIR_NAME: String = "dirName"

    fun sessionDetail(dirName: String): String = "sessions/$dirName"
}

/**
 * The whole navigation graph. Start destination: [Routes.DISCLOSURE] until consent is current
 * (`Consent.isCurrent(settings.consent)`), then [Routes.PERMISSIONS] until precise location is granted,
 * then [Routes.LIVE]. A START refused with READINESS_REQUIRED navigates to [Routes.READINESS].
 * Screens of workstream `ui-setup` are called here with the signatures in their files.
 *
 * Owner: workstream `ui-session`.
 */
@Composable
fun FieldTapNavHost(
    graph: AppGraph,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    TODO("ui-session")
}
