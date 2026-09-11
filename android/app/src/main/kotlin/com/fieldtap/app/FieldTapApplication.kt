package com.fieldtap.app

import android.app.Application
import android.content.Context

/**
 * The application. Builds [AppGraph] lazily, once per process.
 *
 * Contract for the implementation (workstream `service-and-tests`): [onCreate] launches launch
 * recovery (com.fieldtap.recovery.LaunchRecovery) on a background dispatcher before any session can
 * start, and does nothing else slow. No analytics, crash-reporting or third-party SDK is initialised
 * here or anywhere.
 *
 * Owner: workstream `service-and-tests`.
 */
class FieldTapApplication : Application() {
    val graph: AppGraph by lazy { DefaultAppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // service-and-tests: start launch recovery here.
    }
}

/** The process's [AppGraph]. */
val Context.appGraph: AppGraph
    get() = (applicationContext as FieldTapApplication).graph
