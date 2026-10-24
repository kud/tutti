package io.kud.tutti.quick.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.kud.tutti.quick.QuickRuntime
import kotlinx.coroutines.withTimeoutOrNull

/** Notification action buttons. Not exported: only our own PendingIntents reach it. */
class QuickNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        QuickRuntime.start(context)
        val action = intent.action?.let(::mediaAction)
        if (action == null) return
        val pending = goAsync()
        val job = QuickRuntime.act(context) {
            // goAsync allows ~10 s; quick actions are optimistic and return far sooner.
            withTimeoutOrNull(ACTION_TIMEOUT_MS) { perform(commandFor(action)) }
        }
        if (job == null) pending.finish() else job.invokeOnCompletion { pending.finish() }
    }

    companion object {
        const val ACTION_VOLUME_UP = "io.kud.tutti.quick.action.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "io.kud.tutti.quick.action.VOLUME_DOWN"
        const val ACTION_MUTE = "io.kud.tutti.quick.action.MUTE"
        const val ACTION_PLAY_PAUSE = "io.kud.tutti.quick.action.PLAY_PAUSE"
        private const val ACTION_TIMEOUT_MS = 8_000L

        /** One string per button, shared by the notification's intents and the session's custom actions. */
        fun intentAction(action: MediaAction): String = when (action) {
            MediaAction.PLAY_PAUSE -> ACTION_PLAY_PAUSE
            MediaAction.VOLUME_DOWN -> ACTION_VOLUME_DOWN
            MediaAction.MUTE -> ACTION_MUTE
            MediaAction.VOLUME_UP -> ACTION_VOLUME_UP
        }

        fun mediaAction(value: String): MediaAction? =
            MediaAction.entries.firstOrNull { intentAction(it) == value }
    }
}
