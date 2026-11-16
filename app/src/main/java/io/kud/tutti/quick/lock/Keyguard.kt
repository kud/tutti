package io.kud.tutti.quick.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the phone is locked, for the quick surfaces that must hold back Power and scenes
 * there (design.md, Quick access, "Lock screen"). Read from [KeyguardManager.isKeyguardLocked],
 * re-read when the screen goes off or on and when the user unlocks: system broadcasts, so a
 * receiver registered while something listens, never polled.
 */
fun Context.keyguardLocked(): Boolean =
    getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false

fun keyguardLockedFlow(context: Context): Flow<Boolean> = callbackFlow {
    val app = context.applicationContext
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(app.keyguardLocked())
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_USER_PRESENT)
    }
    ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    trySend(app.keyguardLocked())
    awaitClose { app.unregisterReceiver(receiver) }
}.distinctUntilChanged()

/**
 * Asks for the unlock, over the lock screen, then runs [onUnlocked]; [onDone] runs either way,
 * once. Already unlocked, [onUnlocked] runs at once. The platform's own prompt
 * ([KeyguardManager.requestDismissKeyguard]): tutti never sees a PIN or a fingerprint.
 */
fun Activity.unlockThen(onUnlocked: () -> Unit, onDone: () -> Unit) {
    val keyguard = getSystemService(KeyguardManager::class.java)
    if (keyguard == null || !keyguard.isKeyguardLocked) {
        onUnlocked()
        onDone()
        return
    }
    // The prompt needs this activity showing over the lock screen.
    setShowWhenLocked(true)
    keyguard.requestDismissKeyguard(
        this,
        object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                onUnlocked()
                onDone()
            }

            override fun onDismissCancelled() = onDone()

            override fun onDismissError() = onDone()
        },
    )
}
