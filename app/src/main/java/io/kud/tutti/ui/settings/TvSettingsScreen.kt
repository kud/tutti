package io.kud.tutti.ui.settings

import androidx.compose.foundation.background
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.SettingsRow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.kud.tutti.protocol.bravia.TvSettingCatalog
import io.kud.tutti.protocol.bravia.TvSettingCatalog.PictureSection
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.ui.remote.rememberDockPinner
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiController.TvSettingGroup
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.bravia.TvSetting
import io.kud.tutti.ui.common.BusyBar
import io.kud.tutti.ui.motion.Motion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import io.kud.tutti.ui.common.ChoiceRow
import io.kud.tutti.ui.common.OpensChevron
import io.kud.tutti.ui.common.OptionsRow
import io.kud.tutti.ui.common.ProblemNote
import io.kud.tutti.ui.common.LocalRowSurface
import io.kud.tutti.ui.common.SettingsGroup
import io.kud.tutti.ui.common.SwitchRow
import io.kud.tutti.ui.onboarding.IpControlDialog
import io.kud.tutti.ui.scenes.inputName
import kotlin.math.roundToInt

/**
 * TV settings built only from what the TV lists (getSupportedApiInfo, else getMethodTypes) and
 * returns: nothing here is hard-coded, because the method set is unverified on the XR80. While
 * REST is not ONLINE every TV screen explains how to turn IP control on instead of showing dead rows.
 *
 * Settings › TV is a hub (Iris, 2026-10-26, approved by Erwann): Picture, Sound, Inputs, Power and
 * About this TV, each a row with one live line in words that opens its own screen ([TvSettingsPageScreen]).
 * The load itself starts in TuttiApp, on the way into the hub.
 */
@Composable
fun TvSettingsScreen(
    state: TuttiUiState,
    controller: TuttiController,
    onBack: () -> Unit,
    onOpenPage: (TvPage) -> Unit,
    onOpenUnlock: () -> Unit = {},
) {
    TvFrame(stringResource(R.string.settings_tv), state, controller, onBack, onOpenUnlock) {
        val rows = TvHub.rows(state.tv)
        if (rows.isNotEmpty()) SettingsGroup(null) {
            rows.forEach { page ->
                SettingsRow(
                    title = stringResource(pageTitle(page)),
                    icon = pageIcon(page),
                    detail = if (TvHub.waiting(state.tv, page)) stringResource(R.string.tv_waiting) else hubLine(page, state),
                    onClick = { onOpenPage(page) },
                    trailing = { OpensChevron() },
                )
            }
        }
        val settings = state.tv.settings
        if (rows.isEmpty() && settings.loaded && !settings.loading) NothingNote()
    }
}

/** One screen under the TV hub; Advanced picture opens from Picture's last row. */
@Composable
fun TvSettingsPageScreen(
    page: TvPage,
    state: TuttiUiState,
    controller: TuttiController,
    onBack: () -> Unit,
    onOpenPage: (TvPage) -> Unit,
    onOpenUnlock: () -> Unit = {},
) {
    TvFrame(stringResource(pageTitle(page)), state, controller, onBack, onOpenUnlock) {
        when (page) {
            TvPage.PICTURE -> PicturePage(state, controller, onOpenAdvanced = { onOpenPage(TvPage.ADVANCED_PICTURE) })
            TvPage.ADVANCED_PICTURE -> AdvancedPicturePage(state, controller)
            TvPage.SOUND -> SoundPage(state, controller)
            TvPage.INPUTS -> InputsPage(state, controller)
            TvPage.POWER -> PowerPage(state, controller)
            TvPage.ABOUT -> AboutPage(state)
        }
    }
}

/**
 * What every TV screen shares: the top bar whose arrow goes back one level, the IP control
 * explainer while REST is not ONLINE, and the load in words above the rows while it runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvFrame(
    title: String,
    state: TuttiUiState,
    controller: TuttiController,
    onBack: () -> Unit,
    onOpenUnlock: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rest = state.links.rest
    val cue = if (rest == Link.ONLINE) TvHub.loadCue(state.tv.settings) else LoadCue.NONE
    var pskDialog by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = { RecheckMark(active = cue == LoadCue.RECHECK) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            if (rest != Link.ONLINE) {
                RestExplainer(rest, note = state.linkNotes.rest, onEnterPsk = { pskDialog = true }, onOpenUnlock = onOpenUnlock)
                return@Column
            }
            // A first load says in words what it waits for; each part still on its way holds its place
            // with a waiting row, and what is already known shows as it is (Iris, 2026-10-26). A
            // re-check over settings already shown adds nothing here, so the rows never move: its only
            // mark is in the top bar ([RecheckMark]; Iris, 2026-10-27).
            if (cue == LoadCue.FIRST) LoadingNote()
            content()
        }
    }
    if (pskDialog) IpControlDialog(state, controller, onDismiss = { pskDialog = false })
}

@StringRes
private fun pageTitle(page: TvPage): Int = when (page) {
    TvPage.PICTURE -> R.string.tv_picture
    TvPage.ADVANCED_PICTURE -> R.string.tv_advanced_picture
    TvPage.SOUND -> R.string.tv_hub_sound
    TvPage.INPUTS -> R.string.tv_inputs
    TvPage.POWER -> R.string.tv_power
    TvPage.ABOUT -> R.string.tv_about
}

@DrawableRes
private fun pageIcon(page: TvPage): Int = when (page) {
    TvPage.PICTURE -> R.drawable.ic_contrast
    TvPage.ADVANCED_PICTURE -> R.drawable.ic_tune
    TvPage.SOUND -> R.drawable.ic_volume_up
    TvPage.INPUTS -> R.drawable.ic_input
    TvPage.POWER -> R.drawable.ic_power
    TvPage.ABOUT -> R.drawable.ic_info
}

/** A hub row's live line: where the part stands, in words, on one line ("Standard · brightness 32"). */
@Composable
private fun hubLine(page: TvPage, state: TuttiUiState): String {
    val settings = state.tv.settings
    val parts: List<String> = when (page) {
        TvPage.PICTURE -> TvHub.pictureLine(state.tv).let { line ->
            val mode = settings.picture.firstOrNull { it.target == "pictureMode" }
            listOfNotNull(
                line.mode?.let { tvValueName(it, mode) },
                line.brightness?.let { stringResource(R.string.tv_hub_brightness, it) },
            )
        }
        TvPage.SOUND -> TvHub.soundLine(settings).let { line ->
            val outputs = outputNames()
            listOfNotNull(
                line.output?.let { output -> output.currentValue?.let { outputs[it] ?: settingValue(output, it) } },
                line.soundMode?.let { mode -> mode.currentValue?.let { settingValue(mode, it) } },
            ).ifEmpty { listOfNotNull(stringResource(R.string.tv_output_write_only).takeIf { settings.outputWriteOnly }) }
        }
        TvPage.INPUTS -> listOfNotNull(
            TvHub.currentInput(state.tv)?.let { inputName(it.uri, state) },
            pluralStringResource(R.plurals.tv_hub_inputs, state.tv.inputs.size, state.tv.inputs.size),
        )
        TvPage.POWER -> TvHub.powerLine(settings).let { line ->
            listOfNotNull(
                line.saving?.let { stringResource(R.string.tv_hub_power_saving, powerSavingModes().toMap()[it] ?: TvSettingCatalog.readable(it)) },
                line.wol?.let { stringResource(if (it) R.string.tv_hub_wol_on else R.string.tv_hub_wol_off) },
            ).ifEmpty { listOfNotNull(stringResource(R.string.tv_sleep_timer).takeIf { settings.sleepCode != null }) }
        }
        TvPage.ABOUT -> settings.system?.let { system ->
            listOfNotNull(system.model, system.generation?.let { stringResource(R.string.tv_hub_api, it) })
        }.orEmpty()
        TvPage.ADVANCED_PICTURE -> emptyList()
    }
    return parts.joinToString(" · ")
}

@Composable
private fun PicturePage(state: TuttiUiState, controller: TuttiController, onOpenAdvanced: () -> Unit) {
    val settings = state.tv.settings
    val pin = rememberDockPinner(state, controller)::request
    var showUnavailable by rememberSaveable { mutableStateOf(false) }
    val unavailable = TvHub.unavailable(settings, TvPage.PICTURE)
    val everyday = TvSettingCatalog.everyday(settings.picture, showUnavailable || unavailable == 0)
    val advanced: @Composable () -> Unit = {
        if (TvHub.has(state.tv, TvPage.ADVANCED_PICTURE)) {
            val count = TvHub.advancedCount(settings)
            val hidden = TvHub.unavailable(settings, TvPage.ADVANCED_PICTURE)
            SettingsRow(
                title = stringResource(R.string.tv_advanced_picture),
                icon = R.drawable.ic_tune,
                detail = if (count > 0) {
                    pluralStringResource(R.plurals.tv_hub_settings, count, count)
                } else {
                    pluralStringResource(R.plurals.tv_unavailable_count, hidden, hidden)
                },
                onClick = onOpenAdvanced,
                trailing = { OpensChevron() },
            )
        }
    }
    if (TvHub.waiting(state.tv, TvPage.PICTURE)) {
        SettingsGroup(null) {
            // The picture mode rides the remote's own poll, so it is known before this load.
            state.tv.pictureMode?.let { mode ->
                SettingsRow(title = stringResource(R.string.tv_target_picture_mode), detail = tvValueName(mode, null))
            }
            WaitingRow(stringResource(R.string.tv_waiting_picture))
        }
    } else if (everyday.isNotEmpty() || settings.scene != null || TvHub.has(state.tv, TvPage.ADVANCED_PICTURE)) {
        // What people reach for, in the order they reach for it; Sony's long tail is one row away.
        SettingsGroup(null) {
            val scene: @Composable () -> Unit = { settings.scene?.let { SceneRow(it, controller) } }
            if (everyday.none { it.target == "pictureMode" }) scene()
            everyday.forEach {
                TvSettingRow(it, TvSettingGroup.PICTURE, controller, pin)
                if (it.target == "pictureMode") scene()
            }
            advanced()
        }
    } else if (settings.loaded && !settings.loading) {
        NothingNote()
    }
    UnavailableSwitch(unavailable, showUnavailable) { showUnavailable = it }
}

/** Everything past Picture's handful, on one screen: Sony's sections as headings, in its order. */
@Composable
private fun AdvancedPicturePage(state: TuttiUiState, controller: TuttiController) {
    val settings = state.tv.settings
    val pin = rememberDockPinner(state, controller)::request
    var showUnavailable by rememberSaveable { mutableStateOf(false) }
    val unavailable = TvHub.unavailable(settings, TvPage.ADVANCED_PICTURE)
    val sections = TvSettingCatalog.advanced(settings.picture, showUnavailable || unavailable == 0)
    if (TvHub.waiting(state.tv, TvPage.ADVANCED_PICTURE)) {
        SettingsGroup(null) { WaitingRow(stringResource(R.string.tv_waiting_picture)) }
    }
    for ((section, rows) in sections) {
        SettingsGroup(stringResource(TvSettingWords.section(section))) {
            rows.forEach { TvSettingRow(it, TvSettingGroup.PICTURE, controller, pin) }
        }
        if (section == PictureSection.OTHER) RawNamesNote(rows)
    }
    if (sections.isEmpty() && unavailable == 0 && settings.loaded && !settings.loading) NothingNote()
    UnavailableSwitch(unavailable, showUnavailable) { showUnavailable = it }
}

@Composable
private fun SoundPage(state: TuttiUiState, controller: TuttiController) {
    val settings = state.tv.settings
    val pin = rememberDockPinner(state, controller)::request
    var showUnavailable by rememberSaveable { mutableStateOf(false) }
    val unavailable = TvHub.unavailable(settings, TvPage.SOUND)
    val showAll = showUnavailable || unavailable == 0
    if (TvHub.waiting(state.tv, TvPage.SOUND)) {
        SettingsGroup(stringResource(R.string.tv_sound_output)) { WaitingRow(stringResource(R.string.tv_target_output_terminal)) }
    }
    // Sound and speakers get the picture's treatment (Erwann, 2026-10-26: « Mets aussi le son
    // de la barre en mots clairs »): Sony's order, words, unknown targets last and said so.
    val sound = TvSettingCatalog.arrangeSound(settings.sound, showAll)
    val speaker = TvSettingCatalog.arrangeSound(settings.speaker, showAll)
    if (sound.isNotEmpty()) {
        // "Sound output" while that is all the TV lists; "TV sound" once it lists more.
        val title = if (sound.all { it.target == TvSetting.OUTPUT_TERMINAL }) R.string.tv_sound_output else R.string.tv_section_sound
        SettingsGroup(stringResource(title)) {
            sound.forEach { TvSettingRow(it, TvSettingGroup.SOUND, controller, pin) }
        }
        RawNamesNote(sound)
    } else if (settings.sound.isEmpty() && settings.outputWriteOnly) SettingsGroup(stringResource(R.string.tv_sound_output)) {
        val outputs = outputNames()
        ChoiceRow(
            label = stringResource(R.string.tv_target_output_terminal),
            valueLabel = stringResource(R.string.tv_output_write_only),
            options = TvSetting.OUTPUT_TERMINALS.map { it to (outputs[it] ?: it) },
            onSelect = controller::setOutputTerminal,
            onPin = tvPin(TvSetting.OUTPUT_TERMINAL, pin),
            canPin = ::dockKnowsOutput,
        )
    }
    if (speaker.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.tv_speaker)) {
            speaker.forEach { TvSettingRow(it, TvSettingGroup.SPEAKER, controller, pin) }
        }
        RawNamesNote(speaker)
    }
    if (!TvHub.has(state.tv, TvPage.SOUND) && settings.loaded && !settings.loading) NothingNote()
    UnavailableSwitch(unavailable, showUnavailable) { showUnavailable = it }
}

@Composable
private fun InputsPage(state: TuttiUiState, controller: TuttiController) {
    val settings = state.tv.settings
    if (TvHub.waiting(state.tv, TvPage.INPUTS)) {
        SettingsGroup(null) { WaitingRow(stringResource(R.string.tv_inputs)) }
    } else if (state.tv.inputs.isNotEmpty()) {
        SettingsGroup(null) {
            for (input in state.tv.inputs) {
                val current = input.uri == state.tv.input
                LinkRow(
                    title = inputName(input.uri, state),
                    detail = stringResource(
                        when {
                            current -> R.string.tv_input_current
                            input.connected -> R.string.tv_input_connected
                            else -> R.string.tv_input_disconnected
                        },
                    ),
                    enabled = !current,
                    onClick = { controller.switchInput(input.uri) },
                )
            }
        }
    } else if (settings.loaded && !settings.loading) {
        NothingNote()
    }
}

@Composable
private fun PowerPage(state: TuttiUiState, controller: TuttiController) {
    val settings = state.tv.settings
    if (TvHub.waiting(state.tv, TvPage.POWER)) {
        SettingsGroup(null) { WaitingRow(stringResource(R.string.tv_waiting_power)) }
    } else if (TvHub.has(state.tv, TvPage.POWER)) {
        SettingsGroup(null) {
            if (settings.powerSavingSupported) {
                val modes = powerSavingModes()
                OptionsRow(
                    label = stringResource(R.string.tv_power_saving),
                    selected = settings.powerSaving,
                    valueLabel = modes.firstOrNull { it.first == settings.powerSaving }?.second ?: settings.powerSaving ?: "—",
                    options = modes,
                    onSelect = controller::setPowerSaving,
                )
            }
            settings.wol?.let { wol ->
                SwitchRow(
                    label = stringResource(R.string.tv_wol),
                    supporting = stringResource(R.string.tv_wol_detail),
                    checked = wol,
                    onCheckedChange = controller::setWol,
                )
            }
            if (settings.sleepCode != null) {
                LinkRow(
                    title = stringResource(R.string.tv_sleep_timer),
                    detail = stringResource(R.string.tv_sleep_timer_detail),
                    enabled = true,
                    onClick = controller::pressSleepTimer,
                )
            }
        }
    } else if (settings.loaded && !settings.loading) {
        NothingNote()
    }
}

@Composable
private fun AboutPage(state: TuttiUiState) {
    val settings = state.tv.settings
    val rows = settings.system?.let { system ->
        listOfNotNull(
            system.model?.let { stringResource(R.string.tv_device_model) to it },
            system.generation?.let { stringResource(R.string.tv_device_generation) to it },
        )
    }.orEmpty()
    if (TvHub.waiting(state.tv, TvPage.ABOUT)) {
        SettingsGroup(null) { WaitingRow(stringResource(R.string.tv_device_model)) }
    } else if (rows.isNotEmpty()) {
        SettingsGroup(null) {
            rows.forEach { (title, value) -> LinkRow(title = title, detail = value, enabled = false, onClick = {}) }
        }
    } else if (settings.loaded && !settings.loading) {
        NothingNote()
    }
}

/** Power saving's four modes in words, by the value the TV takes. */
@Composable
internal fun powerSavingModes(): List<Pair<String, String>> = listOf(
    "off" to stringResource(R.string.tv_power_saving_off),
    "low" to stringResource(R.string.tv_power_saving_low),
    "high" to stringResource(R.string.tv_power_saving_high),
    "pictureOff" to stringResource(R.string.tv_power_saving_picture_off),
)

/**
 * The foot of a screen whose rows the TV can make unavailable (Picture, Advanced picture, Sound):
 * hidden by default, shown in place on request, the count in words. Absent when none is.
 */
@Composable
private fun UnavailableSwitch(count: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    if (count == 0) return
    SettingsGroup(null, Modifier.padding(top = 16.dp)) {
        SwitchRow(
            label = stringResource(R.string.tv_show_unavailable),
            supporting = pluralStringResource(R.plurals.tv_unavailable_count, count, count),
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun NothingNote() {
    Text(
        stringResource(R.string.tv_nothing_listed),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun RestExplainer(rest: Link, note: String?, onEnterPsk: () -> Unit, onOpenUnlock: () -> Unit) {
    if (rest == Link.CONNECTING) BusyBar(Modifier.fillMaxWidth())
    val (title, body) = when (rest) {
        Link.CONNECTING -> R.string.tv_rest_connecting to null
        Link.UNAUTHORIZED -> R.string.tv_rest_unauthorized_title to R.string.tv_rest_unauthorized_body
        else -> R.string.tv_rest_offline_title to R.string.tv_rest_offline_body
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        if (body != null) {
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = onEnterPsk, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.tv_rest_enter_psk))
            }
            androidx.compose.material3.TextButton(onClick = onOpenUnlock, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.unlock_tv_settings_more))
            }
        }
        if (rest != Link.CONNECTING) ProblemNote(message = null, detail = note, modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * A first load in words, under a progress bar: what is being asked and that what is shown is
 * already the TV's. The bar alone was a thin line Erwann read past (2026-10-26); the words carry the
 * state, so it never rests on motion or colour.
 */
@Composable
private fun LoadingNote() {
    BusyBar(Modifier.fillMaxWidth())
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(start = 32.dp, end = 32.dp, top = 12.dp),
    ) {
        Text(stringResource(R.string.tv_loading_first), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.tv_loading_known),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * A re-check over settings already on screen (Iris, 2026-10-27, after Erwann's « pas très fan du
 * loading » on dev.27): what is shown is valid, so nothing covers, dims or pushes it. Under
 * [TvHub.RECHECK_QUIET_MS] nothing shows at all; past it, a small spinner where the top bar's
 * actions sit (the refresh glyph, still, under reduced motion), faded in and out by opacity alone,
 * named to TalkBack. Values that change simply update in their rows.
 */
@Composable
private fun RecheckMark(active: Boolean) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            delay(TvHub.RECHECK_QUIET_MS)
            shown = true
        } else {
            shown = false
        }
    }
    val label = stringResource(R.string.tv_loading_again)
    AnimatedVisibility(visible = shown, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
        Box(
            Modifier.size(48.dp).semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (LocalReducedMotion.current) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * A part of the screen the TV hasn't answered yet, in the place it will take: its name and
 * "Waiting for the TV…" in words, with a small spinner (a still glyph under reduced motion).
 */
@Composable
private fun WaitingRow(title: String) {
    SettingsRow(
        title = title,
        detail = stringResource(R.string.tv_waiting),
        trailing = {
            if (LocalReducedMotion.current) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** The TV's sound outputs in words, by their `outputTerminal` value (scenes say them too). */
@Composable
internal fun outputNames(): Map<String, String> = mapOf(
    "speaker" to stringResource(R.string.tv_output_speaker),
    "speaker_hdmi" to stringResource(R.string.tv_output_speaker_hdmi),
    "hdmi" to stringResource(R.string.tv_output_hdmi),
    "audioSystem" to stringResource(R.string.tv_output_audio_system),
)

/** Under a group holding targets tutti has no words for: they carry the TV's own names, on purpose. */
@Composable
private fun RawNamesNote(rows: List<TvSetting>) {
    if (rows.all { TvSettingCatalog.isNamed(it.target) }) return
    Text(
        stringResource(R.string.tv_section_other_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
    )
}

@Composable
private fun SceneRow(scene: TvSetting, controller: TuttiController) {
    val names = mapOf(
        "auto" to stringResource(R.string.tv_scene_auto),
        "auto24pSync" to stringResource(R.string.tv_scene_auto_24p),
        "general" to stringResource(R.string.tv_scene_general),
    )
    OptionsRow(
        label = stringResource(R.string.tv_target_scene),
        selected = scene.currentValue,
        valueLabel = scene.currentValue?.let { names[it] ?: it } ?: "—",
        options = scene.candidates.map { it.value to (names[it.value] ?: it.value) },
        onSelect = controller::setScene,
    )
}

@Composable
private fun TvSettingRow(setting: TvSetting, group: TvSettingGroup, controller: TuttiController, pin: (DockAction, String) -> Unit) {
    val label = settingTitle(setting)
    val reason = if (!setting.isAvailable) stringResource(R.string.tv_setting_unavailable) else null
    if (setting.isRange) {
        RangeRow(setting, label, reason) { controller.setTvSetting(group, setting.target, it) }
    } else {
        val outputs = if (setting.target == TvSetting.OUTPUT_TERMINAL) outputNames() else emptyMap()
        val words = setting.candidates.associate { it.value to (outputs[it.value] ?: settingValue(setting, it.value)) }
        val current = setting.currentValue
        // Segments when the options are few and short enough to fit, else the menu (OptionsRow).
        OptionsRow(
            label = label,
            selected = current,
            valueLabel = current?.let { words[it] ?: outputs[it] ?: settingValue(setting, it) } ?: "—",
            options = setting.candidates.filter { it.isAvailable }.map { it.value to words.getValue(it.value) },
            onSelect = { controller.setTvSetting(group, setting.target, it) },
            reason = reason,
            onPin = tvPin(setting.target, pin),
            canPin = { setting.target != TvSetting.OUTPUT_TERMINAL || dockKnowsOutput(it) },
        )
    }
}

/**
 * A numeric setting. The title has the row to itself: the value sits beside it only while the
 * setting can change, and the reason it can't goes on its own line, so a long title never meets
 * it. The range's two ends sit under the slider, so a thumb at an end says where it is in numbers:
 * Sony's gains top out at 0, which otherwise reads as a value stuck at the wrong end.
 */
@Composable
private fun RangeRow(setting: TvSetting, label: String, reason: String?, onSet: (String) -> Unit) {
    val candidate = setting.candidates[0]
    val min = candidate.min ?: 0.0
    val max = candidate.max ?: 100.0
    val step = candidate.step?.takeIf { it > 0 } ?: 1.0
    val current = setting.currentValue?.toDoubleOrNull() ?: min
    var dragging by remember(setting.target) { mutableStateOf<Float?>(null) }
    val shown = dragging ?: current.toFloat()
    val ticks = ((max - min) / step).roundToInt() - 1
    val signed = min < 0
    val valueText = format(shown.toDouble(), step, signed)
    Column(
        Modifier
            .fillMaxWidth()
            .background(LocalRowSurface.current)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .alpha(if (reason == null) 1f else 0.6f),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (reason == null) Text(valueText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
        }
        if (reason != null) {
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { value ->
                    val snapped = min + ((value - min) / step).roundToInt() * step
                    onSet(format(snapped, step, signed = false))
                }
                dragging = null
            },
            valueRange = min.toFloat()..max.toFloat(),
            steps = if (ticks in 1..100) ticks else 0,
            enabled = reason == null,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = if (reason == null) valueText else "$valueText, $reason"
            },
        )
        Row(Modifier.fillMaxWidth()) {
            val ends = MaterialTheme.typography.labelSmall
            Text(format(min, step, signed), style = ends, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(format(max, step, signed), style = ends, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The dock pin for a value of [target], when the dock has one: a picture mode, or a sound output
 * it knows ([DockAction.TvSoundOutput] takes only the two terminals tutti can name). Null otherwise.
 */
private fun tvPin(target: String, pin: (DockAction, String) -> Unit): ((String, String) -> Unit)? = when (target) {
    "pictureMode" -> { value, title -> pin(DockAction.PictureMode(value, title), title) }
    TvSetting.OUTPUT_TERMINAL -> { value, title -> pin(DockAction.TvSoundOutput(value), title) }
    else -> null
}

private fun dockKnowsOutput(value: String): Boolean = value == TvSetting.TERMINAL_AUDIO_SYSTEM || value == TvSetting.TERMINAL_SPEAKER

/** The value as the TV takes it; [signed] adds a + to positives, for ranges that run either side of 0. */
private fun format(value: Double, step: Double, signed: Boolean): String {
    val text = if (step % 1.0 == 0.0) value.roundToInt().toString() else "%.1f".format(java.util.Locale.ROOT, value)
    return if (signed && value > 0) "+$text" else text
}
