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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiController.TvSettingGroup
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.bravia.TvSetting
import io.kud.tutti.ui.common.ChoiceRow
import io.kud.tutti.ui.common.ProblemNote
import io.kud.tutti.ui.common.LocalRowSurface
import io.kud.tutti.ui.common.SettingsGroup
import io.kud.tutti.ui.common.SwitchRow
import io.kud.tutti.ui.onboarding.PskDialog
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
    val settings = state.tv.settings
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
            if (settings.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (settings.picture.isNotEmpty() || settings.scene != null) SettingsGroup(stringResource(R.string.tv_picture)) {
                settings.picture.forEach { TvSettingRow(it, TvSettingGroup.PICTURE, controller) }
                settings.scene?.let { scene ->
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
            }
            if (settings.sound.isNotEmpty()) SettingsGroup(stringResource(R.string.tv_sound_output)) {
                settings.sound.forEach { TvSettingRow(it, TvSettingGroup.SOUND, controller) }
            } else if (settings.outputWriteOnly) SettingsGroup(stringResource(R.string.tv_sound_output)) {
                val outputs = outputNames()
                ChoiceRow(
                    label = stringResource(R.string.tv_target_output_terminal),
                    valueLabel = stringResource(R.string.tv_output_write_only),
                    options = TvSetting.OUTPUT_TERMINALS.map { it to (outputs[it] ?: it) },
                    onSelect = controller::setOutputTerminal,
                )
            }
            if (settings.speaker.isNotEmpty()) SettingsGroup(stringResource(R.string.tv_speaker)) {
                settings.speaker.forEach { TvSettingRow(it, TvSettingGroup.SPEAKER, controller) }
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
            if (settings.powerSavingSupported || settings.wol != null || settings.sleepCode != null) SettingsGroup(stringResource(R.string.tv_power)) {
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
    if (pskDialog) {
        PskDialog(onSave = {
            controller.setPsk(it)
            pskDialog = false
        }, onDismiss = { pskDialog = false })
    }
}

@Composable
private fun RestExplainer(rest: Link, note: String?, onEnterPsk: () -> Unit, onOpenUnlock: () -> Unit) {
    if (rest == Link.CONNECTING) LinearProgressIndicator(Modifier.fillMaxWidth())
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

@Composable
private fun outputNames(): Map<String, String> = mapOf(
    "speaker" to stringResource(R.string.tv_output_speaker),
    "speaker_hdmi" to stringResource(R.string.tv_output_speaker_hdmi),
    "hdmi" to stringResource(R.string.tv_output_hdmi),
    "audioSystem" to stringResource(R.string.tv_output_audio_system),
)

@Composable
private fun TvSettingRow(setting: TvSetting, group: TvSettingGroup, controller: TuttiController) {
    val label = settingTitle(setting)
    val reason = if (!setting.isAvailable) stringResource(R.string.tv_setting_unavailable) else null
    if (setting.isRange) {
        val candidate = setting.candidates[0]
        val min = candidate.min ?: 0.0
        val max = candidate.max ?: 100.0
        val step = candidate.step?.takeIf { it > 0 } ?: 1.0
        val current = setting.currentValue?.toDoubleOrNull() ?: min
        var dragging by remember(setting.target) { mutableStateOf<Float?>(null) }
        val shown = dragging ?: current.toFloat()
        val ticks = ((max - min) / step).roundToInt() - 1
        Column(
            Modifier
                .fillMaxWidth()
                .background(LocalRowSurface.current)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(if (reason == null) 1f else 0.6f),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(reason ?: format(shown.toDouble(), step), style = MaterialTheme.typography.bodyMedium)
            }
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { value ->
                        val snapped = min + ((value - min) / step).roundToInt() * step
                        controller.setTvSetting(group, setting.target, format(snapped, step))
                    }
                    dragging = null
                },
                valueRange = min.toFloat()..max.toFloat(),
                steps = if (ticks in 1..100) ticks else 0,
                enabled = reason == null,
            )
        }
    } else {
        val options = setting.candidates.filter { it.isAvailable }.map { it.value to (it.title ?: it.value) }
        ChoiceRow(
            label = label,
            valueLabel = setting.candidates.firstOrNull { it.value == setting.currentValue }?.title ?: setting.currentValue ?: "—",
            options = options,
            onSelect = { controller.setTvSetting(group, setting.target, it) },
            reason = reason,
        )
    }
}

private fun format(value: Double, step: Double): String =
    if (step % 1.0 == 0.0) value.roundToInt().toString() else "%.1f".format(java.util.Locale.ROOT, value)

/** Known Sony targets in plain words; anything else shows the TV's own title or its raw name. */
@Composable
private fun settingTitle(setting: TvSetting): String {
    val known = when (setting.target) {
        "pictureMode" -> R.string.tv_target_picture_mode
        "brightness" -> R.string.tv_target_brightness
        "contrast" -> R.string.tv_target_contrast
        "color" -> R.string.tv_target_color
        "sharpness" -> R.string.tv_target_sharpness
        "colorTemperature" -> R.string.tv_target_color_temperature
        "lightSensor" -> R.string.tv_target_light_sensor
        "autoPictureMode" -> R.string.tv_target_auto_picture_mode
        "hdrMode" -> R.string.tv_target_hdr_mode
        "outputTerminal" -> R.string.tv_target_output_terminal
        "tvPosition" -> R.string.tv_target_tv_position
        "subwooferLevel" -> R.string.tv_target_subwoofer_level
        "subwooferFreq" -> R.string.tv_target_subwoofer_freq
        "subwooferPhase" -> R.string.tv_target_subwoofer_phase
        "subwooferPower" -> R.string.tv_target_subwoofer_power
        "colorSpace" -> R.string.tv_target_color_space
        "autoLocalDimming" -> R.string.tv_target_auto_local_dimming
        "xtendedDynamicRange" -> R.string.tv_target_xdr
        else -> null
    }
    return known?.let { stringResource(it) } ?: setting.title ?: setting.target
}
