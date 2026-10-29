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

    private fun read(json: String, layout: RemoteLayout = RemoteLayout()) =
        StoreJson.decodeFromString(DockConfig.serializer(), json).normalised(layout)

    private val defaultKeys = RemoteLayout().rowOnScreen.map { DockAction.Remote(it) }

    /** The pins after the joined row: the second row on, empty places left out. */
    private fun DockConfig.pins() = slots.drop(DockConfig.ROW).filter { it.isSet }

    @Test
    fun `a dock stored before phase one reads unchanged behind the row, apps and keys`() {
        val dock = read(stored)
        // The row he saw (the default, mirrored for the right hand) is the first row (2026-10-29).
        assertEquals(defaultKeys, dock.slots.take(DockConfig.ROW).map { it.primary })
        // The doors that stood fixed at the left are the next two pins (2026-10-28).
        val pins = dock.pins()
        assertEquals(DockAction.OpenApps, pins[0].primary)
        assertEquals(DockAction.OpenKeys, pins[1].primary)
        assertEquals(DockAction.ToggleBar("audio.nightmode"), pins[2].primary)
        assertEquals(DockAction.LaunchApp("com.netflix.ninja", "Netflix"), pins[3].primary)
        assertEquals(DockAction.Key(175, long = false), pins[4].primary)
        assertEquals(5, pins.size)
        pins.forEach {
            assertNull(it.label)
            assertNull(it.icon)
            assertNull(it.letters)
        }
        // The row's long presses stay where the keys read them.
        assertEquals(DockAction.Key(176), dock.longPress[SecondaryButton.HOME])
        assertEquals(DockAction.RunScene("scene-1"), dock.longPress[SecondaryButton.PLAY])
        assertNull(dock.legacySlots)
        assertTrue(dock.spare.isEmpty())
    }

    @Test
    fun `every old layout keeps every pin in order, the overflow kept as spare`() {
        // Up to 3 pages of 4, with gaps anywhere: the row, the doors, then each pin in its old order.
        for (pages in 1..3) for (gapEvery in listOf(0, 2, 3)) {
            val old = List(pages * 4) { i ->
                if (gapEvery != 0 && i % gapEvery == 1) DockSlot() else DockSlot(DockAction.Key(100 + i), label = "p$i")
            }
            val json = StoreJson.encodeToString(DockConfig.serializer(), DockConfig(slots = emptyList(), legacySlots = old))
                .replace("\"pins\":[],", "")
            val dock = read(json)
            val expected = defaultKeys + listOf(DockAction.OpenApps, DockAction.OpenKeys) + old.mapNotNull { it.primary }
            val kept = (dock.slots + dock.spare).filter { it.isSet }
            assertEquals(expected, kept.map { it.primary })
            assertEquals(DockConfig.PAGE_SIZE, dock.slots.size.coerceAtLeast(DockConfig.PAGE_SIZE))
            assertEquals(1, dock.pages.size)
        }
    }

    @Test
    fun `the round row and two pages of pins become one dock of twelve`() {
        // His own row of five, mirrored for the right hand, and fourteen pins over two pages.
        val row = listOf(RowButton.BACK, RowButton.HOME, RowButton.INFO, RowButton.KEYBOARD, RowButton.VOICE)
        val layout = RemoteLayout(row = row, rowChosen = true, hand = Hand.RIGHT)
        val pins = List(DockConfig.PAGE_SIZE * 2) { if (it % 3 == 2) "{}" else "{\"primary\":{\"type\":\"key\",\"keyCode\":${it + 1}}}" }
        val dock = read("{\"pins\":[${pins.joinToString(",")}],\"longPress\":{\"BACK\":{\"type\":\"key\",\"keyCode\":4}}}", layout)
        val keys = row.reversed().map { DockAction.Remote(it) }
        val stored = List(DockConfig.PAGE_SIZE * 2) { it }.filter { it % 3 != 2 }.map { DockAction.Key(it + 1) }
        // More than six pins: they follow the keys directly, so only what passes twelve goes spare.
        assertEquals(keys + stored.take(DockConfig.PAGE_SIZE - keys.size), dock.slots.map { it.primary })
        assertEquals(stored.drop(DockConfig.PAGE_SIZE - keys.size), dock.spare.map { it.primary })
        assertEquals(DockAction.Key(4), dock.longPress[SecondaryButton.BACK])
        // Nothing lost: every key and every pin is in the dock or in spare.
        assertEquals(keys.size + stored.size, dock.slots.count { it.isSet } + dock.spare.size)
        // Read again (and stored), merged once: the row is never added twice.
        assertEquals(dock, dock.normalised(layout))
        assertEquals(dock, read(StoreJson.encodeToString(DockConfig.serializer(), dock), RemoteLayout()))
    }

    @Test
    fun `a left hand row joins as he saw it, the pins on the second row`() {
        val layout = RemoteLayout(hand = Hand.LEFT)
        val dock = read("""{"pins":[{"primary":{"type":"open.apps"}},{"primary":{"type":"app","packageName":"a","label":"A"}}]}""", layout)
        assertEquals(RemoteLayout.DEFAULT_ROW.map { DockAction.Remote(it) }, dock.slots.take(6).map { it.primary })
        assertEquals(listOf(DockAction.OpenApps, DockAction.LaunchApp("a", "A")), dock.slots.drop(6).take(2).map { it.primary })
    }

    @Test
    fun `an untouched old default row joins as today's default`() {
        val old = listOf(RowButton.BACK, RowButton.HOME, RowButton.PLAY, RowButton.VOICE, RowButton.KEYBOARD)
        val dock = DockConfig().normalised(RemoteLayout(row = old))
        assertTrue(dock.holds(DockAction.Remote(RowButton.INPUT)))
    }

    @Test
    fun `spare pins go back into a free place or are let go, never lost by accident`() {
        val spare = listOf(DockSlot(DockAction.Key(1)), DockSlot(DockAction.Key(2)))
        val full = DockConfig(slots = List(DockConfig.PAGE_SIZE) { DockSlot(DockAction.Key(100 + it)) }, merged = true, spare = spare)
        assertTrue(full.full)
        assertEquals(full, full.withSpareRestored(0))
        val freed = full.withSlot(3, DockSlot())
        val back = freed.withSpareRestored(1)
        assertEquals(DockAction.Key(2), back.slots[3].primary)
        assertEquals(listOf(spare[0]), back.spare)
        assertEquals(emptyList<DockSlot>(), back.withSpareDropped(0).spare)
    }

    @Test
    fun `a dock never stored is the default row, then apps, keys and the two bar toggles`() {
        val dock = DockConfig().normalised()
        assertEquals(
            defaultKeys + listOf(DockAction.OpenApps, DockAction.OpenKeys, DockAction.ToggleBar("audio.nightmode"), DockAction.ToggleBar("audio.voiceenhancer")),
            dock.slots.filter { it.isSet }.map { it.primary },
        )
        // Back sits under the right thumb, at the first row's right end.
        assertEquals(DockAction.Remote(RowButton.BACK), dock.slots[DockConfig.ROW - 1].primary)
        assertTrue(dock.holds(DockAction.OpenApps))
        assertFalse(dock.withSlot(6, DockSlot()).holds(DockAction.OpenApps))
        assertTrue(dock.withSlot(6, DockSlot(secondary = DockAction.OpenApps)).holds(DockAction.OpenApps))
    }

    @Test
    fun `the new actions and a named pin survive a round trip`() {
        val dock = DockConfig(
            merged = true,
            slots = listOf(
                DockSlot(DockAction.Remote(RowButton.VOICE)),
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
        assertEquals(DockAction.Key(3), dock.pages[0][DockConfig.ROW].primary)
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
        val dock = DockConfig().normalised().withSlot(DockConfig.PAGE_SIZE - 1, DockSlot(macro, secondary = DockAction.Key(3, long = true), letters = "Ci"))
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
