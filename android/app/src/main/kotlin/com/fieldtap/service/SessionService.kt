package com.fieldtap.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.fieldtap.R
import com.fieldtap.app.FieldTapApplication
import com.fieldtap.core.session.RecorderSnapshot
import com.fieldtap.ui.common.DisplayTime
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The location foreground service that keeps a session (or a soak test) alive after Home or
 * screen-off. Started only by [ServiceSessionControl.start] or [ServiceSoakControl.start] while an
 * activity is visible; never from the background, never at boot.
 *
 * Contract:
 * - [onStartCommand] with [ACTION_START]: call `ServiceCompat.startForeground(this, NOTIFICATION_ID,
 *   notification, FOREGROUND_SERVICE_TYPE_LOCATION)` at once, then let [SessionRuntime] allocate the
 *   directory, build the recorder on its own dispatcher, subscribe it to the MeasurementHub, and start
 *   the NetTestRunner when the request asked for tests. Returns `START_NOT_STICKY`: a restart would be
 *   invisible, and location cannot start then; recovery is explicit instead.
 * - [ACTION_MARK] (notification action): a marker with no note. [ACTION_STOP]: stop with cause `user`, or
 *   end a running soak test.
 * - [ACTION_SOAK]: foreground with the soak notification; runs the telephony ticker only.
 * - The notification ([SessionNotification]) shows elapsed time, serving RSRP, the newest sample's age,
 *   and Stop and Mark; updated at most every [NOTIFICATION_MIN_INTERVAL_MS].
 * - [onDestroy] while recording: the recorder stops with `service_destroyed`.
 * - A refused POST_NOTIFICATIONS does not stop the service: updates are skipped, the service runs on.
 * - When Android refuses `startForeground` (for example the location permission is gone), the start fails
 *   visibly (status returns to Idle) and the service stops; nothing is recorded.
 *
 * Owner: workstream `service-and-tests`.
 */
class SessionService : LifecycleService(), ServiceHost {
    private lateinit var runtime: SessionRuntime
    private lateinit var notifications: SessionNotification

    @Volatile
    private var lastStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        runtime = (application as FieldTapApplication).sessionRuntime
        notifications = SessionNotification(this)
        notifications.ensureChannel()
        runtime.attach(this)
        lifecycleScope.launch { keepNotificationCurrent() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        runtime.attach(this)
        val action = intent?.action
        if (action == ACTION_START || action == ACTION_SOAK) {
            val notification = if (action == ACTION_START) {
                notifications.starting()
            } else {
                notifications.soak(0, runtime.soakDurationMs())
            }
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } catch (e: RuntimeException) {
                runtime.onForegroundRefused(action, e)
                stopHosting()
                return START_NOT_STICKY
            }
        }
        if (!runtime.onServiceStartCommand(action)) stopHosting()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (::runtime.isInitialized) runtime.detach(this)
        if (::notifications.isInitialized) notifications.cancel()
        super.onDestroy()
    }

    override fun stopHosting() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    private suspend fun keepNotificationCurrent() {
        combine(runtime.status, runtime.soakState, runtime.markNotice) { status, soak, notice -> NotificationModel.of(status, soak, notice) }
            .distinctUntilChanged()
            .conflate()
            .collect { model ->
                post(model)
                delay(NOTIFICATION_MIN_INTERVAL_MS)
            }
    }

    private fun post(model: NotificationModel) {
        val notification = when (model) {
            NotificationModel.None -> return
            NotificationModel.Starting -> notifications.starting()
            is NotificationModel.Recording -> notifications.recording(model.snapshot, model.notice)
            is NotificationModel.Soak -> notifications.soak(model.elapsedMs, model.durationMs)
        }
        notifications.post(notification)
    }

    companion object {
        const val ACTION_START: String = "com.fieldtap.service.action.START"
        const val ACTION_MARK: String = "com.fieldtap.service.action.MARK"
        const val ACTION_STOP: String = "com.fieldtap.service.action.STOP"
        const val ACTION_SOAK: String = "com.fieldtap.service.action.SOAK"
        const val NOTIFICATION_ID: Int = 1
        const val CHANNEL_ID: String = "session"

        /** The notification is rebuilt at most this often. */
        const val NOTIFICATION_MIN_INTERVAL_MS: Long = 2_000
    }
}

/**
 * The session notification. Channel [SessionService.CHANNEL_ID], low importance, no sound. Actions are
 * PendingIntents to [SessionService] (immutable), so no exported receiver exists.
 *
 * - Recording: title "Recording session" (or location off, waiting for a location fix, paused in a privacy zone, or
 *   saving), text with the serving RAT, RSRP and the newest sample's age (or what the state means), a chronometer
 *   from the session start, and Mark and Stop. Mark shows only while recording, Stop unless saving; with location
 *   off a Location settings action comes first, because nothing is recorded until location is back on.
 * - For a few seconds after a marker, from Mark here or on Live, the text says which marker was added and when ("Marker 3
 *   added at 10:05"), that it waits for a location fix, or that markers were dropped ([MarkNotice]); never its note.
 * - Soak: elapsed against planned time, a progress bar, and Stop test.
 * - Tapping opens the app. Nothing on it names the session or a place, so it is safe on the lock screen.
 *
 * Owner: workstream `service-and-tests`.
 */
class SessionNotification(private val context: Context) {
    private val openApp: PendingIntent? by lazy {
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            PendingIntent.getActivity(context, REQUEST_OPEN, launch, PENDING_FLAGS)
        }
    }

    private val markIntent: PendingIntent by lazy { serviceIntent(SessionService.ACTION_MARK, REQUEST_MARK) }

    private val stopIntent: PendingIntent by lazy { serviceIntent(SessionService.ACTION_STOP, REQUEST_STOP) }

    private val locationSettingsIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            context,
            REQUEST_LOCATION_SETTINGS,
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PENDING_FLAGS,
        )
    }

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            SessionService.CHANNEL_ID,
            context.getString(R.string.notification_channel_session),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setDescription(context.getString(R.string.notification_channel_session_description))
        channel.setShowBadge(false)
        channel.setSound(null, null)
        channel.enableVibration(false)
        manager.createNotificationChannel(channel)
    }

    /** Shown by `startForeground` before the recorder exists. */
    fun starting(): Notification = builder()
        .setContentTitle(context.getString(R.string.notification_starting_title))
        .setContentText(context.getString(R.string.notification_starting_text))
        .setProgress(0, 0, true)
        .build()

    internal fun recording(snapshot: RecorderSnapshot, notice: MarkNotice? = null): Notification {
        val headline = NotificationText.headline(snapshot)
        val title = when (headline) {
            RecordingHeadline.RECORDING -> context.getString(R.string.notification_recording_title)
            RecordingHeadline.PAUSED -> context.getString(R.string.notification_paused_title)
            RecordingHeadline.WAITING_FOR_LOCATION -> context.getString(R.string.notification_waiting_title)
            RecordingHeadline.LOCATION_OFF -> context.getString(R.string.notification_location_off_title)
            RecordingHeadline.SAVING -> context.getString(R.string.notification_saving_title)
        }
        val shownNotice = NotificationText.shownNotice(snapshot, notice)
        val text = if (shownNotice != null) {
            noticeText(shownNotice)
        } else {
            when (headline) {
                RecordingHeadline.RECORDING -> servingText(snapshot)
                RecordingHeadline.PAUSED -> context.getString(R.string.notification_paused_text)
                RecordingHeadline.WAITING_FOR_LOCATION -> context.getString(R.string.notification_waiting_text)
                RecordingHeadline.LOCATION_OFF -> context.getString(R.string.notification_location_off_text)
                RecordingHeadline.SAVING -> context.getString(R.string.notification_saving_text)
            }
        }
        val builder = builder()
            .setContentTitle(title)
            .setContentText(text)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setWhen(snapshot.startedUtcMs)
        if (headline == RecordingHeadline.LOCATION_OFF) {
            builder.addAction(0, context.getString(R.string.notification_action_location_settings), locationSettingsIntent)
        }
        if (headline == RecordingHeadline.RECORDING) {
            builder.addAction(0, context.getString(R.string.notification_action_mark), markIntent)
        }
        if (headline != RecordingHeadline.SAVING) {
            builder.addAction(0, context.getString(R.string.notification_action_stop), stopIntent)
        }
        return builder.build()
    }

    fun soak(elapsedMs: Long, durationMs: Long): Notification {
        val duration = durationMs.coerceAtLeast(1)
        val elapsed = elapsedMs.coerceIn(0, duration)
        val text = context.getString(
            R.string.notification_soak_text,
            NotificationText.minutesSeconds(elapsed),
            NotificationText.minutesSeconds(duration),
        )
        return builder()
            .setContentTitle(context.getString(R.string.notification_soak_title))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(PROGRESS_MAX, (elapsed * PROGRESS_MAX / duration).toInt(), false)
            .addAction(0, context.getString(R.string.notification_action_stop_test), stopIntent)
            .build()
    }

    /** Posts an update, unless notifications are refused; the foreground service runs on either way. */
    @SuppressLint("MissingPermission") // Checked by areNotificationsEnabled(); a late refusal is caught.
    fun post(notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(SessionService.NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission was refused", e)
        }
    }

    /** Removes the notification, for a service on its way out. */
    fun cancel() {
        NotificationManagerCompat.from(context).cancel(SessionService.NOTIFICATION_ID)
    }

    private fun noticeText(notice: MarkNotice): String = when (notice) {
        is MarkNotice.Added ->
            context.getString(R.string.notification_marker_added, notice.number, DisplayTime.time(notice.wallMs, locale = locale()))
        is MarkNotice.Held -> context.getString(R.string.notification_marker_held, notice.number)
        is MarkNotice.Dropped -> context.resources.getQuantityString(R.plurals.notification_markers_dropped, notice.count, notice.count)
    }

    private fun servingText(snapshot: RecorderSnapshot): String {
        val rat = snapshot.servingRat
        val ageMs = snapshot.newestSampleAgeMs
        if (rat == null || ageMs == null) return context.getString(R.string.notification_no_serving_text)
        val age = NotificationText.age(ageMs, locale())
        val rsrp = snapshot.servingRsrpDbm
        return if (rsrp != null) {
            context.getString(R.string.notification_serving_text, NotificationText.ratLabel(rat), rsrp, age)
        } else {
            context.getString(R.string.notification_serving_no_rsrp_text, NotificationText.ratLabel(rat), age)
        }
    }

    private fun locale(): Locale {
        val locales = context.resources.configuration.locales
        return if (locales.isEmpty) Locale.getDefault() else locales.get(0)
    }

    private fun builder(): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, SessionService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_fieldtap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        openApp?.let { builder.setContentIntent(it) }
        return builder
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, SessionService::class.java).setAction(action),
            PENDING_FLAGS,
        )

    private companion object {
        const val TAG = "FieldTapSession"
        const val REQUEST_OPEN = 0
        const val REQUEST_MARK = 1
        const val REQUEST_STOP = 2
        const val REQUEST_LOCATION_SETTINGS = 3
        const val PROGRESS_MAX = 1_000
        const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
