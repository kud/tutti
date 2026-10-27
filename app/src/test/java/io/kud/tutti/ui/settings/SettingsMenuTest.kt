package io.kud.tutti.ui.settings

import io.kud.tutti.controller.Link
import io.kud.tutti.controller.Links
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.controller.TvSettingsState
import io.kud.tutti.controller.TvState
import io.kud.tutti.platform.data.AppPrefs
import io.kud.tutti.platform.data.BarDevice
import io.kud.tutti.platform.data.Devices
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.platform.data.SecondaryButton
import io.kud.tutti.platform.data.ThemeMode
import io.kud.tutti.platform.data.VibrationLevel
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.protocol.theatre.TheatreState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings' top level: the categories in their order and each row's live line (Iris, 2026-10-26, step 2). */
class SettingsMenuTest {
    private val tv = io.kud.tutti.platform.data.TvDevice(host = "192.168.0.10", name = "Living TV")
    private val bar = BarDevice(host = "192.168.0.11", name = "Bar")
    private val both = Devices(tv = tv, bar = bar)

    @Test
    fun `eight rows, what tutti can do first and about last`() {
        assertEquals(
            listOf(
                SettingsCategory.UNLOCK, SettingsCategory.DEVICES, SettingsCategory.TV, SettingsCategory.SOUNDBAR,
                SettingsCategory.REMOTE, SettingsCategory.DISPLAY, SettingsCategory.ADVANCED, SettingsCategory.ABOUT,
            ),
            SettingsCategory.entries,
        )
    }

    @Test
    fun `devices names every device, a tv to pair again first`() {
        val fine = TuttiUiState(devices = both, links = Links(tv = Link.ONLINE))
        assertEquals(SettingsMenu.DevicesLine(listOf("Living TV", "Bar", "Laptop")), SettingsMenu.devicesLine(fine, listOf("Laptop")))
        val pairAgain = fine.copy(links = Links(tv = Link.NEEDS_PAIRING))
        assertEquals(
            SettingsMenu.DevicesLine(listOf("Bar"), SettingsMenu.Trouble("Living TV", Link.NEEDS_PAIRING)),
            SettingsMenu.devicesLine(pairAgain, emptyList()),
        )
        // Asleep or out of reach is no fault to lead with.
        assertEquals(null, SettingsMenu.devicesLine(fine.copy(links = Links(tv = Link.UNREACHABLE)), emptyList()).trouble)
        assertEquals(SettingsMenu.DevicesLine(emptyList()), SettingsMenu.devicesLine(TuttiUiState(), emptyList()))
    }

    @Test
    fun `the tv line says ip control in words before any value`() {
        assertEquals(SettingsMenu.TvLine.NoTv, SettingsMenu.tvLine(TuttiUiState()))
        val down = TuttiUiState(devices = both, links = Links(rest = Link.UNREACHABLE))
        assertEquals(SettingsMenu.TvLine.IpControl(Link.UNREACHABLE), SettingsMenu.tvLine(down))
        val online = TuttiUiState(
            devices = both,
            links = Links(rest = Link.ONLINE),
            tv = TvState(pictureMode = "standard", settings = TvSettingsState(loaded = true, powerSaving = "off", powerSavingSupported = true)),
        )
        assertEquals(SettingsMenu.TvLine.Settled("standard", "off"), SettingsMenu.tvLine(online))
        // Before the TV's settings load, power saving isn't known: the picture mode alone.
        val early = online.copy(tv = TvState(pictureMode = "cinema"))
        assertEquals(SettingsMenu.TvLine.Settled("cinema", null), SettingsMenu.tvLine(early))
    }

    @Test
    fun `the soundbar line gives its input and hidden inputs once online`() {
        assertEquals(SettingsMenu.BarLine.NoBar, SettingsMenu.barLine(TuttiUiState()))
        val offline = TuttiUiState(devices = both, links = Links(bar = Link.UNREACHABLE))
        assertEquals(SettingsMenu.BarLine.Offline(Link.UNREACHABLE), SettingsMenu.barLine(offline))
        val online = offline.copy(
            links = Links(bar = Link.ONLINE),
            bar = TheatreState(connected = true, confirmed = mapOf(TheatreFeature.INPUT.key to "tv")),
            prefs = AppPrefs(hiddenBarInputs = setOf("airplay2", "spotify")),
        )
        assertEquals(SettingsMenu.BarLine.Online("tv", 2), SettingsMenu.barLine(online))
    }

    @Test
    fun `remote and dock counts pins and long presses`() {
        val dock = DockConfig(
            slots = listOf(DockSlot(DockAction.Key(3)), DockSlot(), DockSlot(DockAction.Key(4))),
            longPress = mapOf(SecondaryButton.HOME to DockAction.Key(3)),
        )
        assertEquals(SettingsMenu.RemoteLine(pins = 2, longPresses = 1), SettingsMenu.remoteLine(TuttiUiState(dock = dock)))
        assertEquals(SettingsMenu.RemoteLine(0, 0), SettingsMenu.remoteLine(TuttiUiState(dock = DockConfig(slots = emptyList()))))
    }

    @Test
    fun `display and feedback reads the preferences`() {
        val prefs = AppPrefs(theme = ThemeMode.DARK, trueBlack = true, vibration = VibrationLevel.ESSENTIALS, keySounds = true)
        assertEquals(
            SettingsMenu.DisplayLine(ThemeMode.DARK, trueBlack = true, darkRoom = false, vibration = VibrationLevel.ESSENTIALS, keySounds = true),
            SettingsMenu.displayLine(TuttiUiState(prefs = prefs)),
        )
    }

    @Test
    fun `only devices, remote, display and advanced are pages of settings itself`() {
        assertEquals(
            setOf(SettingsCategory.DEVICES, SettingsCategory.REMOTE, SettingsCategory.DISPLAY, SettingsCategory.ADVANCED),
            SettingsMenu.pages,
        )
    }
}
