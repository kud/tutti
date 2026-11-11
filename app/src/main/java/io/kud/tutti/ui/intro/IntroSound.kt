package io.kud.tutti.ui.intro

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.kud.tutti.R
import io.kud.tutti.ui.common.IntroSoundContext
import io.kud.tutti.ui.common.IntroSoundRule
import io.kud.tutti.ui.common.Ringer

/**
 * The intro's chime, `res/raw/intro_chime.ogg` (synthesised by `scripts/intro-chime.py`), played at
 * the intro's landing when [IntroSoundRule] allows it. Only a cold start loads it.
 *
 * It plays as a sonification, so the system's touch/system volume sets its level, through a
 * SoundPool, which asks for no audio focus: whatever the phone is playing is never ducked or paused,
 * and the rule keeps it quiet while anything is. The pool lives a beat past the chime, then goes.
 */
class IntroChime private constructor(private val context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    @Volatile private var loaded = false
    private val sound: Int

    init {
        pool.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
        sound = pool.load(context, R.raw.intro_chime, 1)
    }

    /** Plays once per process at most, if the rule allows it now and the sound is ready; true if it played. */
    fun play(keySounds: Boolean): Boolean {
        if (!loaded || !claim()) return false
        if (!IntroSoundRule.shouldPlay(read(context, keySounds))) return false
        return pool.play(sound, 1f, 1f, 1, 0, 1f) != 0
    }

    /** Lets the chime ring out (it is under 600 ms), then frees the pool. */
    fun release() {
        Handler(Looper.getMainLooper()).postDelayed({ pool.release() }, RING_OUT_MS)
    }

    companion object {
        private const val RING_OUT_MS = 1_000L

        // Process-wide, like LaunchIntro's own cold flag: one landing per process asks, whatever happens to the activity.
        @Volatile private var asked = false

        private fun claim(): Boolean = synchronized(this) { if (asked) false else true.also { asked = true } }

        /** Only a cold start chimes, so only a cold start pays for the pool. */
        fun forIntro(context: Context, kind: IntroKind): IntroChime? =
            if (kind == IntroKind.COLD && !asked) IntroChime(context.applicationContext) else null

        private fun read(context: Context, keySounds: Boolean): IntroSoundContext {
            val audio = context.getSystemService(AudioManager::class.java)
            val notifications = context.getSystemService(NotificationManager::class.java)
            return IntroSoundContext(
                keySounds = keySounds,
                touchSounds = Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0,
                coldStart = true,
                ringer = when (audio?.ringerMode) {
                    AudioManager.RINGER_MODE_NORMAL -> Ringer.NORMAL
                    AudioManager.RINGER_MODE_VIBRATE -> Ringer.VIBRATE
                    else -> Ringer.SILENT
                },
                doNotDisturb = notifications != null &&
                    notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                    notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
                phoneMediaPlaying = audio?.isMusicActive ?: true,
            )
        }
    }
}
