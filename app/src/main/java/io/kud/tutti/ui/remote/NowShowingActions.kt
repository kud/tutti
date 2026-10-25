package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.protocol.atv.V2Key

/**
 * The playback sheet's keys (Erwann, 2026-10-25: « la lecture est pas mal »), all remote v2 media
 * keys the TV takes like its own remote's. The transport row reads previous, rewind, play/pause,
 * fast forward, next; play/pause is one toggle with no "playing" claim, since no source tells
 * tutti whether an app is playing. Rewind and fast forward, not "10 s": how far a press moves is
 * the app's choice, and the v2 skip keys (272, 273) are honoured by fewer players.
 */
val PLAYBACK_TRANSPORT: List<Int> = listOf(
    V2Key.MEDIA_PREVIOUS,
    V2Key.MEDIA_REWIND,
    V2Key.MEDIA_PLAY_PAUSE,
    V2Key.MEDIA_FAST_FORWARD,
    V2Key.MEDIA_NEXT,
)

/** Subtitles and audio track: the keys Sony's own remote has for them. */
val PLAYBACK_TRACKS: List<Int> = listOf(V2Key.CAPTIONS, V2Key.MEDIA_AUDIO_TRACK)

/** What the card's tap opens: playback while the TV shows something (an app, a channel, an input), else nothing. */
fun cardOpensPlayback(showing: CardShowing?): Boolean = showing is CardShowing.Showing

/** What the card's tap does: [PLAYBACK], [CONTROL_COMPUTER] on a computer's input, or [NONE]. */
enum class CardTap { PLAYBACK, CONTROL_COMPUTER, NONE }

/**
 * The card's tap (Iris, 2026-10-25). While the TV shows the HDMI input marked as a computer's
 * ([onComputerInput]), the tap becomes « Contrôler <nom> » and points the remote at it: media keys
 * sent to a computer over HDMI-CEC are guesswork, while the remote's own keys reach it over Bluetooth.
 * Anything else the TV shows opens playback, as [cardOpensPlayback] says.
 */
fun cardTap(showing: CardShowing?, onComputerInput: Boolean): CardTap = when {
    !cardOpensPlayback(showing) -> CardTap.NONE
    onComputerInput && (showing as CardShowing.Showing).now is NowShowing.Input -> CardTap.CONTROL_COMPUTER
    else -> CardTap.PLAYBACK
}

/** What the card's long press acts on: only an app, which the actions need by package. */
fun cardActionsApp(showing: CardShowing?): NowShowing.App? = (showing as? CardShowing.Showing)?.now as? NowShowing.App

/** One row of the app actions sheet. */
enum class AppAction { PIN, UNPIN, CLOSE_APPS }

/** The first pin that launches [packageName], or null when none does. */
fun pinnedIndex(dock: DockConfig, packageName: String): Int? =
    dock.slots.indexOfFirst { (it.primary as? DockAction.LaunchApp)?.packageName == packageName }.takeIf { it >= 0 }

/**
 * Only what tutti can really do to an app (Erwann, 2026-10-25: « aller sur home de l'app ou kill
 * l'app »). Pin or unpin always. Closing needs IP control ([pskSet]); Sony's `terminateApps`
 * closes every app, so the row says so. No "back to the app's home": a launch link resumes an app
 * where it was on Google TV, so it would promise what it can't do.
 */
fun appActions(pinnedAt: Int?, pskSet: Boolean): List<AppAction> = buildList {
    add(if (pinnedAt == null) AppAction.PIN else AppAction.UNPIN)
    if (pskSet) add(AppAction.CLOSE_APPS)
}

/**
 * The scrub strip's key (benchmark idea 2): rewind or fast forward, the transport row's own keys,
 * since architecture.md rules that the strip moves by them and Cast only ever shows where that
 * lands. How far one key moves is the app's choice, so nothing on the strip names a duration.
 */
fun scrubKey(delta: Int): Int = if (delta > 0) V2Key.MEDIA_FAST_FORWARD else V2Key.MEDIA_REWIND

/** Travel along the scrub strip for one key. */
const val SCRUB_STEP_DP = 32f

/**
 * A drag along the scrub strip: one step (+1 forward, right; -1 back, left) each time the travel
 * crosses another [step] px. Turning back undoes travel before it adds the other way, so a small
 * wobble sends nothing. Pure; px in, steps out.
 */
class ScrubTrack(private val step: Float) {
    private var accumulated = 0f

    fun reset() {
        accumulated = 0f
    }

    fun move(dx: Float): List<Int> {
        if (step <= 0f) return emptyList()
        accumulated += dx
        val out = mutableListOf<Int>()
        while (accumulated >= step) {
            out += 1
            accumulated -= step
        }
        while (accumulated <= -step) {
            out += -1
            accumulated += step
        }
        return out
    }
}
