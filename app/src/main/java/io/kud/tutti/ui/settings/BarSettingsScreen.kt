package io.kud.tutti.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.kud.tutti.ui.common.SettingsRow
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.theatre.FeatureKind
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.ui.common.barValueLabel
import io.kud.tutti.ui.common.ChoiceRow
import io.kud.tutti.ui.common.LocalRowSurface
import io.kud.tutti.ui.common.SettingsGroup
import io.kud.tutti.ui.common.SwitchRow
import io.kud.tutti.ui.common.featureLabel
import io.kud.tutti.ui.remote.LevelSlider
import io.kud.tutti.ui.remote.barReason
import io.kud.tutti.ui.remote.choiceLabel

/** Soundbar system settings over TCP 33336: HDMI, standby, Bluetooth, updates, device info. Never the serial. */
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
            val bar = state.bar
            if (Sections.flatMap { it.second }.none(bar::supports)) {
                Text(
                    barReason(state, TheatreFeature.POWER) ?: stringResource(R.string.bar_none_reported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            // Inputs hidden from the Son sheet's row by his long press come back here (rule 4: never only behind a gesture).
            val hidden = state.prefs.hiddenBarInputs
            if (hidden.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.bar_input_hidden_title)) {
                    for (value in hidden.sorted()) {
                        val name = io.kud.tutti.ui.remote.barInputName(value, barValueLabel(TheatreFeature.INPUT, value), state.prefs.barInputNames)
                        SettingsRow(
                            title = stringResource(R.string.bar_input_show, name),
                            icon = R.drawable.ic_visibility,
                            onClick = { controller.setBarInputHidden(value, false) },
                            role = androidx.compose.ui.semantics.Role.Button,
                        )
                    }
                }
            }
            for ((title, features) in Sections) {
                val shown = features.filter(bar::supports)
                if (shown.isEmpty()) continue
                SettingsGroup(stringResource(title)) {
                    for (feature in shown) {
                        when {
                            feature == TheatreFeature.BLUETOOTH_MODE -> {
                                Info(state, feature)
                                LinkRow(
                                    title = stringResource(R.string.bar_bt_pair),
                                    detail = barReason(state, feature) ?: stringResource(R.string.bar_bt_pair_detail),
                                    enabled = bar.connected,
                                    onClick = controller::startBarPairing,
                                )
                            }
                            feature.kind is FeatureKind.Toggle -> Toggle(state, controller, feature)
                            feature.kind is FeatureKind.Choice -> Choice(state, controller, feature)
                            feature.kind is FeatureKind.Level -> Column(
                                Modifier.fillMaxWidth().background(LocalRowSurface.current).padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                LevelSlider(state, feature, onLevel = { controller.setBarLevel(feature, it) })
                            }
                            else -> Info(state, feature)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Every row is probe-gated: it shows only once the bar has reported a value for it, so a
 * setting the Bar 9 does not have stays hidden rather than greyed forever.
 */
private val Sections: List<Pair<Int, List<TheatreFeature>>> = listOf(
    R.string.bar_section_hdmi to listOf(
        TheatreFeature.HDMI_CEC,
        TheatreFeature.HDMI_ARC,
        TheatreFeature.HDMI_STANDBY_LINK,
        TheatreFeature.HDMI_PASSTHROUGH,
    ),
    R.string.bar_section_system to listOf(
        TheatreFeature.AUTO_STANDBY,
        TheatreFeature.NET_BT_STANDBY,
        TheatreFeature.AUTO_UPDATE,
    ),
    R.string.bar_section_audio to listOf(TheatreFeature.DUAL_MONO, TheatreFeature.AV_SYNC),
    R.string.bar_section_bluetooth to listOf(TheatreFeature.BLUETOOTH_MODE, TheatreFeature.BLUETOOTH_QUALITY),
    R.string.bar_section_device to listOf(TheatreFeature.MODEL, TheatreFeature.FIRMWARE_VERSION),
)

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
        refused = state.bar.refusedValues(feature),
    )
}

/** Read-only, shown raw (e.g. the undocumented Bluetooth mode vocabulary). */
@Composable
private fun Info(state: TuttiUiState, feature: TheatreFeature) {
    LinkRow(
        title = stringResource(featureLabel(feature)),
        detail = barReason(state, feature) ?: state.bar.value(feature)?.let { barValueLabel(feature, it) }.orEmpty(),
        enabled = false,
        onClick = {},
    )
}

@Composable
fun optionLabel(value: String?): String = when (value) {
    "main" -> stringResource(R.string.bar_option_main)
    "sub" -> stringResource(R.string.bar_option_sub)
    "main_sub" -> stringResource(R.string.bar_option_main_sub)
    "prioritysound" -> stringResource(R.string.bar_option_priority_sound)
    "priorityconnection" -> stringResource(R.string.bar_option_priority_connection)
    "arc" -> stringResource(R.string.bar_option_arc)
    "earc" -> stringResource(R.string.bar_option_earc)
    else -> choiceLabel(value)
}
