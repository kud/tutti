package io.kud.tutti.platform.data

import io.kud.tutti.protocol.layout.LayoutCodec
import io.kud.tutti.protocol.layout.LayoutRead
import io.kud.tutti.protocol.scenes.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTransferTest {

    private val cinema = Scene(id = "scene-a1", name = "Cinema", color = 0xFF336699, glyph = "movie")
    private val evening = Scene(id = "scene-b2", name = "Evening", color = 0xFF996633, glyph = "bedtime")

    private val layout = RemoteLayout(hand = Hand.LEFT, dockLabels = DockLabels.NAME, scenes = false)
    private val dock = DockConfig(
        slots = listOf(
            DockSlot(DockAction.Remote(RowButton.BACK), secondary = DockAction.Key(3)),
            DockSlot(DockAction.OpenApps),
            DockSlot(DockAction.LaunchApp("com.example.player", "Player"), DockAction.Key(85, long = true), label = "Films", icon = "movie"),
            DockSlot(DockAction.RunScene(cinema.id), label = "Ciné"),
            DockSlot(DockAction.Input("extInput:hdmi?port=2", "Console"), letters = "PS"),
            DockSlot(DockAction.Macro("Late", listOf(MacroStep.Run(DockAction.TvSoundOutput("audioSystem")), MacroStep.Wait(800), MacroStep.Run(DockAction.RunScene(evening.id))))),
        ) + List(6) { DockSlot() },
        longPress = mapOf(SecondaryButton.HOME to DockAction.ToggleBar("audio.nightmode")),
        merged = true,
        spare = listOf(DockSlot(DockAction.Remote(RowButton.GUIDE))),
    )

    private fun roundTrip(scenesHere: List<Scene>, current: RemoteLayout = RemoteLayout()): LayoutTransfer.Import {
        val export = LayoutTransfer.export(layout, dock, listOf(cinema, evening))
        val read = LayoutCodec.decode(LayoutCodec.encode(export.file)) as LayoutRead.Ok
        return LayoutTransfer.import(read.file, read.skipped, current, scenesHere)
    }

    @Test
    fun aLayoutComesBackWholeOnAPhoneWithTheSameScenes() {
        // Same names, other ids: another phone, or scenes made again.
        val here = listOf(cinema.copy(id = "other-1"), evening.copy(id = "other-2"))
        val result = roundTrip(here)
        assertEquals(emptyList<LayoutTransfer.Skip>(), result.skipped)
        assertEquals(Hand.LEFT, result.layout.hand)
        assertEquals(DockLabels.NAME, result.layout.dockLabels)
        assertFalse(result.layout.scenes)
        assertEquals(DockAction.RunScene("other-1"), result.dock.slot(3).primary)
        assertEquals(dock.slot(2), result.dock.slot(2))
        assertEquals(dock.slot(4), result.dock.slot(4))
        assertEquals(dock.longPress, result.dock.longPress)
        assertEquals(dock.spare, result.dock.spare)
        assertTrue(result.dock.merged)
        assertEquals(6, result.pins)
    }

    @Test
    fun aRemoteKeyKeepsOneLongPressTheRowOne() {
        val result = roundTrip(listOf(cinema, evening))
        assertEquals(DockSlot(DockAction.Remote(RowButton.BACK)), result.dock.slot(0))
    }

    @Test
    fun aPinWhoseSceneIsMissingIsSkippedAndSaidByName() {
        val result = roundTrip(listOf(cinema))
        // The macro pin has no name of its own, so the line names the scene it lacks.
        assertEquals(listOf(LayoutTransfer.Skip.SceneMissing("Evening")), result.skipped)
        assertFalse(result.dock.slot(5).isSet)
        assertTrue(result.dock.slot(3).isSet)
    }

    @Test
    fun theRowAndTheParkedFieldsOfTheCurrentLayoutStay() {
        val current = RemoteLayout(row = listOf(RowButton.HOME), rowChosen = true, padSize = PadSize.LARGE)
        val result = roundTrip(listOf(cinema, evening), current)
        assertEquals(current.row, result.layout.row)
        assertTrue(result.layout.rowChosen)
        assertEquals(PadSize.LARGE, result.layout.padSize)
    }

    @Test
    fun aChannelOrAGoneSceneStaysOnThePhoneAndIsCounted() {
        val withChannel = dock.withSlot(6, DockSlot(DockAction.Input("tv:dvbt?trip=1.2.3", "Channel 1")))
        val export = LayoutTransfer.export(layout, withChannel, listOf(evening))
        assertEquals(2, export.leftOut)
        assertTrue(export.file.pins[6] == null && export.file.pins[3] == null)
    }

    /**
     * The privacy line, end to end: a phone full of devices, hosts, MACs and names exports a file
     * with none of them. Export only ever receives the layout and the dock; this pins that down.
     */
    @Test
    fun noDeviceOrHomeIdentifierReachesTheFile() {
        val devices = Devices(TvDevice(host = "192.0.2.10", name = "Living room TV", mac = "02:00:00:00:00:01"), BarDevice(host = "192.0.2.11", name = "Kitchen bar"))
        val text = LayoutCodec.encode(LayoutTransfer.export(layout, dock, listOf(cinema, evening)).file)
        listOfNotNull(devices.tv?.host, devices.tv?.name, devices.tv?.mac, devices.bar?.host, devices.bar?.name, cinema.id, evening.id).forEach {
            assertFalse("$it leaked into the file", it in text)
        }
        assertTrue(text.contains("\"tutti-layout\""))
    }
}
