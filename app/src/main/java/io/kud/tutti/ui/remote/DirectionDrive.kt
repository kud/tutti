package io.kud.tutti.ui.remote

import io.kud.tutti.protocol.atv.DirectionEffect
import io.kud.tutti.protocol.atv.DirectionHold
import io.kud.tutti.protocol.atv.DirectionPress
import kotlinx.coroutines.delay

/**
 * Carries out a held arrow on the D-pad ([DirectionPress]): after [DirectionHold.HOLD_AFTER_MS] the
 * key goes down where it belongs and the TV repeats it itself, as with a physical remote; a renewal
 * goes every [DirectionHold.RENEW_EVERY_MS] while the finger stays. Where the route can't hold a key
 * (a computer gets plain presses), [hold] says false and this falls back to [KeyRepeat]'s plain
 * presses, which never reach the TV's remote link.
 */
internal object DirectionDrive {
    /** Runs while the finger is down, until cancelled; the caller then calls [end], always. */
    suspend fun run(
        press: DirectionPress,
        clock: () -> Long,
        hold: () -> Boolean,
        renew: () -> Unit,
        repeat: () -> Unit,
    ) {
        delay(DirectionHold.HOLD_AFTER_MS)
        while (true) {
            when (press.tick(clock())) {
                DirectionEffect.HOLD -> if (!hold()) {
                    press.holdRefused()
                    repeatPlain(repeat)
                    return
                }
                DirectionEffect.RENEW -> renew()
                DirectionEffect.NONE -> Unit
                DirectionEffect.TAP, DirectionEffect.RELEASE -> return
            }
            delay(DirectionHold.RENEW_EVERY_MS)
        }
    }

    /** The finger lifted ([lifted]) or the touch was cancelled: a tap goes, a hold comes up, else nothing. */
    fun end(press: DirectionPress, lifted: Boolean, tap: () -> Unit, release: () -> Unit) {
        when (if (lifted) press.release() else press.cancel()) {
            DirectionEffect.TAP -> tap()
            DirectionEffect.RELEASE -> release()
            else -> Unit
        }
    }

    /** [KeyRepeat]'s schedule from its first repeat, which is now. */
    private suspend fun repeatPlain(repeat: () -> Unit) {
        var at = KeyRepeat.DELAY_MS
        repeat()
        while (true) {
            val next = KeyRepeat.nextAt(at)
            delay(next - at)
            at = next
            repeat()
        }
    }
}
