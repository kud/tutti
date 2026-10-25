package io.kud.tutti.quick.lock

import android.os.Bundle
import androidx.activity.ComponentActivity
import io.kud.tutti.quick.QuickRuntime

/**
 * The notification's "Turn off": no window of its own, only the unlock. Started from the
 * notification's action (an activity PendingIntent, so the platform asks for the unlock first
 * on the lock screen, and `setAuthenticationRequired` on Android 12+); if it still lands over
 * the lock screen it asks itself, and turns the room off only once unlocked. Cancelled, nothing
 * is sent. Turning off is never done from the lock screen: someone else's hand could do it.
 */
class QuickTurnOffActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuickRuntime.start(this)
        // Recreated mid-prompt: the first instance's request is gone with it; send nothing.
        if (savedInstanceState != null) {
            finish()
            return
        }
        unlockThen(
            onUnlocked = { QuickRuntime.act(this) { powerOff() } },
            onDone = ::finish,
        )
    }
}
