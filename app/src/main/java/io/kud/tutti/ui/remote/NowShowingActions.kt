package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.protocol.cast.CastNowPlaying

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
 * The card's tap (2026-10-25). While the TV shows the HDMI input marked as a computer's
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
enum class AppAction { PIN, UNPIN, HIDE, CLOSE_APPS }

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
    // Hiding only takes it off tutti's Apps list; Settings → Apps shows it again.
    add(AppAction.HIDE)
    if (pskSet) add(AppAction.CLOSE_APPS)
}

/**
 * The jog wheel's jump as words, "+1 min 30 s" or "−20 s" (Erwann, 2026-11-01: each notch a fixed
 * jump, like turning a timeline, never fast forward's 2x, 3x). The keys and their pace are
 * [io.kud.tutti.protocol.apps.JogSeek]'s; the "about" around it is the string resource's.
 */
fun seekAmount(seconds: Int): String {
    val sign = if (seconds < 0) "\u2212" else "+"
    val abs = kotlin.math.abs(seconds)
    val min = abs / 60
    val sec = abs % 60
    val body = when {
        min == 0 -> "$sec s"
        sec == 0 -> "$min min"
        else -> "$min min $sec s"
    }
    return sign + body
}

/** Travel along the jog wheel's surface for one key. */
const val SCRUB_STEP_DP = 32f

/**
 * Travel turned into steps: one step (+1 for positive travel, -1 for negative) each time the
 * travel crosses another [step] px. Turning back undoes travel before it adds the other way, so a small
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

/**
 * The jog wheel (Erwann, 2026-10-28: « Si on drag left ou right c'est comme une roulette donc ça
 * devrait faire l'opposé »): a wheel seen edge-on, whose surface follows the finger. Pushing the
 * surface left turns it on, so a drag to the left goes forward (+1) and to the right goes back
 * (-1), as a physical jog wheel or a scrolled list does. Pure; px in, steps out.
 */
class JogTrack(step: Float) {
    private val track = ScrubTrack(step)

    fun reset() = track.reset()

    fun move(dx: Float): List<Int> = track.move(-dx)
}

/** What sits under the chips: a timeline when a trusted time is known, else the jog wheel. */
sealed interface PlaybackStrip {
    /** Display-only: [elapsedSec] of [totalSec], [progress] 0..1. Right is always later. */
    data class Timeline(val elapsedSec: Double, val totalSec: Double, val progress: Float) : PlaybackStrip

    data object JogWheel : PlaybackStrip
}

/**
 * A timeline only from a Cast reading the card would show ([CastNowPlaying.progressAt]: a trusted
 * position, a positive duration, fresh enough); live content, an unknown position or no Cast at
 * all gets the wheel. No seek exists (Cast stays read-only, Remote v2 and IRCC have none), so the
 * timeline never takes a drag.
 */
fun playbackStrip(cast: CastNowPlaying?, nowMillis: Long): PlaybackStrip {
    if (cast == null) return PlaybackStrip.JogWheel
    val progress = cast.progressAt(nowMillis) ?: return PlaybackStrip.JogWheel
    val total = cast.durationSec ?: return PlaybackStrip.JogWheel
    val at = cast.positionAt(nowMillis) ?: return PlaybackStrip.JogWheel
    return PlaybackStrip.Timeline(at, total, progress)
}

/** The app in front's own commands for the playback sheet, or null when it has none. */
fun playbackShortcuts(showing: CardShowing): List<io.kud.tutti.protocol.apps.AppShortcut>? {
    val app = (showing as? CardShowing.Showing)?.now as? NowShowing.App ?: return null
    return io.kud.tutti.protocol.apps.AppShortcuts.forPackage(app.packageName).ifEmpty { null }
}

/**
 * Whether the playback sheet offers "search in this app" as the Search key (Erwann, 2026-11-06:
 * « que je puisse aller à la recherche directement »): any app in front without its own search
 * link. Android hands KEYCODE_SEARCH to the app first; one that has no search lets the system
 * open Google TV's own, so the press is never wasted. Unverified per app on the BRAVIA.
 */
fun inAppSearchKey(showing: CardShowing): Boolean {
    val app = (showing as? CardShowing.Showing)?.now as? NowShowing.App ?: return false
    if (app.packageName in NO_SEARCH_PACKAGES) return false
    return io.kud.tutti.protocol.apps.AppShortcut.SEARCH !in
        io.kud.tutti.protocol.apps.AppShortcuts.forPackage(app.packageName)
}

/**
 * Apps in front of which neither search chip does anything on the BRAVIA, so neither is offered.
 * Nuvio (Erwann, 2026-11-06: « Search et Search TV, il marche pas »): its player and screens
 * handle no Search key, and the TV opened nothing for the Assistant key either.
 */
private val NO_SEARCH_PACKAGES: Set<String> = setOf("com.nuvio.tv", "com.nuvio.app")

/** Whether the whole-TV search chip is offered over [showing]: everywhere but [NO_SEARCH_PACKAGES]. */
fun globalSearchOffered(showing: CardShowing): Boolean {
    val app = (showing as? CardShowing.Showing)?.now as? NowShowing.App ?: return true
    return app.packageName !in NO_SEARCH_PACKAGES
}

/**
 * The sheet's whole-TV search, the Assistant key (219): Google TV's search over whatever is on
 * screen, typed with tutti's keyboard or spoken; Back returns to the app. Offered whatever the TV shows.
 */
const val GLOBAL_SEARCH_KEY: Int = V2Key.ASSIST
