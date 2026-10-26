package io.kud.tutti.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import io.kud.tutti.controller.TvSettingsPart
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
import io.kud.tutti.ui.common.ChoiceRow
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
 * REST is not ONLINE the screen explains how to turn IP control on instead of showing dead rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvSettingsScreen(state: TuttiUiState, controller: TuttiController, onBack: () -> Unit, onOpenUnlock: () -> Unit = {}) {
    val rest = state.links.rest
    LaunchedEffect(rest) { if (rest == Link.ONLINE) controller.loadTvSettings() }
    var pskDialog by remember { mutableStateOf(false) }
    var showUnavailable by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val settings = state.tv.settings
    // Picture modes and sound outputs pin to the dock from their own menu (design.md, gestures).
    // The rows only exist while REST is online, so no long press here can meet a down link.
    val pinner = rememberDockPinner(state, controller)
    val pin: (DockAction, String) -> Unit = pinner::request
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_tv_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            if (rest != Link.ONLINE) {
                RestExplainer(rest, note = state.linkNotes.rest, onEnterPsk = { pskDialog = true }, onOpenUnlock = onOpenUnlock)
                return@Column
            }
            // While IP control answers, a line in words says so; each part still on its way holds its
            // place with a waiting row, and what is already known shows as it is (Iris, 2026-10-26).
            val firstLoad = !settings.loaded
            val waitingFor = { part: TvSettingsPart -> settings.loading && firstLoad && part in settings.waiting }
            if (settings.loading) LoadingNote(firstLoad)

            // The everyday handful first, the rest folded behind Advanced in Sony's order (Erwann,
            // 2026-10-26: "Put the expert settings behind Advanced"). Rows the TV can't change now are
            // hidden until asked for, then shown in place.
            val unavailable = (settings.picture + settings.sound + settings.speaker).count { !it.isAvailable }
            val showAll = showUnavailable || unavailable == 0
            val everyday = TvSettingCatalog.everyday(settings.picture, showAll)
            if (waitingFor(TvSettingsPart.PICTURE)) {
                SettingsGroup(stringResource(R.string.tv_picture)) {
                    // The picture mode rides the remote's own poll, so it is known before this load.
                    state.tv.pictureMode?.let { mode ->
                        SettingsRow(title = stringResource(R.string.tv_target_picture_mode), detail = tvValueName(mode, null))
                    }
                    WaitingRow(stringResource(R.string.tv_waiting_picture))
                }
            } else if (everyday.isNotEmpty() || settings.scene != null) {
                SettingsGroup(stringResource(R.string.tv_picture)) {
                    val scene: @Composable () -> Unit = { settings.scene?.let { SceneRow(it, controller) } }
                    if (everyday.none { it.target == "pictureMode" }) scene()
                    everyday.forEach {
                        TvSettingRow(it, TvSettingGroup.PICTURE, controller, pin)
                        if (it.target == "pictureMode") scene()
                    }
                }
            }
            val advanced = TvSettingCatalog.advanced(settings.picture, showAll)
            if (advanced.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.tv_advanced)) {
                    AdvancedToggle(
                        unfolded = showAdvanced,
                        count = advanced.sumOf { it.second.size },
                        sections = advanced.map { stringResource(TvSettingWords.section(it.first)) },
                        onToggle = { showAdvanced = !showAdvanced },
                    )
                }
                if (showAdvanced) {
                    for ((section, rows) in advanced) {
                        SettingsGroup(stringResource(TvSettingWords.section(section))) {
                            rows.forEach { TvSettingRow(it, TvSettingGroup.PICTURE, controller, pin) }
                        }
                        if (section == PictureSection.OTHER) RawNamesNote(rows)
                    }
                }
            }
            if (waitingFor(TvSettingsPart.SOUND)) {
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
            if (speaker.isNotEmpty()) SettingsGroup(stringResource(R.string.tv_speaker)) {
                speaker.forEach { TvSettingRow(it, TvSettingGroup.SPEAKER, controller, pin) }
            }
            if (speaker.isNotEmpty()) RawNamesNote(speaker)
            if (unavailable > 0) SettingsGroup(null, Modifier.padding(top = 16.dp)) {
                SwitchRow(
                    label = stringResource(R.string.tv_show_unavailable),
                    supporting = pluralStringResource(R.plurals.tv_unavailable_count, unavailable, unavailable),
                    checked = showUnavailable,
                    onCheckedChange = { showUnavailable = it },
                )
            }
            if (state.tv.inputs.isNotEmpty()) SettingsGroup(stringResource(R.string.tv_inputs)) {
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
            if (waitingFor(TvSettingsPart.POWER)) {
                SettingsGroup(stringResource(R.string.tv_power)) { WaitingRow(stringResource(R.string.tv_waiting_power)) }
            } else if (settings.powerSavingSupported || settings.wol != null || settings.sleepCode != null) SettingsGroup(stringResource(R.string.tv_power)) {
                if (settings.powerSavingSupported) {
                    val modes = listOf(
                        "off" to stringResource(R.string.tv_power_saving_off),
                        "low" to stringResource(R.string.tv_power_saving_low),
                        "high" to stringResource(R.string.tv_power_saving_high),
                        "pictureOff" to stringResource(R.string.tv_power_saving_picture_off),
                    )
                    ChoiceRow(
                        label = stringResource(R.string.tv_power_saving),
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
            settings.system?.let { system ->
                val rows = listOfNotNull(
                    system.model?.let { stringResource(R.string.tv_device_model) to it },
                    system.generation?.let { stringResource(R.string.tv_device_generation) to it },
                )
                if (rows.isNotEmpty()) SettingsGroup(stringResource(R.string.tv_device)) {
                    rows.forEach { (title, value) -> LinkRow(title = title, detail = value, enabled = false, onClick = {}) }
                }
            }
            val nothing = settings.picture.isEmpty() && settings.scene == null && settings.sound.isEmpty() &&
                !settings.outputWriteOnly && settings.speaker.isEmpty() && !settings.powerSavingSupported && settings.wol == null
            if (!settings.loading && nothing) {
                Text(
                    stringResource(R.string.tv_nothing_listed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
    if (pskDialog) IpControlDialog(state, controller, onDismiss = { pskDialog = false })
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
 * The load in words, under a progress bar: what is being asked and that what is shown is already
 * the TV's. The bar alone was a thin line Erwann read past (2026-10-26); the words carry the state,
 * so it never rests on motion or colour.
 */
@Composable
private fun LoadingNote(firstLoad: Boolean) {
    BusyBar(Modifier.fillMaxWidth())
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(start = 32.dp, end = 32.dp, top = 12.dp),
    ) {
        Text(stringResource(if (firstLoad) R.string.tv_loading_first else R.string.tv_loading_again), style = MaterialTheme.typography.bodyMedium)
        if (firstLoad) {
            Text(
                stringResource(R.string.tv_loading_known),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
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

/**
 * The Advanced fold's one row. Its title says what a tap does ("Show 48 more settings", "Hide
 * advanced settings"), its line names the sections inside, and the chevron turns down when open:
 * the state is in words first, and TalkBack hears "Folded" or "Unfolded".
 */
@Composable
private fun AdvancedToggle(unfolded: Boolean, count: Int, sections: List<String>, onToggle: () -> Unit) {
    val state = stringResource(if (unfolded) R.string.tv_advanced_unfolded else R.string.tv_advanced_folded)
    SettingsRow(
        title = if (unfolded) stringResource(R.string.tv_advanced_hide) else pluralStringResource(R.plurals.tv_advanced_show, count, count),
        detail = sections.joinToString(", "),
        onClick = onToggle,
        role = Role.Button,
        modifier = Modifier.semantics { stateDescription = state },
        trailing = {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (unfolded) 90f else 0f),
            )
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
    ChoiceRow(
        label = stringResource(R.string.tv_target_scene),
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
        ChoiceRow(
            label = label,
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
