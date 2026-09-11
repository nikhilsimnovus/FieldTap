package com.fieldtap.ui.common

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.fieldtap.app.AppGraph
import java.io.File

/**
 * A `ViewModelProvider.Factory` that builds a view model from the [AppGraph]
 * (`viewModelFactory { initializer { create(graph) } }`). Every screen's view model takes only the graph
 * (plus saved-state arguments), so tests pass a fake graph.
 *
 * Owner: workstream `ui-session`.
 */
fun <VM : ViewModel> graphViewModelFactory(graph: AppGraph, create: (AppGraph) -> VM): ViewModelProvider.Factory =
    TODO("ui-session")

/**
 * Shares a file from `<cacheDir>/exports/` through the app's FileProvider with `ACTION_SEND` and a
 * chooser, granting read permission only. Never shares a session directory file directly.
 *
 * Owner: workstream `ui-session`.
 */
object FileSharer {
    /** Matches the manifest's `${applicationId}.fileprovider`. */
    fun authority(context: Context): String = context.packageName + ".fileprovider"

    fun share(context: Context, file: File, mimeType: String, subject: String, text: String?): Unit =
        TODO("ui-session")
}
