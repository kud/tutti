package io.kud.salon.quick.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.kud.salon.quick.QuickRuntime
import kotlinx.coroutines.withTimeoutOrNull

/** Notification action buttons. Not exported: only our own PendingIntents reach it. */
class QuickNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        QuickRuntime.start(context)
        val pending = goAsync()
        val job = QuickRuntime.act(context) {
            // goAsync allows ~10 s; quick actions are optimistic and return far sooner.
            withTimeoutOrNull(ACTION_TIMEOUT_MS) {
                when (intent.action) {
                    ACTION_VOLUME_UP -> volumeUp()
                    ACTION_VOLUME_DOWN -> volumeDown()
                    ACTION_MUTE -> toggleMute()
                }
            }
        }
        if (job == null) pending.finish() else job.invokeOnCompletion { pending.finish() }
    }

    companion object {
        const val ACTION_VOLUME_UP = "io.kud.salon.quick.action.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "io.kud.salon.quick.action.VOLUME_DOWN"
        const val ACTION_MUTE = "io.kud.salon.quick.action.MUTE"
        private const val ACTION_TIMEOUT_MS = 8_000L
    }
}
