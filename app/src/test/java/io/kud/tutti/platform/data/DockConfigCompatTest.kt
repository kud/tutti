package io.kud.tutti.platform.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A dock saved before pins had a name, an icon or a held key must read exactly as it was. */
class DockConfigCompatTest {
    private val stored = """
        {"slots":[
          {"primary":{"type":"toggle","featureKey":"audio.nightmode"},"secondary":null},
          {"primary":{"type":"app","packageName":"com.netflix.ninja","label":"Netflix"}},
          {"primary":{"type":"key","keyCode":175}},
          {}
        ],
        "longPress":{"HOME":{"type":"key","keyCode":176},"PLAY":{"type":"scene","sceneId":"scene-1"}}}
    """.trimIndent()

    @Test
    fun `a dock stored before phase one reads unchanged`() {
        val dock = StoreJson.decodeFromString(DockConfig.serializer(), stored)
        assertEquals(DockAction.ToggleBar("audio.nightmode"), dock.slots[0].primary)
        assertEquals(DockAction.LaunchApp("com.netflix.ninja", "Netflix"), dock.slots[1].primary)
        assertEquals(DockAction.Key(175, long = false), dock.slots[2].primary)
        assertNull(dock.slots[3].primary)
        dock.slots.forEach {
            assertNull(it.label)
            assertNull(it.icon)
        }
        assertEquals(DockAction.Key(176), dock.longPress[SecondaryButton.HOME])
        assertEquals(DockAction.RunScene("scene-1"), dock.longPress[SecondaryButton.PLAY])
    }

    @Test
    fun `the new actions and a named pin survive a round trip`() {
        val dock = DockConfig(
            slots = listOf(
                DockSlot(DockAction.SetBar("main.bassstep", "-2"), label = "Ciné", icon = "movie"),
                DockSlot(DockAction.Key(90, long = true)),
                DockSlot(DockAction.Ircc("ActionMenu", "AAAAAgAAAMQAAABAAw==")),
                DockSlot(DockAction.PictureMode("cinema", "Cinéma")),
            ),
            longPress = mapOf(SecondaryButton.BACK to DockAction.ToggleBar("audio.voiceenhancer")),
        )
        val json = StoreJson.encodeToString(DockConfig.serializer(), dock)
        assertEquals(dock, StoreJson.decodeFromString(DockConfig.serializer(), json))
    }
}
