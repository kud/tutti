package io.kud.tutti.protocol.scenes

import io.kud.tutti.protocol.scenes.SceneValue.Flag
import io.kud.tutti.protocol.scenes.SceneValue.Level
import io.kud.tutti.protocol.scenes.SceneValue.Text
import io.kud.tutti.protocol.theatre.TheatreFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneUndoTest {
    private val night = Scene(
        "night", "Nuit", 0, "bedtime",
        mapOf(SceneKey.NIGHT to Flag(true), SceneKey.VOICE to Flag(true), SceneKey.VOLUME_CAP to Level(20)),
    )
    private val cinema = Scene("cinema", "Cinéma", 0, "movie", mapOf(SceneKey.SOUND_FIELD to Flag(true)), launchApp = "com.netflix.ninja")

    private fun snapshot(vararg values: Pair<SceneKey, SceneValue>, powered: Boolean? = true) = DeviceSnapshot(mapOf(*values), powered)

    @Test
    fun `capture keeps only what the scene touches, the cap taking the volume`() {
        val before = snapshot(
            SceneKey.NIGHT to Flag(false),
            SceneKey.VOICE to Flag(false),
            SceneKey.BASS to Level(3),
            SceneKey.VOLUME to Level(35),
            SceneKey.SOUND_FIELD to Flag(true),
        )
        val undo = SceneUndo.capture(night, before, activeSceneId = "cinema", now = 1_000)!!
        assertEquals(
            mapOf(SceneKey.NIGHT to Flag(false), SceneKey.VOICE to Flag(false), SceneKey.VOLUME to Level(35)),
            undo.before,
        )
        assertEquals("cinema", undo.previousSceneId)
        assertEquals("night", undo.sceneId)
    }

    @Test
    fun `restoring brings back the captured values and never wakes or switches off`() {
        val undo = SceneUndo.capture(night, snapshot(SceneKey.NIGHT to Flag(false), SceneKey.VOLUME to Level(35)), null, 0)!!
        val after = snapshot(SceneKey.NIGHT to Flag(true), SceneKey.VOLUME to Level(20), powered = false)
        assertEquals(
            listOf(SceneStep.Bar(TheatreFeature.NIGHT, "off"), SceneStep.Bar(TheatreFeature.VOLUME, "35")),
            undo.plan(after),
        )
    }

    @Test
    fun `an app launched by a scene is undone by going back to the input or app before`() {
        val onHdmi = snapshot(SceneKey.TV_INPUT to Text("extInput:hdmi?port=1"), SceneKey.TV_APP to Text("com.google.android.tvlauncher"))
        val undo = SceneUndo.capture(cinema, onHdmi, null, 0)!!
        // An HDMI input makes the foreground app meaningless (SceneEngine.capture).
        assertEquals(mapOf(SceneKey.TV_INPUT to Text("extInput:hdmi?port=1")), undo.before)
        val now = snapshot(SceneKey.TV_APP to Text("com.netflix.ninja"))
        assertEquals(listOf(SceneStep.TvInput("extInput:hdmi?port=1")), undo.plan(now))

        val onYouTube = snapshot(SceneKey.TV_APP to Text("com.google.android.youtube.tv"))
        val back = SceneUndo.capture(cinema, onYouTube, null, 0)!!
        assertEquals(listOf(SceneStep.TvApp("com.google.android.youtube.tv")), back.plan(now))
    }

    @Test
    fun `values unknown at capture are left alone`() {
        val undo = SceneUndo.capture(night, snapshot(SceneKey.NIGHT to Flag(false)), null, 0)!!
        assertEquals(listOf(SceneStep.Bar(TheatreFeature.NIGHT, "off")), undo.plan(snapshot(SceneKey.NIGHT to Flag(true), SceneKey.VOICE to Flag(true))))
    }

    @Test
    fun `nothing to go back to gives no undo`() {
        assertNull(SceneUndo.capture(night, snapshot(), activeSceneId = "night", now = 0))
        // Another scene was active: going back to it is still an undo, even with no value known.
        assertEquals(SceneUndo("night", "cinema", emptyMap(), 0), SceneUndo.capture(night, snapshot(), "cinema", 0))
    }

    @Test
    fun `an undo goes stale with time, another scene or the room switched off`() {
        val undo = SceneUndo("night", null, mapOf(SceneKey.NIGHT to Flag(false)), takenAt = 10_000)
        assertFalse(undo.stale(10_000 + SceneUndo.WINDOW_MS, "night", true))
        assertTrue(undo.stale(10_001 + SceneUndo.WINDOW_MS, "night", true))
        assertTrue(undo.stale(9_999, "night", true), "a clock that went back")
        assertTrue(undo.stale(10_500, "cinema", true))
        assertTrue(undo.stale(10_500, null, true))
        assertTrue(undo.stale(10_500, "night", false))
        assertFalse(undo.stale(10_500, "night", null), "unknown power is not off")
    }
}
