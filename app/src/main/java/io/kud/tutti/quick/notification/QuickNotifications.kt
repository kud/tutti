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
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import io.kud.tutti.MainActivity
import io.kud.tutti.R
import io.kud.tutti.platform.data.IconBitmaps
import io.kud.tutti.quick.QuickControls
import io.kud.tutti.quick.QuickPreferences
import io.kud.tutti.quick.QuickRuntime
import io.kud.tutti.quick.VolumeTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * The ongoing media-style control notification: what the TV is showing, then
 * play/pause, volume −, mute and volume +, on the lock screen too. Shown only
 * while the TV is on, the controller is connected and the user has it enabled.
 *
 * Backed by a framework [MediaSession] (no androidx.media dependency: minSdk 29
 * has everything, and [Notification.MediaStyle] is the platform's own):
 * - Android 13+ draws the media controls from the session, not from the
 *   notification's actions, so the volume buttons are also PlaybackState custom
 *   actions; with no skip actions they fill the previous/next slots.
 * - A remote [VolumeProvider] (absolute, 0..the device's max) makes the phone's
 *   volume keys drive the bar, or the TV with no bar, whenever the session is
 *   active: by default for as long as the notification shows ([VOLUME_KEYS]).
 *   Android routes keys to the most recent *playing* session, so a phone app
 *   playing its own media takes them back while it plays.
 * - No foreground service. The controller lives in the app process and the
 *   notification mirrors it; it is not what keeps the connection alive. An
 *   FGS (it would be `connectedDevice` on Android 14+) is also not startable
 *   from the background, which is exactly when the TV tends to turn on.
 *   Trade-off: if the process dies, the last notification can linger until the
 *   next start reconciles it ([QuickRuntime.start] cancels it while disconnected);
 *   its session dies with the process, so the volume keys go back to the phone.
 */
object QuickNotifications {
    const val CHANNEL_ID = "quick_remote"
    private const val NOTIFICATION_ID = 0x5A10

    /**
     * At least this long between posts, trailing (see [postDelay]). Android sheds
     * updates past ~5/s per app; the old 150 ms debounce allowed ~6.7/s during the
     * connect burst, which could drop the post that carried the real volume.
     */
    private const val POST_INTERVAL_MS = 400L

    /** When the session takes the phone's volume keys. See docs/design.md, "Notification". */
    private val VOLUME_KEYS = VolumeKeysScope.WHILE_SHOWN

    private const val ICON_PX = 192

    private class Live(val session: MediaSession) {
        var provider: VolumeProvider? = null
        var providerKey: Pair<VolumeTarget, Int>? = null
    }

    suspend fun run(context: Context, controls: QuickControls, preferences: QuickPreferences) {
        ensureChannel(context)
        var live: Live? = null
        var lastPost: Long? = null
        try {
            combine(controls.state, preferences.notificationEnabled) { state, enabled ->
                state.takeIf { enabled && it.connected && it.power }?.let(::cardFor)
            }
                .distinctUntilChanged()
                .collectLatest { card ->
                    if (card == null || !canPost(context)) {
                        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
                        live?.session?.release()
                        live = null
                        return@collectLatest
                    }
                    val current = live ?: Live(newSession(context)).also { live = it }
                    val icon = withContext(Dispatchers.IO) { largeIcon(context, card) }
                    // The session moves at once (the volume keys read it); the post waits its turn.
                    updateSession(context, current, card, icon)
                    delay(postDelay(SystemClock.elapsedRealtime(), lastPost, POST_INTERVAL_MS))
                    post(context, build(context, card, icon, current.session.sessionToken))
                    lastPost = SystemClock.elapsedRealtime()
                }
        } finally {
            live?.session?.release()
        }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    // canPost() is the permission check; lint cannot follow it across the call.
    @SuppressLint("MissingPermission")
    private fun post(context: Context, notification: Notification) {
        if (!canPost(context)) return
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Runs a button or volume-key command on the process scope, like the receiver does. */
    private fun send(context: Context, command: MediaCommand) {
        QuickRuntime.act(context) { perform(command) }
    }

    private fun newSession(context: Context): MediaSession =
        MediaSession(context, "tutti").apply {
            setSessionActivity(openApp(context))
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = send(context, commandFor(MediaAction.PLAY_PAUSE))

                override fun onPause() = send(context, commandFor(MediaAction.PLAY_PAUSE))

                override fun onCustomAction(action: String, extras: Bundle?) {
                    val media = QuickNotificationReceiver.mediaAction(action) ?: return
                    send(context, commandFor(media))
                }
            })
        }

    private fun updateSession(context: Context, live: Live, card: MediaCard, icon: Bitmap) {
        val session = live.session
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title(context, card))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle(context, card))
                .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, icon)
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                // The TV does not report play/pause: PLAYING keeps this the session the
                // volume keys go to, and the button is a toggle either way.
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .addCustomAction(customAction(context, MediaAction.VOLUME_DOWN, card))
                .addCustomAction(customAction(context, MediaAction.VOLUME_UP, card))
                .addCustomAction(customAction(context, MediaAction.MUTE, card))
                .build(),
        )

        val remote = card.remote
        if (remote == null) {
            if (live.provider != null) {
                session.setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                live.provider = null
                live.providerKey = null
            }
        } else if (live.providerKey != (remote.target to remote.max)) {
            // VolumeProvider's maximum is fixed at construction: a new cap or device needs a new one.
            val provider = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, remote.max, remote.current) {
                override fun onAdjustVolume(direction: Int) {
                    adjustCommand(direction)?.let { send(context, it) }
                }

                override fun onSetVolumeTo(volume: Int) = send(context, setLevelCommand(volume, maxVolume))
            }
            session.setPlaybackToRemote(provider)
            live.provider = provider
            live.providerKey = remote.target to remote.max
        } else {
            live.provider?.currentVolume = remote.current
        }
        session.isActive = sessionTakesVolumeKeys(VOLUME_KEYS, shown = true, appInFront = false)
    }

    private fun title(context: Context, card: MediaCard): String =
        card.appLabel ?: card.sceneName ?: context.getString(R.string.app_name)

    /** "BRAVIA 8 · Bar 9 · Bar vol. 10": the devices, then whose level it is. */
    private fun subtitle(context: Context, card: MediaCard): String {
        val level = card.level?.let { level ->
            val bar = level.target == VolumeTarget.BAR
            if (level.muted) {
                context.getString(if (bar) R.string.quick_level_bar_muted else R.string.quick_level_tv_muted)
            } else {
                context.getString(if (bar) R.string.quick_level_bar else R.string.quick_level_tv, level.level)
            }
        }
        // Stopped at the high-volume guard: say that going above needs the app.
        val guard = card.guardHeld?.let { context.getString(R.string.quick_guard_held, it) }
        return listOfNotNull(card.devices.ifEmpty { null }, level, guard).joinToString(" · ")
    }

    /** The TV app's own icon when tutti has cached it, else tutti's mark. */
    private fun largeIcon(context: Context, card: MediaCard): Bitmap {
        card.appIconPath?.let { path -> runCatching { IconBitmaps.load(path) }.getOrNull()?.let { return it } }
        val mark = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        return mark?.toBitmap(ICON_PX, ICON_PX) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private fun actionIcon(action: MediaAction, card: MediaCard): Int = when (action) {
        MediaAction.PLAY_PAUSE -> R.drawable.ic_play_pause
        MediaAction.VOLUME_DOWN -> R.drawable.quick_ic_remove
        // The icon shows the state (a struck speaker while muted); the label says what a tap does.
        MediaAction.MUTE -> if (card.muted) R.drawable.quick_ic_volume_off else R.drawable.quick_ic_volume_up
        MediaAction.VOLUME_UP -> R.drawable.quick_ic_add
    }

    private fun actionLabel(context: Context, action: MediaAction, card: MediaCard): String = context.getString(
        when (action) {
            MediaAction.PLAY_PAUSE -> R.string.quick_notification_play_pause
            MediaAction.VOLUME_DOWN -> R.string.quick_notification_volume_down
            MediaAction.MUTE -> if (card.muted) R.string.quick_notification_unmute else R.string.quick_notification_mute
            MediaAction.VOLUME_UP -> R.string.quick_notification_volume_up
        },
    )

    private fun customAction(context: Context, action: MediaAction, card: MediaCard): PlaybackState.CustomAction =
        PlaybackState.CustomAction.Builder(
            QuickNotificationReceiver.intentAction(action),
            actionLabel(context, action, card),
            actionIcon(action, card),
        ).build()

    private fun build(context: Context, card: MediaCard, icon: Bitmap, token: MediaSession.Token): Notification {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.quick_ic_tv)
            .setLargeIcon(icon)
            .setContentTitle(title(context, card))
            .setContentText(subtitle(context, card))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentIntent(openApp(context))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(*COMPACT_ACTIONS),
            )
        MediaAction.entries.forEachIndexed { index, action ->
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, actionIcon(action, card)),
                    actionLabel(context, action, card),
                    pendingAction(context, QuickNotificationReceiver.intentAction(action), index + 1),
                ).build(),
            )
        }
        card.sceneColor?.let { builder.setColor(it.toInt()) }
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

    private fun pendingAction(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, QuickNotificationReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** Sends one [MediaCommand] through the quick controls: the bar when configured, else the TV. */
internal suspend fun QuickControls.perform(command: MediaCommand) {
    when (command) {
        is MediaCommand.Step -> if (command.delta > 0) volumeUp() else volumeDown()
        is MediaCommand.SetLevel -> setVolume(command.level)
        MediaCommand.ToggleMute -> toggleMute()
        MediaCommand.Mute -> if (state.value.level?.muted != true) toggleMute()
        MediaCommand.Unmute -> if (state.value.level?.muted == true) toggleMute()
        is MediaCommand.TvKey -> playPause()
    }
}
