package io.kud.tutti.ui.settings

import io.kud.tutti.controller.TvSettingsPart
import io.kud.tutti.controller.TvSettingsState
import io.kud.tutti.controller.TvState
import io.kud.tutti.protocol.bravia.ExternalInput
import io.kud.tutti.protocol.bravia.TvSetting
import io.kud.tutti.protocol.bravia.TvSettingCatalog

/** The screens under Settings › TV: the hub's five parts, and Advanced picture under Picture. */
enum class TvPage { PICTURE, ADVANCED_PICTURE, SOUND, INPUTS, POWER, ABOUT }

/** What a load shows: nothing, the first load in words, or a quiet re-check ([TvHub.loadCue]). */
enum class LoadCue { NONE, FIRST, RECHECK }

/**
 * What the TV hub and its screens show, kept apart from Compose so it can be tested (Iris,
 * 2026-10-26, docs/proposals/settings-structure.md): which parts have a row, which are still on
 * their way, each row's live line as values, and how many settings each screen hides as unavailable.
 */
internal object TvHub {

    /** The hub's parts, in Sony's order: Picture, Sound, Inputs, Power, About this TV. */
    val parts: List<TvPage> = listOf(TvPage.PICTURE, TvPage.SOUND, TvPage.INPUTS, TvPage.POWER, TvPage.ABOUT)

    /**
     * How a load shows on the TV screens (Iris, 2026-10-27, after Erwann's « pas très fan du loading »
     * on a re-check): a first load says in words what it waits for ([LoadCue.FIRST]); a re-check over
     * settings already shown is quiet ([LoadCue.RECHECK]): no bar, no sentence, nothing that moves the
     * rows, only a small mark in the top bar once it has run [RECHECK_QUIET_MS].
     */
    fun loadCue(settings: TvSettingsState): LoadCue = when {
        !settings.loading -> LoadCue.NONE
        settings.loaded -> LoadCue.RECHECK
        else -> LoadCue.FIRST
    }

    /** A re-check quicker than this shows nothing at all, so a fast one never flickers. */
    const val RECHECK_QUIET_MS = 1_000L

    /**
     * Whether [page] is still on its way on a first load, so its row says "Waiting for the TV…".
     * Inputs and the TV's model land with the last part; inputs the remote already knows don't wait.
     */
    fun waiting(tv: TvState, page: TvPage): Boolean {
        val settings = tv.settings
        if (!settings.loading || settings.loaded) return false
        return when (page) {
            TvPage.PICTURE, TvPage.ADVANCED_PICTURE -> TvSettingsPart.PICTURE in settings.waiting
            TvPage.SOUND -> TvSettingsPart.SOUND in settings.waiting
            TvPage.POWER -> TvSettingsPart.POWER in settings.waiting
            TvPage.INPUTS -> TvSettingsPart.POWER in settings.waiting && tv.inputs.isEmpty()
            TvPage.ABOUT -> TvSettingsPart.POWER in settings.waiting && settings.system == null
        }
    }

    /** Whether [page] has anything to show once the TV has answered. */
    fun has(tv: TvState, page: TvPage): Boolean {
        val s = tv.settings
        return when (page) {
            TvPage.PICTURE -> s.picture.isNotEmpty() || s.scene != null
            TvPage.ADVANCED_PICTURE -> s.picture.any { it.target !in TvSettingCatalog.everydayTargets }
            TvPage.SOUND -> s.sound.isNotEmpty() || s.speaker.isNotEmpty() || s.outputWriteOnly
            TvPage.INPUTS -> tv.inputs.isNotEmpty()
            TvPage.POWER -> s.powerSavingSupported || s.wol != null || s.sleepCode != null
            TvPage.ABOUT -> s.system?.let { it.model != null || it.generation != null } == true
        }
    }

    /** The hub's rows: every part still on its way or with something to show, in [parts] order. */
    fun rows(tv: TvState): List<TvPage> = parts.filter { waiting(tv, it) || has(tv, it) }

    /** The settings [page] hides while "Show unavailable settings" is off; 0 on screens without the switch. */
    fun unavailable(settings: TvSettingsState, page: TvPage): Int = when (page) {
        TvPage.PICTURE -> TvSettingCatalog.everyday(settings.picture, showUnavailable = true).count { !it.isAvailable }
        TvPage.ADVANCED_PICTURE -> settings.picture.count { it.target !in TvSettingCatalog.everydayTargets && !it.isAvailable }
        TvPage.SOUND -> (settings.sound + settings.speaker).count { !it.isAvailable }
        else -> 0
    }

    /** How many settings Advanced picture shows as it opens, unavailable ones hidden. */
    fun advancedCount(settings: TvSettingsState): Int =
        TvSettingCatalog.advanced(settings.picture, showUnavailable = false).sumOf { it.second.size }

    /** Picture's live line: the mode (the remote's poll knows it before the load) and the brightness, when the TV gives them. */
    data class PictureLine(val mode: String?, val brightness: String?)

    fun pictureLine(tv: TvState): PictureLine {
        val picture = tv.settings.picture
        val mode = picture.firstOrNull { it.target == "pictureMode" }?.currentValue ?: tv.pictureMode
        val brightness = picture.firstOrNull { it.target == "brightness" && it.isAvailable }?.currentValue
        return PictureLine(mode, brightness)
    }

    /** Sound's live line: the output and the sound mode, each when the TV reports it. */
    data class SoundLine(val output: TvSetting?, val soundMode: TvSetting?)

    fun soundLine(settings: TvSettingsState): SoundLine = SoundLine(
        output = settings.sound.firstOrNull { it.target == TvSetting.OUTPUT_TERMINAL && it.currentValue != null },
        soundMode = settings.sound.firstOrNull { it.target == "soundMode" && it.currentValue != null },
    )

    /** The input on screen, when the TV says which one it is. */
    fun currentInput(tv: TvState): ExternalInput? = tv.inputs.firstOrNull { it.uri == tv.input }

    /** Power's live line: power saving's value and Wake-on-LAN, each when the TV lists it. */
    data class PowerLine(val saving: String?, val wol: Boolean?)

    fun powerLine(settings: TvSettingsState): PowerLine =
        PowerLine(saving = settings.powerSaving.takeIf { settings.powerSavingSupported }, wol = settings.wol)
}
