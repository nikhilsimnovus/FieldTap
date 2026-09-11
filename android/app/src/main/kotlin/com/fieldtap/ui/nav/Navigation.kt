package com.fieldtap.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fieldtap.app.AppGraph
import com.fieldtap.core.privacy.Consent
import com.fieldtap.platform.Permissions
import com.fieldtap.ui.about.AboutScreen
import com.fieldtap.ui.common.graphViewModelFactory
import com.fieldtap.ui.live.LiveScreen
import com.fieldtap.ui.live.LiveViewModel
import com.fieldtap.ui.onboarding.DisclosureScreen
import com.fieldtap.ui.onboarding.OnboardingViewModel
import com.fieldtap.ui.onboarding.PermissionsScreen
import com.fieldtap.ui.probe.ProbeScreen
import com.fieldtap.ui.probe.ProbeViewModel
import com.fieldtap.ui.readiness.ReadinessScreen
import com.fieldtap.ui.readiness.ReadinessViewModel
import com.fieldtap.ui.sessions.SessionDetailScreen
import com.fieldtap.ui.sessions.SessionDetailViewModel
import com.fieldtap.ui.sessions.SessionsScreen
import com.fieldtap.ui.sessions.SessionsViewModel
import com.fieldtap.ui.settings.SettingsScreen
import com.fieldtap.ui.settings.SettingsViewModel
import kotlinx.coroutines.CancellationException

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
 * Where the app opens and where onboarding continues. Pure, so it is unit-tested.
 *
 * Owner: workstream `ui-session`.
 */
object StartDestination {
    /** The disclosure until consent is current, then permissions until precise location is granted, then Live. */
    fun route(consentCurrent: Boolean, preciseLocationGranted: Boolean): String = when {
        !consentCurrent -> Routes.DISCLOSURE
        !preciseLocationGranted -> Routes.PERMISSIONS
        else -> Routes.LIVE
    }

    /** After the disclosure is accepted: permissions, unless precise location is already granted. */
    fun afterDisclosure(preciseLocationGranted: Boolean): String =
        if (preciseLocationGranted) Routes.LIVE else Routes.PERMISSIONS
}

/**
 * The whole navigation graph. Start destination: [Routes.DISCLOSURE] until consent is current
 * (`Consent.isCurrent(settings.consent)`), then [Routes.PERMISSIONS] until precise location is granted,
 * then [Routes.LIVE]. Readiness never refuses a start (decision 7): Live runs the checks itself and opens
 * [Routes.READINESS] only when the user asks for it.
 * Screens of workstream `ui-setup` are called here with the signatures in their files.
 *
 * - The start destination is decided once, from settings read off the main thread, and survives
 *   recreation; until it is known the screen shows only the background, so no location prompt can appear
 *   before the disclosure.
 * - Onboarding steps opened from Live (review consent, allow location) return to Live when done instead of
 *   stacking a second Live.
 * - Every navigation callback is dropped unless its screen is resumed, so a double tap during a
 *   transition cannot push a screen twice or pop past it.
 * - Declining the disclosure leaves the app usable for About only.
 *
 * Owner: workstream `ui-session`.
 */
@Composable
fun FieldTapNavHost(
    graph: AppGraph,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    var startRoute by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(graph) {
        if (startRoute == null) {
            val consentCurrent = try {
                Consent.isCurrent(graph.settings.current().consent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unreadable settings: ask again rather than assume consent.
                false
            }
            startRoute = StartDestination.route(consentCurrent, Permissions.preciseLocationGranted(context))
        }
    }
    val start = startRoute
    if (start == null) {
        Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    NavHost(navController = navController, startDestination = start, modifier = modifier) {
        composable(Routes.DISCLOSURE) {
            val viewModel: OnboardingViewModel = viewModel(factory = graphViewModelFactory(graph) { OnboardingViewModel(it) })
            DisclosureScreen(
                viewModel = viewModel,
                onAccepted = dropUnlessResumed {
                    val next = StartDestination.afterDisclosure(Permissions.preciseLocationGranted(context))
                    navController.completeOnboardingStep(Routes.DISCLOSURE, next)
                },
                onDeclined = dropUnlessResumed { navController.navigate(Routes.ABOUT) { launchSingleTop = true } },
            )
        }
        composable(Routes.PERMISSIONS) {
            PermissionsScreen(
                onDone = dropUnlessResumed { navController.completeOnboardingStep(Routes.PERMISSIONS, Routes.LIVE) },
            )
        }
        composable(Routes.LIVE) {
            val viewModel: LiveViewModel = viewModel(factory = graphViewModelFactory(graph) { LiveViewModel(it) })
            LiveScreen(
                viewModel = viewModel,
                onOpenSessions = dropUnlessResumed { navController.navigate(Routes.SESSIONS) { launchSingleTop = true } },
                onOpenReadiness = dropUnlessResumed { navController.navigate(Routes.READINESS) { launchSingleTop = true } },
                onOpenProbe = dropUnlessResumed { navController.navigate(Routes.PROBE) { launchSingleTop = true } },
                onOpenSettings = dropUnlessResumed { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenAbout = dropUnlessResumed { navController.navigate(Routes.ABOUT) { launchSingleTop = true } },
                onOpenDisclosure = dropUnlessResumed { navController.navigate(Routes.DISCLOSURE) { launchSingleTop = true } },
                onOpenSession = { dirName -> navController.navigate(Routes.sessionDetail(dirName)) { launchSingleTop = true } },
            )
        }
        composable(Routes.SESSIONS) {
            val viewModel: SessionsViewModel = viewModel(factory = graphViewModelFactory(graph) { SessionsViewModel(it) })
            SessionsScreen(
                viewModel = viewModel,
                onOpenSession = { dirName -> navController.openSessionDetail(dirName) },
                onBack = dropUnlessResumed { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.SESSION_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_DIR_NAME) { type = NavType.StringType }),
        ) { entry ->
            val dirName = entry.arguments?.getString(Routes.ARG_DIR_NAME).orEmpty()
            val viewModel: SessionDetailViewModel =
                viewModel(factory = graphViewModelFactory(graph) { SessionDetailViewModel(it, dirName) })
            SessionDetailScreen(
                viewModel = viewModel,
                onBack = dropUnlessResumed { navController.popBackStack() },
            )
        }
        composable(Routes.READINESS) {
            val viewModel: ReadinessViewModel = viewModel(factory = graphViewModelFactory(graph) { ReadinessViewModel(it) })
            ReadinessScreen(viewModel = viewModel, onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.PROBE) {
            val viewModel: ProbeViewModel = viewModel(factory = graphViewModelFactory(graph) { ProbeViewModel(it) })
            ProbeScreen(viewModel = viewModel, onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            val viewModel: SettingsViewModel = viewModel(factory = graphViewModelFactory(graph) { SettingsViewModel(it) })
            SettingsScreen(viewModel = viewModel, onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.ABOUT) {
            AboutScreen(appInfo = graph.appInfo, onBack = dropUnlessResumed { navController.popBackStack() })
        }
    }
}

/**
 * Leaves the onboarding step [current]: back to Live when Live opened it, else on to [next] with the step
 * removed from the back stack, so Back never returns to a finished step.
 */
private fun NavHostController.completeOnboardingStep(current: String, next: String) {
    if (popBackStack(Routes.LIVE, inclusive = false)) return
    navigate(next) {
        popUpTo(current) { inclusive = true }
        launchSingleTop = true
    }
}

/** Opens a session, ignoring a second tap while the first navigation is still running. */
private fun NavHostController.openSessionDetail(dirName: String) {
    val route = Routes.sessionDetail(dirName)
    if (currentBackStackEntry?.destination?.route != Routes.SESSIONS) return
    navigate(route) { launchSingleTop = true }
}
