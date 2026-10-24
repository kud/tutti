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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.diagnostic.AppVersion
import io.kud.tutti.ui.common.SettingsGroup
import io.kud.tutti.ui.common.SettingsRow

/**
 * Technical details (developer mode, Settings › Advanced): what tutti knows about each device and
 * link, read-only, one fact per row. The hardware address shows only as learnt or not, and the PSK
 * only as stored or not: never the values. New readouts (from the reverse map of BRAVIA Connect)
 * join as rows in these groups, or as a group of their own; the everyday screens stay untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechnicalScreen(state: TuttiUiState, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.technical_title)) },
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
            val tv = state.devices.tv
            SettingsGroup(stringResource(R.string.technical_tv)) {
                if (tv == null) {
                    Fact(R.string.technical_address, stringResource(R.string.technical_none_device))
                } else {
                    Fact(R.string.technical_address, tv.host)
                    Fact(R.string.technical_remote_link, linkLabel(state.links.tv))
                    Fact(R.string.technical_rest_link, linkLabel(state.links.rest))
                    Fact(R.string.technical_psk, stringResource(if (state.pskSet) R.string.technical_psk_stored else R.string.technical_psk_none))
                    Fact(R.string.technical_mac, stringResource(if (tv.mac != null) R.string.technical_mac_learnt else R.string.technical_mac_not_yet))
                    Fact(
                        R.string.technical_remote_start,
                        stringResource(
                            when (state.tv.settings.wol) {
                                true -> R.string.technical_on
                                false -> R.string.technical_off
                                null -> R.string.technical_unknown
                            },
                        ),
                    )
                    state.tv.settings.system?.model?.let { Fact(R.string.technical_model, it) }
                    state.tv.settings.system?.generation?.let { Fact(R.string.technical_generation, it) }
                }
            }
            val bar = state.devices.bar
            SettingsGroup(stringResource(R.string.technical_bar)) {
                if (bar == null) {
                    Fact(R.string.technical_address, stringResource(R.string.technical_none_device))
                } else {
                    Fact(R.string.technical_address, bar.host)
                    Fact(R.string.technical_bar_link, linkLabel(state.links.bar))
                    if (state.bar.refused.isNotEmpty()) {
                        Fact(R.string.technical_bar_refused, state.bar.refused.sorted().joinToString(", "))
                    }
                }
            }
            SettingsGroup(stringResource(R.string.technical_app)) {
                Fact(R.string.technical_version, AppVersion.of(context))
            }
        }
    }
}

@Composable
private fun Fact(label: Int, value: String) {
    SettingsRow(title = stringResource(label), detail = value)
}
