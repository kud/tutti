package io.kud.tutti.quick

import androidx.annotation.DrawableRes
import io.kud.tutti.R
import io.kud.tutti.platform.data.VibrationLevel
import kotlinx.coroutines.flow.StateFlow

/**
 * The narrow slice of the living room that the quick-access surfaces need: the
 * Quick Settings tile, its overlay, the home-screen widget and the notification.
 *
 * The app's controller implements it (see [QuickModule]). The surfaces depend on
 * nothing else, so they can be built and reasoned about without the full remote.
 *
 * Contract for implementers:
 * - [state] is optimistic: an action updates it before the device answers,
 *   and reconciles (or rolls back) when it does. Surfaces never wait on the network.
 * - Actions are safe to call while disconnected: they are dropped or queued, never thrown.
 * - [applyScene] suspends until the scene has finished applying, so callers can
 *   give the "scene done" haptic. Every other action returns once sent.
 */
interface QuickControls {
    val state: StateFlow<QuickState>

    /** Keeps the device sessions open while a surface is visible. Pair with [release]. */
    fun retain() {}

    fun release() {}

    /** One Power for the whole room: switches the TV and the bar together. */
    suspend fun togglePower()

    /** One step on [QuickState.level]'s device: the bar when one is configured, else the TV. */
    suspend fun volumeUp()

    suspend fun volumeDown()

    /** Absolute level on [QuickState.level]'s device. The bar clamps to the active cap; the TV gets key presses. */
    suspend fun setVolume(level: Int)

    suspend fun toggleMute()

    /** Play/pause on the TV, over the remote link. */
    suspend fun playPause() {}

    /** One of the TV's media keys (previous, next) over the remote link, under the TV-link rules. */
    suspend fun mediaKey(keyCode: Int) {}

    /** Absolute seek, offered only when [QuickPlayback.canSeek]; nothing implements it over the remote link. */
    suspend fun seekTo(positionMs: Long) {}

    /**
     * Turns the room off (TV and bar), never on: the notification's "Turn off", offered only
     * while the TV is on and only once the phone is unlocked.
     */
    suspend fun powerOff() {}

    suspend fun applyScene(id: String)
}

data class QuickState(
    /** Both control channels needed for quick actions are up (bar TCP at least). */
    val connected: Boolean = false,
    /** A TV or a bar is set up: actions are offered, and connect on tap, even while nothing is up. */
    val configured: Boolean = false,
    /** The TV is on. The notification shows only while this and [connected] hold. */
    val power: Boolean = false,
    /** Bar volume, 0–100. 0 while unknown: surfaces that must not show a made-up 0 read [level]. */
    val volume: Int = 0,
    /** Effective ceiling: 100, or the active cap (a scene's, Nuit: 20, or the cap for tonight). */
    val volumeLimit: Int = 100,
    /**
     * The bar's high-volume guard, where it stops a quick surface (they can't ask): null when it
     * stops nothing right now. Going above needs the app.
     */
    val guardLine: Int? = null,
    /** A quick press was just stopped at [guardLine]: the surfaces say going above needs the app. */
    val guardHeld: Boolean = false,
    val muted: Boolean = false,
    val activeSceneId: String? = null,
    /** The active scene was applied, then changed by hand ("modifiée"). */
    val activeSceneModified: Boolean = false,
    /** In strip order. */
    val scenes: List<QuickScene> = emptyList(),
    /** The app's Feedback settings, so the overlay vibrates exactly as the remote does. */
    val vibration: VibrationLevel = VibrationLevel.ALL,
    val keySounds: Boolean = false,
    /** The level the notification shows and the volume keys drive, with its device. Null while unknown. */
    val level: AudioLevel? = null,
    /** What the TV is showing, when its foreground app is known. */
    val nowShowing: QuickNowShowing? = null,
    /** A TV is set up: play/pause is offered. */
    val tvConfigured: Boolean = false,
    /** What the now-showing card says, for the widget and the overlay; null without a TV. */
    val showing: QuickShowing? = null,
    /**
     * What plays inside the app on the TV, when something reports it (Cast "now playing", Maya):
     * fed into the one MediaSession (QuickNotifications), never a second one. Null while unknown.
     */
    val playback: QuickPlayback? = null,
    /** Configured device names, for the notification's subtitle. */
    val tvName: String? = null,
    val barName: String? = null,
    /** What Cast reports playing, while the remote is in front; null when nothing is reported. Never logged. */
    val cast: io.kud.tutti.protocol.cast.CastNowPlaying? = null,
) {
    val activeScene: QuickScene? get() = scenes.firstOrNull { it.id == activeSceneId }
}

/** Mirrors `io.kud.tutti.protocol.scenes.Scene`'s id, name and colour. */
data class QuickScene(
    val id: String,
    val name: String,
    /** ARGB seed, as `Scene.color`. */
    val color: Long,
)

/** Whose volume a quick surface is showing and stepping. */
enum class VolumeTarget { BAR, TV }

/** A device's volume as last reported (or optimistically set), on its own scale 0..[max]. */
data class AudioLevel(val target: VolumeTarget, val level: Int, val max: Int, val muted: Boolean)

/**
 * The bar's level whenever a bar is configured, else the TV's; never the other
 * device's in its place. The TV's remote link reports `0/0` while its sound goes
 * to the bar, so a TV level with no range counts as unknown.
 */
fun audioLevelOf(barConfigured: Boolean, barVolume: Int?, barMax: Int, barMuted: Boolean, tvVolume: Int?, tvMax: Int?, tvMuted: Boolean): AudioLevel? =
    if (barConfigured) {
        barVolume?.let { AudioLevel(VolumeTarget.BAR, it, barMax, barMuted) }
    } else {
        if (tvVolume == null || tvMax == null || tvMax <= 0) null else AudioLevel(VolumeTarget.TV, tvVolume, tvMax, tvMuted)
    }

/** What plays inside the TV's app, as far as it is known. Any field may be missing. */
data class QuickPlayback(
    val title: String? = null,
    val artist: String? = null,
    /** False when known paused; null when nobody says (the TV's own keys don't). */
    val playing: Boolean? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    /** An absolute seek can be sent ([QuickControls.seekTo]). */
    val canSeek: Boolean = false,
)

/** The TV's foreground app: its label from the app catalogue, and the icon tutti has cached for it. */
data class QuickNowShowing(val packageName: String, val label: String?, val iconPath: String?)

/**
 * The quick overlay's rail: [level] on 0..[range], drags capped at [limit]; null while the level
 * has not been reported, so the rail shows empty rather than a made-up 0. Reads [QuickState.level],
 * the same reported level as the notification: the bar's on 0..100 under the scene's cap, else
 * the TV's on its own scale.
 */
data class OverlayRail(val level: Int?, val range: Int, val limit: Int) {
    /** A step towards [up] would go past the end: bump instead of tick. Unknown is never at a limit. */
    fun atLimit(up: Boolean): Boolean = level != null && if (up) level >= limit else level <= 0
}

fun overlayRail(state: QuickState): OverlayRail {
    val reported = state.level
    return when (reported?.target) {
        VolumeTarget.TV -> OverlayRail(reported.level.coerceIn(0, reported.max), reported.max, reported.max)
        VolumeTarget.BAR -> OverlayRail(reported.level.coerceIn(0, BAR_RANGE), BAR_RANGE, barLimit(state))
        null -> OverlayRail(null, BAR_RANGE, barLimit(state))
    }
}

/** The overlay's drag stops at the cap, or at the high-volume guard while it is armed: it can't ask. */
private fun barLimit(state: QuickState): Int = minOf(state.volumeLimit, state.guardLine ?: BAR_RANGE).coerceIn(0, BAR_RANGE)

/** The bar's scale (TuttiController.MAX_VOLUME). */
private const val BAR_RANGE = 100

/**
 * The now-showing card's state (`ui/remote/ForegroundApp.kt`, `CardShowing`) as plain data, so the
 * widget and the overlay say what's on in the card's own words instead of a protocol word.
 */
sealed interface QuickShowing {
    data class App(val name: String, val packageName: String) : QuickShowing

    data class Input(val title: String) : QuickShowing

    /** The tuner: "2 · Channel", null when the TV sent neither (then a plain "TV channel"). */
    data class Channel(val text: String?) : QuickShowing

    /** The bar playing one of its own sources ([source] as the bar names it) with the TV asleep or out of reach. */
    data class OnTheBar(val source: String, val tvAsleep: Boolean) : QuickShowing

    data class Standby(val barOn: Boolean) : QuickShowing

    data object Home : QuickShowing

    data object Reconnecting : QuickShowing

    data object Offline : QuickShowing
}

/** One part of a quick surface's line: text as the TV or the user wrote it, or a string resource. */
sealed interface QuickWord {
    data class Plain(val text: String) : QuickWord

    data class Res(val id: Int, val arg: String? = null) : QuickWord
}

/** The widget's and the overlay's top line: its parts, joined with " · ", and the state's glyph. */
data class QuickHeadline(val words: List<QuickWord>, @DrawableRes val glyph: Int)

/**
 * What's on, as the now-showing card says it: "Netflix · Cinema", "HDMI 1", "TV on standby".
 * The active scene follows what is playing (marked when changed by hand), never a standby or an
 * out-of-reach TV. Without a TV, the soundbar by name, or why it can't be reached. Never
 * "Connected" (Iris, design idea 4): state is told in words and a glyph, not a dot's colour.
 */
fun quickHeadline(state: QuickState): QuickHeadline {
    val scene = state.activeScene?.let {
        if (state.activeSceneModified) QuickWord.Res(R.string.quick_scene_modified, it.name) else QuickWord.Plain(it.name)
    }
    fun playing(word: QuickWord, glyph: Int) = QuickHeadline(listOfNotNull(word, scene), glyph)
    return when (val showing = state.showing) {
        is QuickShowing.App -> playing(QuickWord.Plain(showing.name), R.drawable.ic_apps)
        is QuickShowing.Input -> playing(QuickWord.Plain(showing.title), R.drawable.ic_input)
        is QuickShowing.Channel -> playing(
            showing.text?.let(QuickWord::Plain) ?: QuickWord.Res(R.string.system_tv_channel),
            R.drawable.ic_tv,
        )
        is QuickShowing.OnTheBar -> QuickHeadline(
            listOf(
                barSourceName(showing.source)?.let(QuickWord::Plain) ?: QuickWord.Res(R.string.card_on_the_bar),
                QuickWord.Res(if (showing.tvAsleep) R.string.card_standby else R.string.card_offline),
            ),
            R.drawable.ic_speaker,
        )
        is QuickShowing.Standby -> QuickHeadline(listOf(QuickWord.Res(R.string.card_standby)), R.drawable.ic_power)
        QuickShowing.Home -> playing(QuickWord.Res(R.string.card_home), R.drawable.ic_home)
        QuickShowing.Reconnecting -> QuickHeadline(listOf(QuickWord.Res(R.string.card_reconnecting)), R.drawable.ic_refresh)
        QuickShowing.Offline -> QuickHeadline(listOf(QuickWord.Res(R.string.card_offline)), R.drawable.ic_warning)
        null -> when {
            !state.configured -> QuickHeadline(listOf(QuickWord.Res(R.string.quick_status_set_up)), R.drawable.ic_tv)
            !state.connected -> QuickHeadline(
                listOf(QuickWord.Res(R.string.system_unreachable, state.barName ?: "")),
                R.drawable.ic_warning,
            )
            else -> playing(
                state.barName?.let(QuickWord::Plain) ?: QuickWord.Res(R.string.card_on_the_bar),
                R.drawable.ic_speaker,
            )
        }
    }
}

/** The bar's sources by the names the Sound sheet gives them (`barValueLabel`); null for one tutti doesn't know. */
private fun barSourceName(source: String): String? = when (source) {
    "tv" -> "TV"
    "hdmi1" -> "HDMI 1"
    "spotify" -> "Spotify"
    "bluetooth" -> "Bluetooth"
    "airplay2" -> "AirPlay 2"
    else -> null
}

/**
 * Play/pause is offered with a TV set up, except while it sleeps: a tap there would send nothing
 * worth sending. Out of reach it stays, like volume: a quick tap connects on its own, and the
 * controller's own rules hold the key for the TV's return or drop it with a reason.
 */
fun playPauseOffered(state: QuickState): Boolean = state.tvConfigured && state.showing !is QuickShowing.Standby
