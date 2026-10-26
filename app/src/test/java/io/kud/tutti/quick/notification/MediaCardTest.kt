package io.kud.tutti.quick.notification

import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.quick.AudioLevel
import io.kud.tutti.quick.QuickNowShowing
import io.kud.tutti.quick.QuickPlayback
import io.kud.tutti.quick.QuickScene
import io.kud.tutti.quick.QuickState
import io.kud.tutti.quick.VolumeTarget
import io.kud.tutti.quick.audioLevelOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCardTest {

    // region which level is shown

    @Test
    fun `a configured bar shows the bar's level, never the TV's`() {
        val level = audioLevelOf(true, barVolume = 10, barMax = 100, barMuted = false, tvVolume = 0, tvMax = 0, tvMuted = false)
        assertEquals(AudioLevel(VolumeTarget.BAR, 10, 100, false), level)
    }

    @Test
    fun `an unknown bar level is unknown, not zero and not the TV's`() {
        // The OnePlus case: link up before the bar's first volume notify, TV reporting 0/0.
        assertNull(audioLevelOf(true, barVolume = null, barMax = 100, barMuted = false, tvVolume = 0, tvMax = 0, tvMuted = false))
        assertNull(audioLevelOf(true, barVolume = null, barMax = 100, barMuted = false, tvVolume = 12, tvMax = 100, tvMuted = false))
    }

    @Test
    fun `with no bar the TV's level shows`() {
        val level = audioLevelOf(false, barVolume = null, barMax = 100, barMuted = false, tvVolume = 12, tvMax = 60, tvMuted = true)
        assertEquals(AudioLevel(VolumeTarget.TV, 12, 60, true), level)
    }

    @Test
    fun `a TV level with no range counts as unknown`() {
        assertNull(audioLevelOf(false, barVolume = null, barMax = 100, barMuted = false, tvVolume = 0, tvMax = 0, tvMuted = false))
        assertNull(audioLevelOf(false, barVolume = null, barMax = 100, barMuted = false, tvVolume = null, tvMax = null, tvMuted = false))
    }

    @Test
    fun `the bar's range is the scene cap`() {
        val level = audioLevelOf(true, barVolume = 15, barMax = 20, barMuted = false, tvVolume = null, tvMax = null, tvMuted = false)
        assertEquals(RemoteVolume(VolumeTarget.BAR, 20, 15), remoteVolumeFor(level))
    }

    // endregion

    // region card

    @Test
    fun `a dropped link keeps the last card, a live link shows the room or nothing once it is off`() {
        val on = QuickState(
            connected = true,
            configured = true,
            power = true,
            level = AudioLevel(VolumeTarget.BAR, 12, 100, false),
            nowShowing = QuickNowShowing("com.example.video", "Video", null),
            tvName = "BRAVIA 8",
            barName = "Bar 9",
        )
        val first = shownCard(on, enabled = true, locked = false, last = null)!!
        assertEquals("Video", first.appLabel)
        // The link drops: what the controller now holds is gone, the card is not.
        val dropped = QuickState(configured = true, tvName = "BRAVIA 8", barName = "Bar 9")
        val kept = shownCard(dropped, enabled = true, locked = false, last = first)
        assertEquals(first, kept)
        // The lock still governs Turn off on a kept card.
        assertFalse(shownCard(dropped, enabled = true, locked = true, last = first)!!.turnOff)
        // Known off on a live link: gone, and a later drop does not bring it back.
        val off = shownCard(on.copy(power = false), enabled = true, locked = false, last = first)
        assertNull(off)
        assertNull(shownCard(dropped, enabled = true, locked = false, last = off))
        // Never shown this process (a cold start): nothing to keep.
        assertNull(shownCard(dropped, enabled = true, locked = false, last = null))
        // The setting off, or nothing configured, always clears it.
        assertNull(shownCard(dropped, enabled = false, locked = false, last = first))
        assertNull(shownCard(QuickState(), enabled = true, locked = false, last = first))
    }

    @Test
    fun `the card carries the app, both devices and the level`() {
        val state = QuickState(
            connected = true,
            power = true,
            level = AudioLevel(VolumeTarget.BAR, 10, 100, false),
            nowShowing = QuickNowShowing("com.example.video", "Video", "/icons/com.example.video"),
            tvName = "BRAVIA 8",
            barName = "Bar 9",
            activeSceneId = "film",
            scenes = listOf(QuickScene("film", "Film", 0xFF336699)),
        )
        val card = cardFor(state)
        assertEquals("Video", card.appLabel)
        assertEquals("/icons/com.example.video", card.appIconPath)
        assertEquals("BRAVIA 8 · Bar 9", card.devices)
        assertEquals("Film", card.sceneName)
        assertEquals(RemoteVolume(VolumeTarget.BAR, 100, 10), card.remote)
        assertFalse(card.muted)
    }

    @Test
    fun `a TV-only room names only the TV, and an unknown level drops the remote volume`() {
        val card = cardFor(QuickState(tvName = "BRAVIA 8", level = null))
        assertEquals("BRAVIA 8", card.devices)
        assertNull(card.remote)
        assertNull(card.level)
    }

    @Test
    fun `a change outside the card does not repost`() {
        val a = QuickState(level = AudioLevel(VolumeTarget.BAR, 10, 100, false), keySounds = false)
        assertEquals(cardFor(a), cardFor(a.copy(keySounds = true)))
    }

    // endregion

    // region actions

    @Test
    fun `buttons map to the TV's media keys and the bar's mute`() {
        assertEquals(MediaCommand.TvKey(V2Key.MEDIA_PREVIOUS), commandFor(MediaAction.PREVIOUS))
        assertEquals(MediaCommand.TvKey(V2Key.MEDIA_PLAY_PAUSE), commandFor(MediaAction.PLAY_PAUSE))
        assertEquals(MediaCommand.TvKey(V2Key.MEDIA_NEXT), commandFor(MediaAction.NEXT))
        assertEquals(MediaCommand.ToggleMute, commandFor(MediaAction.MUTE))
        assertEquals(MediaCommand.TurnOff, commandFor(MediaAction.TURN_OFF))
    }

    @Test
    fun `the compact view is the media player's previous, play-pause and next`() {
        assertArrayEquals(intArrayOf(0, 1, 2), COMPACT_ACTIONS)
        assertEquals(
            listOf(MediaAction.PREVIOUS, MediaAction.PLAY_PAUSE, MediaAction.NEXT),
            COMPACT_ACTIONS.map { MediaAction.entries[it] },
        )
    }

    @Test
    fun `turn off is last and never in the compact view`() {
        assertEquals(MediaAction.TURN_OFF, MediaAction.entries.last())
        assertFalse(COMPACT_ACTIONS.any { MediaAction.entries[it] == MediaAction.TURN_OFF })
    }

    @Test
    fun `the session offers turn off only while the phone is unlocked`() {
        assertFalse(turnOffInSession(locked = true))
        assertTrue(turnOffInSession(locked = false))
        val state = QuickState(connected = true, power = true)
        assertFalse(cardFor(state).turnOff)
        assertFalse(cardFor(state, locked = true).turnOff)
        assertTrue(cardFor(state, locked = false).turnOff)
        // Locking changes the card, so the session is re-posted without it.
        assertTrue(cardFor(state, locked = true) != cardFor(state, locked = false))
    }

    @Test
    fun `reported playback leads the title, and only a known length gives a position`() {
        val state = QuickState(nowShowing = QuickNowShowing("com.example.video", "Video", null))
        val bare = cardFor(state)
        assertEquals("Video", bare.titleText())
        assertTrue(bare.playing())
        assertNull(bare.position())
        val paused = cardFor(state.copy(playback = QuickPlayback(title = "Episode 3", playing = false, positionMs = 60_000)))
        assertEquals("Episode 3", paused.titleText())
        assertFalse(paused.playing())
        assertNull(paused.position())
        val timed = cardFor(state.copy(playback = QuickPlayback(positionMs = 60_000, durationMs = 1_800_000)))
        assertEquals("Video", timed.titleText())
        assertEquals(60_000L, timed.position())
    }

    // endregion

    // region volume provider

    @Test
    fun `volume keys step, mute directions mute, others do nothing`() {
        assertEquals(MediaCommand.Step(+1), adjustCommand(AdjustDirection.RAISE))
        assertEquals(MediaCommand.Step(-1), adjustCommand(AdjustDirection.LOWER))
        assertEquals(MediaCommand.ToggleMute, adjustCommand(AdjustDirection.TOGGLE_MUTE))
        assertEquals(MediaCommand.Mute, adjustCommand(AdjustDirection.MUTE))
        assertEquals(MediaCommand.Unmute, adjustCommand(AdjustDirection.UNMUTE))
        assertNull(adjustCommand(AdjustDirection.SAME))
        assertNull(adjustCommand(42))
    }

    @Test
    fun `the system slider is clamped to the provider's range`() {
        assertEquals(MediaCommand.SetLevel(20), setLevelCommand(35, 20))
        assertEquals(MediaCommand.SetLevel(0), setLevelCommand(-3, 20))
        assertEquals(MediaCommand.SetLevel(7), setLevelCommand(7, 20))
        assertEquals(MediaCommand.SetLevel(0), setLevelCommand(5, -1))
    }

    @Test
    fun `the provider's current level stays inside its range`() {
        assertEquals(20, remoteVolumeFor(AudioLevel(VolumeTarget.BAR, 30, 20, false))?.current)
        assertNull(remoteVolumeFor(AudioLevel(VolumeTarget.TV, 0, 0, false)))
        assertNull(remoteVolumeFor(null))
    }

    @Test
    fun `a TV slider move becomes bounded key presses`() {
        assertEquals(5, tvStepsTo(current = 10, requested = 15, max = 60))
        assertEquals(-4, tvStepsTo(current = 10, requested = 6, max = 60))
        assertEquals(MAX_TV_STEPS, tvStepsTo(current = 0, requested = 60, max = 60))
        assertEquals(-MAX_TV_STEPS, tvStepsTo(current = 60, requested = -5, max = 60))
        assertEquals(0, tvStepsTo(current = 60, requested = 90, max = 60))
    }

    @Test
    fun `the session takes the keys while shown by default, only in front when narrowed`() {
        assertTrue(sessionTakesVolumeKeys(VolumeKeysScope.WHILE_SHOWN, shown = true, appInFront = false))
        assertFalse(sessionTakesVolumeKeys(VolumeKeysScope.WHILE_SHOWN, shown = false, appInFront = true))
        assertFalse(sessionTakesVolumeKeys(VolumeKeysScope.IN_FRONT, shown = true, appInFront = false))
        assertTrue(sessionTakesVolumeKeys(VolumeKeysScope.IN_FRONT, shown = true, appInFront = true))
    }

    // endregion

    @Test
    fun `posts are held apart, trailing`() {
        assertEquals(0, postDelay(now = 1_000, lastPost = null, interval = 400))
        assertEquals(300, postDelay(now = 1_100, lastPost = 1_000, interval = 400))
        assertEquals(0, postDelay(now = 2_000, lastPost = 1_000, interval = 400))
    }
}
