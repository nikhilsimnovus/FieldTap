package com.fieldtap.app

import android.app.Application
import android.content.Context
import com.fieldtap.service.SessionRuntime

/**
 * The application. Builds [AppGraph] lazily, once per process.
 *
 * [onCreate] launches launch recovery (com.fieldtap.recovery.LaunchRecovery) on a background dispatcher
 * before any session can start, and does nothing else slow: the graph builds nothing that touches the disk
 * or a platform service until it is used. `SessionControl.start` waits for recovery to finish, so a start
 * never races it. No analytics, crash-reporting or third-party SDK is initialised here or anywhere.
 *
 * Owner: workstream `service-and-tests`.
 */
class FieldTapApplication : Application() {
    private val defaultGraph: DefaultAppGraph by lazy { DefaultAppGraph(this) }

    val graph: AppGraph get() = defaultGraph

    /** The session lifecycle owner, for [com.fieldtap.service.SessionService]. */
    internal val sessionRuntime: SessionRuntime get() = defaultGraph.runtime

    override fun onCreate() {
        super.onCreate()
        defaultGraph.startLaunchRecovery()
    }
}

/** The process's [AppGraph]. */
val Context.appGraph: AppGraph
    get() = (applicationContext as FieldTapApplication).graph
