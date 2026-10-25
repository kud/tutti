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
        // Turn off never comes this way: it goes through QuickTurnOffActivity and the unlock.
        if (action == null || action == MediaAction.TURN_OFF) return
        val pending = goAsync()
        val job = QuickRuntime.act(context) {
            // goAsync allows ~10 s; quick actions are optimistic and return far sooner.
            withTimeoutOrNull(ACTION_TIMEOUT_MS) { perform(commandFor(action)) }
        }
        if (job == null) pending.finish() else job.invokeOnCompletion { pending.finish() }
    }

    companion object {
        const val ACTION_PREVIOUS = "io.kud.tutti.quick.action.PREVIOUS"
        const val ACTION_NEXT = "io.kud.tutti.quick.action.NEXT"
        const val ACTION_MUTE = "io.kud.tutti.quick.action.MUTE"
        const val ACTION_PLAY_PAUSE = "io.kud.tutti.quick.action.PLAY_PAUSE"
        /** The session's custom action only; the notification's button starts QuickTurnOffActivity. */
        const val ACTION_TURN_OFF = "io.kud.tutti.quick.action.TURN_OFF"
        private const val ACTION_TIMEOUT_MS = 8_000L

        /** One string per button, shared by the notification's intents and the session's custom actions. */
        fun intentAction(action: MediaAction): String = when (action) {
            MediaAction.PREVIOUS -> ACTION_PREVIOUS
            MediaAction.PLAY_PAUSE -> ACTION_PLAY_PAUSE
            MediaAction.NEXT -> ACTION_NEXT
            MediaAction.MUTE -> ACTION_MUTE
            MediaAction.TURN_OFF -> ACTION_TURN_OFF
        }

        fun mediaAction(value: String): MediaAction? =
            MediaAction.entries.firstOrNull { intentAction(it) == value }
    }
}
