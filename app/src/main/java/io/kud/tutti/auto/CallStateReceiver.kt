package io.kud.tutti.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import kotlinx.coroutines.launch

/**
 * The phone's call state, for "Pause during calls". A manifest receiver rather than a
 * TelephonyCallback: a callback lives only while tutti's process runs and isn't frozen, and a call
 * comes exactly when tutti is in the background. `PHONE_STATE` is one of the broadcasts Android
 * still delivers to manifest receivers, and only to apps holding READ_PHONE_STATE, which the
 * setting asks for when it is turned on. It reads the state only, never the number.
 */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val active = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING, TelephonyManager.EXTRA_STATE_OFFHOOK -> true
            TelephonyManager.EXTRA_STATE_IDLE -> false
            else -> return
        }
        if (!AutoPlayback.callPermission(context)) return
        val auto = context.autoEntry().autoPlayback()
        val pending = goAsync()
        auto.controller.scope.launch {
            try {
                auto.onCall(active)
            } finally {
                pending.finish()
            }
        }
    }
}
