package io.kud.tutti.protocol.scenes

import kotlin.test.Test
import kotlin.test.assertEquals

/** Scenes saved by a newer version must survive in this one: storage only ever grows keys. */
class SceneDecodingTest {
    private fun scene(settings: String) =
        """{"id":"cinema","name":"Cinéma","color":4280391411,"glyph":"movie","settings":{$settings}}"""

    @Test
    fun `an unknown key is skipped, the rest of the scene kept`() {
        val json = scene(""""VOLUME":{"type":"level","value":18},"SCREEN_OFF_FROM_THE_FUTURE":{"type":"flag","on":true}""")
        val decoded = SceneJson.decodeFromString(Scene.serializer(), json)
        assertEquals(mapOf(SceneKey.VOLUME to SceneValue.Level(18)), decoded.settings)
        assertEquals("Cinéma", decoded.name)
    }

    @Test
    fun `an unknown value kind is skipped, not the scene`() {
        val json = scene(""""NIGHT":{"type":"flag","on":true},"VOLUME":{"type":"curve","points":[1,2]}""")
        val decoded = SceneJson.decodeFromString(Scene.serializer(), json)
        assertEquals(mapOf(SceneKey.NIGHT to SceneValue.Flag(true)), decoded.settings)
    }

    @Test
    fun `an unreadable scene is left out, never the whole list`() {
        val list = "[${scene(""""BASS":{"type":"level","value":3}""")},{\"id\":\"broken\"}]"
        val decoded = SceneJson.decodeFromString(SceneListSerializer, list)
        assertEquals(listOf("cinema"), decoded.map { it.id })
        assertEquals(mapOf(SceneKey.BASS to SceneValue.Level(3)), decoded.single().settings)
    }

    @Test
    fun `what this version writes reads back identically`() {
        val all = StarterScenes.all
        val json = SceneJson.encodeToString(SceneListSerializer, all)
        assertEquals(all, SceneJson.decodeFromString(SceneListSerializer, json))
        // And the bytes are the strict format older versions read.
        assertEquals(json, SceneJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(Scene.serializer()), all))
    }
}
