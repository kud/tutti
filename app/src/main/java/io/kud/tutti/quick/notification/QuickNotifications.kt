package io.kud.tutti.quick.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.kud.tutti.MainActivity
import io.kud.tutti.R
import io.kud.tutti.quick.QuickControls
import io.kud.tutti.quick.QuickPreferences
import io.kud.tutti.quick.QuickState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The ongoing control notification: volume −/+, mute and the active scene,
 * visible on the lock screen, shown only while the TV is on, the controller is
 * connected and the user has it enabled.
 *
 * Why a plain notification with actions:
 * - No MediaSession. A session with a remote VolumeProvider would make the
 *   hardware volume keys drive the bar system-wide whenever the TV is on;
 *   design.md scopes that to "while the app is in front". Lock-screen actions
 *   only need VISIBILITY_PUBLIC on the notification and the channel.
 * - No foreground service. The controller lives in the app process and the
 *   notification mirrors it; it is not what keeps the connection alive. An
 *   FGS (it would be `connectedDevice` on Android 14+) is also not startable
 *   from the background, which is exactly when the TV tends to turn on.
 *   Trade-off: if the process dies, the last notification can linger until the
 *   next start reconciles it ([QuickRuntime.start] cancels it while disconnected).
 */
object QuickNotifications {
    const val CHANNEL_ID = "quick_remote"
    private const val NOTIFICATION_ID = 0x5A10

    /** Coalesces bursts (a volume drag) under the system's notification rate limit. */
    private const val POST_DEBOUNCE_MS = 150L

    suspend fun run(context: Context, controls: QuickControls, preferences: QuickPreferences) {
        ensureChannel(context)
        combine(controls.state, preferences.notificationEnabled) { state, enabled ->
            state.takeIf { enabled && it.connected && it.power }
        }
            .distinctUntilChanged()
            .collectLatest { state ->
                if (state == null) {
                    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
                } else {
                    delay(POST_DEBOUNCE_MS)
                    post(context, state)
                }
            }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    // canPost() is the permission check; lint cannot follow it across the call.
    @SuppressLint("MissingPermission")
    private fun post(context: Context, state: QuickState) {
        if (!canPost(context)) return
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, state))
    }

    private fun build(context: Context, state: QuickState): Notification {
        val scene = state.activeScene
        val sceneLabel = scene?.let {
            if (state.activeSceneModified) context.getString(R.string.quick_scene_modified, it.name) else it.name
        }
        val volumeLabel = if (state.muted) {
            context.getString(R.string.quick_status_muted)
        } else {
            context.getString(R.string.quick_volume_value, state.volume)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.quick_ic_tv)
            .setContentTitle(sceneLabel ?: context.getString(R.string.app_name))
            .setContentText(volumeLabel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context))
            .addAction(
                R.drawable.quick_ic_remove,
                context.getString(R.string.quick_notification_volume_down),
                action(context, QuickNotificationReceiver.ACTION_VOLUME_DOWN, 1),
            )
            .addAction(
                if (state.muted) R.drawable.quick_ic_volume_up else R.drawable.quick_ic_volume_off,
                context.getString(
                    if (state.muted) R.string.quick_notification_unmute else R.string.quick_notification_mute,
                ),
                action(context, QuickNotificationReceiver.ACTION_MUTE, 2),
            )
            .addAction(
                R.drawable.quick_ic_add,
                context.getString(R.string.quick_notification_volume_up),
                action(context, QuickNotificationReceiver.ACTION_VOLUME_UP, 3),
            )
        if (scene != null) builder.setColor(scene.color.toInt())
        return builder.build()
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.quick_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.quick_notification_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, QuickNotificationReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
