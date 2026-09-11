package com.fieldtap.ui.nav

import com.fieldtap.format.SessionDirName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {

    @Test
    fun sessionDetailFillsTheRouteTemplate() {
        val dirName = "20260910-143000_Mall-walk-north-path"

        val route = Routes.sessionDetail(dirName)

        assertEquals("sessions/20260910-143000_Mall-walk-north-path", route)
        assertEquals(Routes.SESSION_DETAIL.replace("{${Routes.ARG_DIR_NAME}}", dirName), route)
        assertTrue("directory names need no escaping in a route", SessionDirName.PATTERN.matches(dirName))
    }

    @Test
    fun routesAreDistinct() {
        val routes = listOf(
            Routes.DISCLOSURE, Routes.PERMISSIONS, Routes.LIVE, Routes.SESSIONS, Routes.SESSION_DETAIL,
            Routes.READINESS, Routes.PROBE, Routes.SETTINGS, Routes.ABOUT,
        )
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun theAppOpensOnTheFirstUnfinishedOnboardingStep() {
        assertEquals(Routes.DISCLOSURE, StartDestination.route(consentCurrent = false, preciseLocationGranted = false))
        assertEquals("no location prompt before the disclosure", Routes.DISCLOSURE, StartDestination.route(consentCurrent = false, preciseLocationGranted = true))
        assertEquals(Routes.PERMISSIONS, StartDestination.route(consentCurrent = true, preciseLocationGranted = false))
        assertEquals(Routes.LIVE, StartDestination.route(consentCurrent = true, preciseLocationGranted = true))
    }

    @Test
    fun acceptingTheDisclosureSkipsPermissionsAlreadyGranted() {
        assertEquals(Routes.PERMISSIONS, StartDestination.afterDisclosure(preciseLocationGranted = false))
        assertEquals(Routes.LIVE, StartDestination.afterDisclosure(preciseLocationGranted = true))
    }
}
