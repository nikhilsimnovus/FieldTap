package com.fieldtap.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * Whether the user has asked the system to remove animations. Provided by
 * [com.fieldtap.ui.FieldTapTheme]; the default (`false`) serves previews without a theme.
 *
 * Read it and pass it to the [Motion] specs, for example
 * `animateFloatAsState(spec = Motion.spatial(LocalReducedMotion.current))`, so every motion collapses to
 * an instant state change when animations are off.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Reads the system "remove animations" setting (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`) and keeps
 * it current: a [ContentObserver] updates the value if the user toggles it while the app is open. Call it
 * once in the theme and provide the result through [LocalReducedMotion].
 */
@Composable
fun rememberReducedMotion(): Boolean {
    if (LocalView.current.isInEditMode) return false
    val resolver = LocalContext.current.contentResolver
    var reduced by remember(resolver) { mutableStateOf(readReducedMotion(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = readReducedMotion(resolver)
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        resolver.registerContentObserver(uri, false, observer)
        reduced = readReducedMotion(resolver)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/** True when the animator duration scale is 0 (animations removed). Any read error is treated as "not reduced". */
private fun readReducedMotion(resolver: ContentResolver): Boolean =
    runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        .getOrDefault(false)
