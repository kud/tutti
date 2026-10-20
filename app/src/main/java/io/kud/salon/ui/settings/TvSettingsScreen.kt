package io.kud.salon.ui.settings

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
import io.kud.salon.R
import io.kud.salon.controller.SalonController
import io.kud.salon.controller.SalonController.TvSettingGroup
import io.kud.salon.controller.SalonUiState
import io.kud.salon.protocol.bravia.BraviaService
import io.kud.salon.protocol.bravia.TvSetting
import io.kud.salon.ui.common.ChoiceRow
import io.kud.salon.ui.common.SectionTitle
import io.kud.salon.ui.scenes.inputName
import kotlin.math.roundToInt

/**
 * TV settings built only from what the TV lists (getMethodTypes) and returns: nothing here is
 * hard-coded, because the method set is unverified on the XR80.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvSettingsScreen(state: SalonUiState, controller: SalonController, onBack: () -> Unit) {
    LaunchedEffect(Unit) { controller.loadTvSettings() }
    val settings = state.tv.settings
    val caps = state.tv.capabilities
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
            if (settings.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (settings.picture.isNotEmpty()) {
                SectionTitle(stringResource(R.string.tv_picture))
                settings.picture.forEach { TvSettingRow(it, TvSettingGroup.PICTURE, controller) }
            }
            if (settings.sound.isNotEmpty()) {
                SectionTitle(stringResource(R.string.tv_sound_output))
                settings.sound.forEach { TvSettingRow(it, TvSettingGroup.SOUND, controller) }
            }
            if (settings.speaker.isNotEmpty()) {
                SectionTitle(stringResource(R.string.tv_speaker))
                settings.speaker.forEach { TvSettingRow(it, TvSettingGroup.SPEAKER, controller) }
            }
            if (state.tv.inputs.isNotEmpty()) {
                SectionTitle(stringResource(R.string.tv_inputs))
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
            if (caps.supports(BraviaService.SYSTEM, "setPowerSavingMode")) {
                SectionTitle(stringResource(R.string.tv_power_saving))
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
            if (!settings.loading && settings.picture.isEmpty() && settings.sound.isEmpty() && settings.speaker.isEmpty()) {
                Text(
                    stringResource(R.string.tv_nothing_listed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun TvSettingRow(setting: TvSetting, group: TvSettingGroup, controller: SalonController) {
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
        Column(Modifier.fillMaxWidth().alpha(if (reason == null) 1f else 0.6f).padding(horizontal = 16.dp, vertical = 8.dp)) {
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
        else -> null
    }
    return known?.let { stringResource(it) } ?: setting.title ?: setting.target
}
