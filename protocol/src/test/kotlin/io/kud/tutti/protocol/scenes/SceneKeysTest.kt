package io.kud.tutti.protocol.scenes

import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.protocol.theatre.TheatreState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scene keys added in Maya's top 10, item 7: bar input, 360 Spatial, Voice Zoom, the TV's sound
 * output and the remote's target. Each one plans, matches, captures and survives storage; a scene
 * stored before them reads back exactly as it was written.
 */
class SceneKeysTest {
    private fun scene(vararg settings: Pair<SceneKey, SceneValue>) =
        Scene(id = "s", name = "Soir", color = 0xFF3355AA, glyph = "movie", settings = mapOf(*settings))

    private fun roundTrip(scene: Scene): Scene =
        SceneJson.decodeFromString(Scene.serializer(), SceneJson.encodeToString(Scene.serializer(), scene))

    private fun bar(vararg confirmed: Pair<String, String>) = TheatreState(connected = true, confirmed = mapOf(*confirmed))

    /** A scene as stored by a version without the new keys: every byte must come back. */
    private val oldStored =
        """{"id":"cinema","name":"Cinéma","color":4280391411,"glyph":"movie","settings":{"SOUND_FIELD":{"type":"flag","on":true},"VOLUME":{"type":"level","value":18}},"launchApp":"com.netflix.ninja"}"""

    @Test
    fun `an old-format scene reads and writes back unchanged`() {
        val decoded = SceneJson.decodeFromString(Scene.serializer(), oldStored)
        assertEquals(mapOf(SceneKey.SOUND_FIELD to SceneValue.Flag(true), SceneKey.VOLUME to SceneValue.Level(18)), decoded.settings)
        assertEquals(oldStored, SceneJson.encodeToString(Scene.serializer(), decoded))
    }

    @Test
    fun `bar input sets main input after the tv, and is captured from the bar`() {
        val s = scene(SceneKey.BAR_INPUT to SceneValue.Text("spotify"))
        assertEquals(s, roundTrip(s))
        val now = DeviceSnapshot.from(bar(TheatreFeature.INPUT.key to "tv"))
        assertEquals(SceneValue.Text("tv"), now[SceneKey.BAR_INPUT])
        assertEquals(listOf<SceneStep>(SceneStep.Bar(TheatreFeature.INPUT, "spotify")), SceneEngine.plan(s, now))
        assertEquals(SceneMatch.Modified(setOf(SceneKey.BAR_INPUT)), SceneEngine.match(s, now))
        val there = DeviceSnapshot.from(bar(TheatreFeature.INPUT.key to "spotify"))
        assertEquals(emptyList(), SceneEngine.plan(s, there))
        assertEquals(SceneMatch.Matches, SceneEngine.match(s, there))
    }

    @Test
    fun `360 spatial is a bar toggle, read and written as on or off`() {
        val s = scene(SceneKey.SPATIAL_360 to SceneValue.Flag(true))
        assertEquals(s, roundTrip(s))
        val off = DeviceSnapshot.from(bar(TheatreFeature.SPATIAL_360.key to "off"))
        assertEquals(SceneValue.Flag(false), off[SceneKey.SPATIAL_360])
        assertEquals(listOf<SceneStep>(SceneStep.Bar(TheatreFeature.SPATIAL_360, "on")), SceneEngine.plan(s, off))
        assertEquals(SceneMatch.Matches, SceneEngine.match(s, DeviceSnapshot.from(bar(TheatreFeature.SPATIAL_360.key to "on"))))
    }

    @Test
    fun `voice zoom is a bar toggle, unknown on a bar that never answered it`() {
        val s = scene(SceneKey.VOICE_ZOOM to SceneValue.Flag(false))
        assertEquals(s, roundTrip(s))
        val on = DeviceSnapshot.from(bar(TheatreFeature.VOICE_ZOOM.key to "on"))
        assertEquals(listOf<SceneStep>(SceneStep.Bar(TheatreFeature.VOICE_ZOOM, "off")), SceneEngine.plan(s, on))
        // Silent bar: unknown is not a change, and nothing is captured for it.
        val silent = DeviceSnapshot.from(bar())
        assertEquals(null, silent[SceneKey.VOICE_ZOOM])
        assertEquals(SceneMatch.Matches, SceneEngine.match(s, silent))
    }

    @Test
    fun `the tv's sound output plans before the bar, known only once the tv reported it`() {
        val s = scene(SceneKey.TV_AUDIO_OUTPUT to SceneValue.Text("audioSystem"), SceneKey.BAR_INPUT to SceneValue.Text("tv"))
        assertEquals(s, roundTrip(s))
        val now = DeviceSnapshot.from(bar(TheatreFeature.INPUT.key to "hdmi1"), tvAudioOutput = "speaker")
        assertEquals(
            listOf(SceneStep.TvAudioOutput("audioSystem"), SceneStep.Bar(TheatreFeature.INPUT, "tv")),
            SceneEngine.plan(s, now),
        )
        assertEquals(null, DeviceSnapshot.from(null)[SceneKey.TV_AUDIO_OUTPUT])
        assertEquals(SceneValue.Text("speaker"), SceneEngine.capture(now)[SceneKey.TV_AUDIO_OUTPUT])
    }

    @Test
    fun `the target goes last, after the input it holds against`() {
        val s = scene(
            SceneKey.TARGET to SceneValue.Text("mac-1"),
            SceneKey.TV_INPUT to SceneValue.Text("extInput:hdmi?port=3"),
            SceneKey.PICTURE_OFF to SceneValue.Flag(false),
        )
        assertEquals(s, roundTrip(s))
        val now = DeviceSnapshot(
            mapOf(SceneKey.TV_INPUT to SceneValue.Text("extInput:hdmi?port=1"), SceneKey.PICTURE_OFF to SceneValue.Flag(true), SceneKey.TARGET to SceneValue.Text("tv")),
            powered = true,
        )
        assertEquals(
            listOf(SceneStep.TvInput("extInput:hdmi?port=3"), SceneStep.PictureOff(false), SceneStep.Target("mac-1")),
            SceneEngine.plan(s, now),
        )
        assertEquals(SceneValue.Text("tv"), DeviceSnapshot.from(null, target = "tv")[SceneKey.TARGET])
        assertEquals(SceneMatch.Matches, SceneEngine.match(scene(SceneKey.TARGET to SceneValue.Text("tv")), DeviceSnapshot.from(null, target = "tv")))
    }

    @Test
    fun `undo puts every new key back as it was`() {
        val s = scene(SceneKey.BAR_INPUT to SceneValue.Text("spotify"), SceneKey.SPATIAL_360 to SceneValue.Flag(true), SceneKey.TARGET to SceneValue.Text("mac-1"))
        val before = DeviceSnapshot.from(bar(TheatreFeature.INPUT.key to "tv", TheatreFeature.SPATIAL_360.key to "off"), target = "tv")
        val undo = SceneUndo.capture(s, before, activeSceneId = null, now = 0)!!
        val after = DeviceSnapshot.from(bar(TheatreFeature.INPUT.key to "spotify", TheatreFeature.SPATIAL_360.key to "on"), target = "mac-1")
        assertEquals(
            listOf(SceneStep.Bar(TheatreFeature.INPUT, "tv"), SceneStep.Bar(TheatreFeature.SPATIAL_360, "off"), SceneStep.Target("tv")),
            undo.plan(after),
        )
    }

    @Test
    fun `a scene with the new keys reads in a version that knew none of them`() {
        // What an older build keeps: the strict known keys, the rest skipped (SceneSettingsSerializer).
        val stored = SceneJson.encodeToString(Scene.serializer(), scene(SceneKey.VOICE_ZOOM to SceneValue.Flag(true), SceneKey.NIGHT to SceneValue.Flag(true)))
        val older = stored.replace("VOICE_ZOOM", "A_KEY_FROM_THE_FUTURE")
        assertEquals(mapOf<SceneKey, SceneValue>(SceneKey.NIGHT to SceneValue.Flag(true)), SceneJson.decodeFromString(Scene.serializer(), older).settings)
    }
}
