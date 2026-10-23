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

    @Test
    fun `an old single-row dock is page one and nothing more`() {
        val dock = StoreJson.decodeFromString(DockConfig.serializer(), stored)
        assertEquals(1, dock.pages.size)
        assertEquals(dock.slots, dock.pages[0])
        dock.slots.forEach { assertNull(it.letters) }
        // Re-encoding an untouched old dock writes the same four slots, not a page model.
        val again = StoreJson.decodeFromString(DockConfig.serializer(), StoreJson.encodeToString(DockConfig.serializer(), dock))
        assertEquals(dock, again)
    }

    @Test
    fun `a short stored row is padded out to a whole page`() {
        val dock = StoreJson.decodeFromString(DockConfig.serializer(), """{"slots":[{"primary":{"type":"key","keyCode":3}}]}""")
        assertEquals(1, dock.pages.size)
        assertEquals(DockConfig.PAGE_SIZE, dock.pages[0].size)
        assertEquals(DockAction.Key(3), dock.pages[0][0].primary)
    }

    @Test
    fun `pages grow to three, take pins anywhere and shrink without touching the others`() {
        val one = DockConfig()
        val two = one.withPageAdded()
        assertEquals(2, two.pages.size)
        val pinned = two.withSlot(5, DockSlot(DockAction.Key(90), letters = "Ci"))
        assertEquals(DockAction.Key(90), pinned.pages[1][1].primary)
        assertEquals(one.slots, pinned.pages[0])
        val three = pinned.withPageAdded().withPageAdded()
        assertEquals(DockConfig.MAX_PAGES, three.pages.size)
        val back = three.withPageRemoved(1)
        assertEquals(2, back.pages.size)
        assertEquals(one.slots, back.pages[0])
        assertEquals(List(DockConfig.PAGE_SIZE) { DockSlot() }, back.pages[1])
        assertEquals(1, back.withPageRemoved(1).withPageRemoved(0).pages.size)
        // A pin saved past the end opens the page it needs.
        assertEquals(3, one.withSlot(9, DockSlot(DockAction.Key(3))).pages.size)
    }

    @Test
    fun `a pin with steps, waits, its own long press and letters survives a round trip`() {
        val macro = DockAction.Macro(
            "Ciné",
            listOf(
                MacroStep.Run(DockAction.Input("extInput:hdmi?port=2", "HDMI 2")),
                MacroStep.Wait(2_000),
                MacroStep.Run(DockAction.PictureMode("cinema", "Cinéma")),
            ),
        )
        val dock = DockConfig().withPageAdded().withSlot(4, DockSlot(macro, secondary = DockAction.Key(3, long = true), letters = "Ci"))
        val json = StoreJson.encodeToString(DockConfig.serializer(), dock)
        assertEquals(dock, StoreJson.decodeFromString(DockConfig.serializer(), json))
    }

    @Test
    fun `steps flatten nested macros and fold back to the simplest action`() {
        val inner = DockAction.Macro("in", listOf(MacroStep.Run(DockAction.Key(3)), MacroStep.Wait(500)))
        val steps = listOf(MacroStep.Run(inner), MacroStep.Run(DockAction.Key(4)))
        assertEquals(
            listOf(MacroStep.Run(DockAction.Key(3)), MacroStep.Wait(500), MacroStep.Run(DockAction.Key(4))),
            steps.flattened(),
        )
        assertEquals(DockAction.Key(4), listOf<MacroStep>(MacroStep.Run(DockAction.Key(4))).asAction("x"))
        assertNull(listOf<MacroStep>(MacroStep.Wait(500)).asAction("x"))
        assertEquals(listOf(MacroStep.Run(DockAction.Key(4))), DockAction.Key(4).asSteps())
        val folded = steps.asAction("Ciné") as DockAction.Macro
        assertEquals(3, folded.steps.size)
    }
}
