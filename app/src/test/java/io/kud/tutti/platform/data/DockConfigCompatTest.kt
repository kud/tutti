package io.kud.tutti.platform.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun read(json: String) = StoreJson.decodeFromString(DockConfig.serializer(), json).normalised()

    @Test
    fun `a dock stored before phase one reads unchanged behind apps and keys`() {
        val dock = read(stored)
        // The doors that stood fixed at the left are its first two pins (2026-10-28).
        assertEquals(DockAction.OpenApps, dock.slots[0].primary)
        assertEquals(DockAction.OpenKeys, dock.slots[1].primary)
        assertEquals(DockAction.ToggleBar("audio.nightmode"), dock.slots[2].primary)
        assertEquals(DockAction.LaunchApp("com.netflix.ninja", "Netflix"), dock.slots[3].primary)
        assertEquals(DockAction.Key(175, long = false), dock.slots[4].primary)
        assertEquals(5, dock.slots.size)
        dock.slots.forEach {
            assertNull(it.label)
            assertNull(it.icon)
            assertNull(it.letters)
        }
        assertEquals(DockAction.Key(176), dock.longPress[SecondaryButton.HOME])
        assertEquals(DockAction.RunScene("scene-1"), dock.longPress[SecondaryButton.PLAY])
        assertNull(dock.legacySlots)
    }

    @Test
    fun `every old layout up to three full pages keeps every pin in order`() {
        // Up to 3 pages of 4, with gaps anywhere: the doors first, then each pin in its old order.
        for (pages in 1..3) for (gapEvery in listOf(0, 2, 3)) {
            val old = List(pages * 4) { i ->
                if (gapEvery != 0 && i % gapEvery == 1) DockSlot() else DockSlot(DockAction.Key(100 + i), label = "p$i")
            }
            val json = StoreJson.encodeToString(DockConfig.serializer(), DockConfig(slots = emptyList(), legacySlots = old))
                .replace("\"pins\":[],", "")
            val dock = read(json)
            val expected = listOf(DockAction.OpenApps, DockAction.OpenKeys) + old.mapNotNull { it.primary }
            assertEquals(expected, dock.slots.map { it.primary })
            assertEquals(old.filter { it.primary != null }.map { it.label }, dock.slots.drop(2).map { it.label })
            assertTrue(dock.pages.size <= DockConfig.MAX_PAGES)
        }
        // Twelve old pins and the doors: page one full, two on page two, none lost.
        val twelve = List(12) { "{\"primary\":{\"type\":\"key\",\"keyCode\":${it + 1}}}" }.joinToString(",")
        val full = read("{\"slots\":[$twelve]}")
        assertEquals(14, full.slots.size)
        assertEquals(2, full.pages.size)
        assertEquals(DockAction.Key(12), full.pages[1][1].primary)
    }

    @Test
    fun `a migrated dock is stored in the new shape and reads back the same`() {
        val dock = read(stored)
        val json = StoreJson.encodeToString(DockConfig.serializer(), dock)
        assertEquals(dock, read(json))
        // Read twice, migrated once: the doors are never added a second time.
        assertEquals(dock, dock.normalised())
    }

    @Test
    fun `a dock never stored is apps, keys, then the two bar toggles`() {
        val dock = DockConfig().normalised()
        assertEquals(
            listOf(DockAction.OpenApps, DockAction.OpenKeys, DockAction.ToggleBar("audio.nightmode"), DockAction.ToggleBar("audio.voiceenhancer")),
            dock.slots.map { it.primary },
        )
        assertTrue(dock.holds(DockAction.OpenApps))
        assertFalse(dock.withSlot(0, DockSlot()).holds(DockAction.OpenApps))
        assertTrue(dock.withSlot(0, DockSlot(secondary = DockAction.OpenApps)).holds(DockAction.OpenApps))
    }

    @Test
    fun `the new actions and a named pin survive a round trip`() {
        val dock = DockConfig(
            slots = listOf(
                DockSlot(DockAction.SetBar("main.bassstep", "-2"), label = "Ciné", icon = "movie"),
                DockSlot(DockAction.Key(90, long = true)),
                DockSlot(DockAction.Ircc("ActionMenu", "AAAAAgAAAMQAAABAAw==")),
                DockSlot(DockAction.PictureMode("cinema", "Cinéma")),
                DockSlot(DockAction.TvSoundOutput("audioSystem")),
                DockSlot(DockAction.OpenKeys, secondary = DockAction.OpenApps),
            ),
            longPress = mapOf(SecondaryButton.BACK to DockAction.ToggleBar("audio.voiceenhancer")),
        )
        val json = StoreJson.encodeToString(DockConfig.serializer(), dock)
        assertEquals(dock, read(json))
    }

    @Test
    fun `a short dock is padded out to a whole page of two rows`() {
        val dock = read("""{"pins":[{"primary":{"type":"key","keyCode":3}}]}""")
        assertEquals(1, dock.pages.size)
        assertEquals(DockConfig.PAGE_SIZE, dock.pages[0].size)
        assertEquals(DockAction.Key(3), dock.pages[0][0].primary)
    }

    @Test
    fun `pages grow to two, take pins anywhere and shrink without touching the others`() {
        val one = DockConfig()
        val two = one.withPageAdded()
        assertEquals(2, two.pages.size)
        val pinned = two.withSlot(DockConfig.PAGE_SIZE + 1, DockSlot(DockAction.Key(90), letters = "Ci"))
        assertEquals(DockAction.Key(90), pinned.pages[1][1].primary)
        assertEquals(one.pages[0], pinned.pages[0])
        assertEquals(DockConfig.MAX_PAGES, pinned.withPageAdded().pages.size)
        val back = pinned.withPageRemoved(1)
        assertEquals(1, back.pages.size)
        assertEquals(one.pages[0], back.pages[0])
        assertEquals(1, back.withPageRemoved(0).pages.size)
        // A pin saved past the end opens the page it needs, never more than two.
        assertEquals(2, one.withSlot(13, DockSlot(DockAction.Key(3))).pages.size)
        assertEquals(DockConfig.MAX_PAGES * DockConfig.PAGE_SIZE, one.withSlot(40, DockSlot(DockAction.Key(3))).slots.size)
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
        val dock = DockConfig().withPageAdded().withSlot(DockConfig.PAGE_SIZE, DockSlot(macro, secondary = DockAction.Key(3, long = true), letters = "Ci"))
        val json = StoreJson.encodeToString(DockConfig.serializer(), dock)
        assertEquals(dock, read(json))
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
