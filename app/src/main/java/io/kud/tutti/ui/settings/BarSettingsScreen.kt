package io.kud.tutti.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.theatre.FeatureKind
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.ui.common.ChoiceRow
import io.kud.tutti.ui.common.SectionTitle
import io.kud.tutti.ui.common.SwitchRow
import io.kud.tutti.ui.common.featureLabel
import io.kud.tutti.ui.remote.LevelSlider
import io.kud.tutti.ui.remote.barReason
import io.kud.tutti.ui.remote.choiceLabel

/** Soundbar system settings over TCP 33336: HDMI, standby, Bluetooth, updates, device info. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarSettingsScreen(state: TuttiUiState, controller: TuttiController, onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_bar_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = { TextButton(onClick = controller::refreshBar) { Text(stringResource(R.string.bar_refresh)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionTitle(stringResource(R.string.bar_section_hdmi))
            Toggle(state, controller, TheatreFeature.HDMI_CEC)
            Choice(state, controller, TheatreFeature.HDMI_PASSTHROUGH)
            Choice(state, controller, TheatreFeature.HDMI_STANDBY_LINK)

            SectionTitle(stringResource(R.string.bar_section_system))
            Toggle(state, controller, TheatreFeature.AUTO_STANDBY)
            Toggle(state, controller, TheatreFeature.AUTO_UPDATE)

            SectionTitle(stringResource(R.string.bar_section_audio))
            Choice(state, controller, TheatreFeature.DUAL_MONO)
            Column(Modifier.padding(horizontal = 16.dp)) {
                LevelSlider(state, TheatreFeature.AV_SYNC, onLevel = { controller.setBarLevel(TheatreFeature.AV_SYNC, it) })
            }

            SectionTitle(stringResource(R.string.bar_section_bluetooth))
            Info(state, TheatreFeature.BLUETOOTH_MODE)
            Choice(state, controller, TheatreFeature.BLUETOOTH_QUALITY)

            SectionTitle(stringResource(R.string.bar_section_device))
            Info(state, TheatreFeature.MODEL_NAME)
            Info(state, TheatreFeature.FIRMWARE_VERSION)
        }
    }
}

@Composable
private fun Toggle(state: TuttiUiState, controller: TuttiController, feature: TheatreFeature) {
    SwitchRow(
        label = stringResource(featureLabel(feature)),
        checked = state.bar.isOn(feature) == true,
        onCheckedChange = { controller.setBarToggle(feature, it) },
        reason = barReason(state, feature),
    )
}

@Composable
private fun Choice(state: TuttiUiState, controller: TuttiController, feature: TheatreFeature) {
    val kind = feature.kind as? FeatureKind.Choice ?: return
    ChoiceRow(
        label = stringResource(featureLabel(feature)),
        valueLabel = optionLabel(state.bar.value(feature)),
        options = kind.options.map { it to optionLabel(it) },
        onSelect = { controller.setBarChoice(feature, it) },
        reason = barReason(state, feature),
    )
}

/** Read-only, shown raw (e.g. the undocumented Bluetooth mode vocabulary). */
@Composable
private fun Info(state: TuttiUiState, feature: TheatreFeature) {
    LinkRow(
        title = stringResource(featureLabel(feature)),
        detail = barReason(state, feature) ?: state.bar.value(feature).orEmpty(),
        enabled = false,
        onClick = {},
    )
}

@Composable
private fun optionLabel(value: String?): String = when (value) {
    "main" -> stringResource(R.string.bar_option_main)
    "sub" -> stringResource(R.string.bar_option_sub)
    "main_sub" -> stringResource(R.string.bar_option_main_sub)
    "prioritysound" -> stringResource(R.string.bar_option_priority_sound)
    "priorityconnection" -> stringResource(R.string.bar_option_priority_connection)
    else -> choiceLabel(value)
}
