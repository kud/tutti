package io.kud.tutti.quick.notification

import android.media.session.PlaybackState
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.cast.CastPlayerState
import io.kud.tutti.quick.AudioLevel
import io.kud.tutti.quick.QuickState
import io.kud.tutti.quick.VolumeTarget

/**
 * The media notification's rules, with no Android types, so they run as unit
 * tests: what the card says, which button sends what, and how the phone's
 * volume keys map onto the bar (or the TV).
 */

/**
 * The buttons, in the order they sit on the expanded notification. [TURN_OFF] is last, never in
 * the collapsed view, and only ever runs once the phone is unlocked ([turnOffInSession]).
 */
enum class MediaAction { PLAY_PAUSE, VOLUME_DOWN, MUTE, VOLUME_UP, TURN_OFF }

/**
 * Indexes into [MediaAction.entries] for the collapsed view (Android 12 and older):
 * play/pause, volume down and mute. Down rather than up because turning it down
 * is the urgent one (an advert, someone asleep); up is one key press away while
 * the session is active.
 */
val COMPACT_ACTIONS = intArrayOf(0, 1, 2)

/** What a button or a volume key asks of the room. Sent through [io.kud.tutti.quick.QuickControls]. */
sealed interface MediaCommand {
    data class Step(val delta: Int) : MediaCommand
    data class SetLevel(val level: Int) : MediaCommand
    data object ToggleMute : MediaCommand
    data object Mute : MediaCommand
    data object Unmute : MediaCommand
    /** A key to the TV over the remote link (play/pause is always the TV's). */
    data class TvKey(val keyCode: Int) : MediaCommand
    /** The room off (TV and bar), never on. */
    data object TurnOff : MediaCommand
}

fun commandFor(action: MediaAction): MediaCommand = when (action) {
    MediaAction.PLAY_PAUSE -> MediaCommand.TvKey(V2Key.MEDIA_PLAY_PAUSE)
    MediaAction.VOLUME_DOWN -> MediaCommand.Step(-1)
    MediaAction.MUTE -> MediaCommand.ToggleMute
    MediaAction.VOLUME_UP -> MediaCommand.Step(+1)
    MediaAction.TURN_OFF -> MediaCommand.TurnOff
}

/**
 * Android 13+ draws the media controls from the session's custom actions, which run with no
 * unlock at all, on the lock screen too. So "Turn off" joins them only while the phone is
 * unlocked: on the lock screen the controls keep play/pause, volume and mute, and it comes back
 * with the unlock. The notification's own action (Android 12 and older) asks for the unlock instead.
 */
fun turnOffInSession(locked: Boolean): Boolean = !locked

/** `android.media.AudioManager` adjust directions, copied so this file stays plain Kotlin. */
object AdjustDirection {
    const val LOWER = -1
    const val SAME = 0
    const val RAISE = 1
    const val MUTE = -100
    const val UNMUTE = 100
    const val TOGGLE_MUTE = 101
}

/** `VolumeProvider.onAdjustVolume`: a hardware key press, or the system slider's mute. Null does nothing. */
fun adjustCommand(direction: Int): MediaCommand? = when (direction) {
    AdjustDirection.LOWER -> MediaCommand.Step(-1)
    AdjustDirection.RAISE -> MediaCommand.Step(+1)
    AdjustDirection.TOGGLE_MUTE -> MediaCommand.ToggleMute
    AdjustDirection.MUTE -> MediaCommand.Mute
    AdjustDirection.UNMUTE -> MediaCommand.Unmute
    else -> null
}

/** `VolumeProvider.onSetVolumeTo`: the system slider, clamped to the provider's range. */
fun setLevelCommand(requested: Int, max: Int): MediaCommand.SetLevel =
    MediaCommand.SetLevel(requested.coerceIn(0, max.coerceAtLeast(0)))

/**
 * The TV has no absolute volume over the remote link, only keys: a slider move is
 * sent as that many presses, at most [MAX_TV_STEPS] so a fling cannot blast it.
 */
fun tvStepsTo(current: Int, requested: Int, max: Int): Int {
    val target = requested.coerceIn(0, max.coerceAtLeast(0))
    return (target - current).coerceIn(-MAX_TV_STEPS, MAX_TV_STEPS)
}

const val MAX_TV_STEPS = 10

/**
 * The remote volume the session hands the system, or null for none (the phone's
 * keys stay the phone's). A new provider is needed whenever [max] changes, since
 * `VolumeProvider`'s maximum is fixed at construction.
 */
data class RemoteVolume(val target: VolumeTarget, val max: Int, val current: Int)

fun remoteVolumeFor(level: AudioLevel?): RemoteVolume? {
    if (level == null || level.max <= 0) return null
    return RemoteVolume(level.target, level.max, level.level.coerceIn(0, level.max))
}

/**
 * When the session takes the phone's volume keys. [WHILE_SHOWN] is the default
 * Erwann asked for; [IN_FRONT] is the narrower one design.md used to promise.
 */
enum class VolumeKeysScope { WHILE_SHOWN, IN_FRONT }

fun sessionTakesVolumeKeys(scope: VolumeKeysScope, shown: Boolean, appInFront: Boolean): Boolean = when (scope) {
    VolumeKeysScope.WHILE_SHOWN -> shown
    VolumeKeysScope.IN_FRONT -> shown && appInFront
}

/** Everything the notification shows, so an unchanged card is never re-posted. */
data class MediaCard(
    /** The TV's foreground app label, else null (the caller falls back to the scene or the app name). */
    val appLabel: String?,
    val appPackage: String?,
    val appIconPath: String?,
    val sceneName: String?,
    val sceneColor: Long?,
    /** "BRAVIA 8 · Bar 9": the configured devices, TV first. */
    val devices: String,
    /** Null while the level is unknown: never shown as 0. */
    val level: AudioLevel?,
    val remote: RemoteVolume?,
    /** The high-volume guard's line, when a quick press was just stopped there; null otherwise. */
    val guardHeld: Int? = null,
    /** What Cast reports playing; null keeps today's metadata. Personal viewing data: never logged. */
    val cast: CastNowPlaying? = null,
    /** The session offers "Turn off" (the phone is unlocked; see [turnOffInSession]). */
    val turnOff: Boolean = false,
) {
    val muted: Boolean get() = level?.muted == true
}

fun cardFor(state: QuickState, locked: Boolean = true): MediaCard = MediaCard(
    appLabel = state.nowShowing?.label,
    appPackage = state.nowShowing?.packageName,
    appIconPath = state.nowShowing?.iconPath,
    sceneName = state.activeScene?.name,
    sceneColor = state.activeScene?.color,
    devices = listOfNotNull(state.tvName, state.barName).joinToString(" · "),
    level = state.level,
    remote = remoteVolumeFor(state.level),
    guardHeld = state.guardLine?.takeIf { state.guardHeld },
    cast = state.cast,
    turnOff = turnOffInSession(locked),
)

/**
 * Android sheds notification updates past about five a second per app, silently,
 * and the one shed can be the last: a post that leaves a stale card. Posts are
 * held at least [interval] apart, trailing, so the latest card always goes out.
 * Returns how long to wait before posting at [now], given the last post time.
 */
fun postDelay(now: Long, lastPost: Long?, interval: Long): Long {
    if (lastPost == null) return 0
    return (lastPost + interval - now).coerceAtLeast(0)
}

/** The session's playback fields while Cast reports something: framework state, position, speed, update time. */
data class CastPlayback(val state: Int, val positionMs: Long, val speed: Float, val updateTime: Long, val durationMs: Long?)

/**
 * Maps a Cast report onto the media session. The report's time is wall-clock; the session wants
 * [android.os.SystemClock.elapsedRealtime], so it is shifted by how long ago the report was read.
 * Unknown position stays unknown; live content has no duration.
 */
fun castPlayback(cast: CastNowPlaying, nowWallMs: Long, nowElapsedMs: Long): CastPlayback {
    val state = when (cast.state) {
        CastPlayerState.PLAYING -> PlaybackState.STATE_PLAYING
        CastPlayerState.PAUSED -> PlaybackState.STATE_PAUSED
        CastPlayerState.BUFFERING -> PlaybackState.STATE_BUFFERING
        CastPlayerState.IDLE -> PlaybackState.STATE_STOPPED
    }
    val position = cast.positionSec?.let { (it * 1000).toLong() } ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
    val speed = if (cast.state == CastPlayerState.PLAYING) cast.playbackRate.toFloat() else 0f
    val updateTime = nowElapsedMs - (nowWallMs - cast.readAtMillis).coerceAtLeast(0)
    return CastPlayback(state, position, speed, updateTime, cast.durationSec?.let { (it * 1000).toLong() })
}
