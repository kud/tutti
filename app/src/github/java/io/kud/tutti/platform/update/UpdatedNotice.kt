package io.kud.tutti.platform.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.kud.tutti.BuildConfig
import io.kud.tutti.R
import io.kud.tutti.protocol.update.AppVersion
import io.kud.tutti.ui.about.ACTION_WHATS_NEW
import io.kud.tutti.ui.about.installedNotes

/**
 * "tutti was updated", once, after an install this app's updater started (docs/architecture.md,
 * "In-app updates"). Android kills the app to replace it and, with a session install, shows no
 * "Open" of its own; a receiver may not start an activity from the background (Android 10+), so a
 * quiet notification is what brings the person back: one tap opens the remote, and it goes as
 * soon as the app is in front again.
 */
internal object UpdatedNotice {
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 0x5A20
    private const val PREFS = "updates"
    /** The version the updater handed to Android, set at commit and read once by the receiver. */
    private const val KEY_PENDING = "pending_install"

    fun expect(context: Context, version: String) {
        prefs(context).edit().putString(KEY_PENDING, version).apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().remove(KEY_PENDING).apply()
    }

    /**
     * Runs in the replaced app's first process. Only an install the updater asked for, landing on the
     * version it asked for, is announced: a sideload or a build from the IDE stays silent.
     */
    fun onReplaced(context: Context) {
        val expected = prefs(context).getString(KEY_PENDING, null)
        forget(context)
        val landed = AppVersion.parse(BuildConfig.VERSION_NAME)
        if (expected == null || landed == null || AppVersion.parse(expected) != landed) return
        post(context)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    // canPost() is the permission check; lint cannot follow it across the call.
    @SuppressLint("MissingPermission")
    private fun post(context: Context) {
        if (!canPost(context)) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_done_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.update_done_channel_description)
                setShowBadge(false)
            },
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        // With notes for this version, the line names its lead and the tap opens What's new over the
        // remote (back lands on the remote); without, it says the version and opens the remote.
        val notes = installedNotes()
        if (notes != null) launch.setAction(ACTION_WHATS_NEW)
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (notes != null) {
            context.getString(R.string.update_done_notes, BuildConfig.VERSION_NAME, context.getString(notes.headline))
        } else {
            context.getString(R.string.update_done_text, BuildConfig.VERSION_NAME)
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check_circle)
            .setContentTitle(context.getString(R.string.update_done_title, context.getString(R.string.app_name)))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Android's own word that this app was just replaced (manifest-declared: the app is not running yet). */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) UpdatedNotice.onReplaced(context)
    }
}
