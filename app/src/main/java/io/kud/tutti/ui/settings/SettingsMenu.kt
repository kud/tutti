package io.kud.tutti.ui.settings

import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.ThemeMode
import io.kud.tutti.platform.data.VibrationLevel
import io.kud.tutti.protocol.theatre.TheatreFeature

/** Settings' top level, one row each, in this order (Iris, 2026-10-26, docs/proposals/settings-structure.md). */
enum class SettingsCategory { UNLOCK, DEVICES, TV, SOUNDBAR, REMOTE, DISPLAY, ADVANCED, ABOUT }

/**
 * What Settings' top level shows, kept apart from Compose so it can be tested: which categories
 * have a screen of their own, and each row's live line as values, said in words by the screen.
 */
internal object SettingsMenu {

    /**
     * The categories whose screen is a page of Settings itself. The others open a screen that was
     * already there: What tutti can do, the TV hub, the soundbar's settings, About.
     */
    val pages: Set<SettingsCategory> = setOf(SettingsCategory.DEVICES, SettingsCategory.REMOTE, SettingsCategory.DISPLAY, SettingsCategory.ADVANCED)

    /** Devices' live line: every device by name, a TV that has to be paired again first. */
    data class DevicesLine(val names: List<String>, val trouble: Trouble? = null)

    /** A device whose link needs him: its name and the link's state, said before the others. */
    data class Trouble(val name: String, val link: Link)

    fun devicesLine(state: TuttiUiState, computers: List<String>): DevicesLine {
        val tv = state.devices.tv
        // Only what asks for an action of his: a TV asleep or out of reach is no fault (design.md).
        val trouble = tv?.takeIf { state.links.tv == Link.NEEDS_PAIRING || state.links.tv == Link.UNAUTHORIZED }?.let { Trouble(it.name, state.links.tv) }
        val names = listOfNotNull(tv?.name?.takeIf { trouble == null }, state.devices.bar?.name) + computers
        return DevicesLine(names.filter { it.isNotBlank() }, trouble)
    }

    /** The TV's live line. */
    sealed interface TvLine {
        data object NoTv : TvLine

        /** IP control isn't answering: the TV's settings ride it, so the row says so. */
        data class IpControl(val link: Link) : TvLine

        /** The picture mode (from the remote's own poll) and power saving, each when known. */
        data class Settled(val pictureMode: String?, val powerSaving: String?) : TvLine
    }

    fun tvLine(state: TuttiUiState): TvLine {
        if (state.devices.tv == null) return TvLine.NoTv
        if (state.links.rest != Link.ONLINE) return TvLine.IpControl(state.links.rest)
        val settings = state.tv.settings
        return TvLine.Settled(
            pictureMode = TvHub.pictureLine(state.tv).mode,
            powerSaving = settings.powerSaving.takeIf { settings.powerSavingSupported },
        )
    }

    /** The soundbar's live line. */
    sealed interface BarLine {
        data object NoBar : BarLine

        data class Offline(val link: Link) : BarLine

        /** The input it plays from, when reported, and how many inputs are hidden from the Son sheet. */
        data class Online(val input: String?, val hidden: Int) : BarLine
    }

    fun barLine(state: TuttiUiState): BarLine = when {
        state.devices.bar == null -> BarLine.NoBar
        state.links.bar != Link.ONLINE -> BarLine.Offline(state.links.bar)
        else -> BarLine.Online(state.bar.value(TheatreFeature.INPUT), state.prefs.hiddenBarInputs.size)
    }

    /** Remote & dock's live line: the dock's pins and the buttons with a long press. */
    data class RemoteLine(val pins: Int, val longPresses: Int)

    fun remoteLine(state: TuttiUiState): RemoteLine =
        RemoteLine(pins = state.dock.slots.count { it.primary != null }, longPresses = state.dock.longPress.size)

    /** Display & feedback's live line: the theme, then what is switched on. */
    data class DisplayLine(val theme: ThemeMode, val trueBlack: Boolean, val darkRoom: Boolean, val vibration: VibrationLevel, val keySounds: Boolean)

    fun displayLine(state: TuttiUiState): DisplayLine = state.prefs.let {
        DisplayLine(it.theme, it.trueBlack, it.eveningDim, it.vibrationLevel, it.keySounds)
    }
}
