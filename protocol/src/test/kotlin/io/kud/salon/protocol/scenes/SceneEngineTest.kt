package io.kud.salon.protocol.scenes

import io.kud.salon.protocol.scenes.SceneValue.Flag
import io.kud.salon.protocol.scenes.SceneValue.Level
import io.kud.salon.protocol.scenes.SceneValue.Text
import io.kud.salon.protocol.theatre.TheatreFeature
import io.kud.salon.protocol.theatre.TheatreState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SceneEngineTest {
    private val cinema = Scene(
        id = "c",
        name = "Cinéma",
        color = 0,
        glyph = "movie",
        settings = mapOf(
            SceneKey.TV_INPUT to Text("extInput:hdmi?port=2"),
            SceneKey.SOUND_FIELD to Flag(true),
            SceneKey.VOICE to Flag(false),
            SceneKey.BASS to Level(2),
            SceneKey.VOLUME to Level(35),
            SceneKey.VOLUME_CAP to Level(30),
        ),
        launchApp = "com.netflix.ninja",
    )

    private fun snapshot(vararg values: Pair<SceneKey, SceneValue>, powered: Boolean? = true) = DeviceSnapshot(mapOf(*values), powered)

    @Test
    fun `plan orders steps, skips matching ones and clamps volume to the cap`() {
        val now = snapshot(
            SceneKey.TV_INPUT to Text("extInput:hdmi?port=1"),
            SceneKey.SOUND_FIELD to Flag(true),
            SceneKey.VOICE to Flag(true),
            SceneKey.BASS to Level(2),
            SceneKey.VOLUME to Level(10),
            powered = false,
        )
        assertEquals(
            listOf(
                SceneStep.WakeUp,
                SceneStep.TvInput("extInput:hdmi?port=2"),
                SceneStep.Bar(TheatreFeature.VOICE, "upoff"),
                SceneStep.Bar(TheatreFeature.VOLUME, "30"),
                SceneStep.LaunchApp("com.netflix.ninja"),
            ),
            SceneEngine.plan(cinema, now),
        )
    }

    @Test
    fun `a cap alone only lowers a loud volume`() {
        val night = Scene("n", "Nuit", 0, "bedtime", mapOf(SceneKey.NIGHT to Flag(true), SceneKey.VOLUME_CAP to Level(20)))
        assertEquals(
            listOf(SceneStep.Bar(TheatreFeature.NIGHT, "on"), SceneStep.Bar(TheatreFeature.VOLUME, "20")),
            SceneEngine.plan(night, snapshot(SceneKey.NIGHT to Flag(false), SceneKey.VOLUME to Level(45))),
        )
        assertEquals(emptyList(), SceneEngine.plan(night, snapshot(SceneKey.NIGHT to Flag(true), SceneKey.VOLUME to Level(12))))
        assertEquals(emptyList(), SceneEngine.plan(night, snapshot(SceneKey.NIGHT to Flag(true))))
    }

    @Test
    fun `match reports modified keys and ignores unknown ones`() {
        val applied = snapshot(
            SceneKey.TV_INPUT to Text("extInput:hdmi?port=2"),
            SceneKey.SOUND_FIELD to Flag(true),
            SceneKey.VOICE to Flag(false),
            SceneKey.BASS to Level(2),
            SceneKey.VOLUME to Level(30),
        )
        // VOLUME 30 vs wanted 35 is the cap at work, but it is still a difference from the stored value.
        assertEquals(SceneMatch.Modified(setOf(SceneKey.VOLUME)), SceneEngine.match(cinema, applied))

        val noVolume = cinema.copy(settings = cinema.settings - SceneKey.VOLUME)
        assertEquals(SceneMatch.Matches, SceneEngine.match(noVolume, applied))
        assertEquals(SceneMatch.Matches, SceneEngine.match(noVolume, snapshot()))

        val louder = DeviceSnapshot(applied.values + (SceneKey.VOLUME to Level(40)), true)
        assertEquals(SceneMatch.Modified(setOf(SceneKey.VOLUME_CAP)), SceneEngine.match(noVolume, louder))

        val voiceOn = DeviceSnapshot(applied.values + (SceneKey.VOICE to Flag(true)), true)
        assertEquals(SceneMatch.Modified(setOf(SceneKey.VOICE)), SceneEngine.match(noVolume, voiceOn))
    }

    @Test
    fun `capture keeps every known value but the app behind an hdmi input`() {
        val captured = SceneEngine.capture(
            snapshot(
                SceneKey.TV_INPUT to Text("extInput:hdmi?port=3"),
                SceneKey.TV_APP to Text("com.google.android.tvlauncher"),
                SceneKey.NIGHT to Flag(true),
                SceneKey.VOLUME to Level(18),
            ),
        )
        assertEquals(setOf(SceneKey.TV_INPUT, SceneKey.NIGHT, SceneKey.VOLUME), captured.keys)

        val appOnly = SceneEngine.capture(snapshot(SceneKey.TV_APP to Text("com.netflix.ninja")))
        assertEquals(Text("com.netflix.ninja"), appOnly[SceneKey.TV_APP])
    }

    @Test
    fun `snapshot reads the bar state with its own vocabulary`() {
        val theatre = TheatreState(
            confirmed = mapOf(
                TheatreFeature.POWER.key to "on",
                TheatreFeature.VOICE.key to "upon",
                TheatreFeature.NIGHT.key to "off",
                TheatreFeature.VOLUME.key to "21",
                TheatreFeature.BASS.key to "-2",
            ),
        )
        val snap = DeviceSnapshot.from(theatre, tvInput = "extInput:hdmi?port=1", tvPowered = true)
        assertEquals(Flag(true), snap[SceneKey.VOICE])
        assertEquals(Flag(false), snap[SceneKey.NIGHT])
        assertEquals(Level(21), snap[SceneKey.VOLUME])
        assertEquals(Level(-2), snap[SceneKey.BASS])
        assertEquals(null, snap[SceneKey.SOUND_FIELD])
        assertEquals(true, snap.powered)
    }

    @Test
    fun `scenes round-trip through json`() {
        val json = SceneJson.encodeToString(Scene.serializer(), cinema)
        assertEquals(cinema, SceneJson.decodeFromString(Scene.serializer(), json))
        assertTrue(StarterScenes.all.any { it.name == "Nuit" && it.settings[SceneKey.VOLUME_CAP] == Level(20) })
        assertEquals(7, StarterScenes.all.size)
    }

    @Test
    fun `runner reports progress and keeps going after a failure`() = runTest {
        val steps = listOf(SceneStep.WakeUp, SceneStep.Bar(TheatreFeature.NIGHT, "on"), SceneStep.LaunchApp("x"))
        val actuator = object : SceneActuator {
            override suspend fun perform(step: SceneStep): Boolean = step != SceneStep.WakeUp
        }
        val progress = SceneRunner(actuator).run(steps).toList()
        assertEquals(listOf(0, 1, 2, 3), progress.map { it.done })
        assertEquals(listOf<SceneStep>(SceneStep.WakeUp), progress.last().failed)
        assertTrue(progress.last().finished)
        assertFalse(progress.first().finished)
    }

    @Test
    fun `per-app profiles pick the scene for the foreground app`() {
        val profiles = listOf(AppProfile("com.netflix.ninja", "starter-cinema"))
        assertEquals("starter-cinema", AppProfiles.sceneFor("com.netflix.ninja", profiles))
        assertEquals(null, AppProfiles.sceneFor("org.xbmc.kodi", profiles))
    }
}
