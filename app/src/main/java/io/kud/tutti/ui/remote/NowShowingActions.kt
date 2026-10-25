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
