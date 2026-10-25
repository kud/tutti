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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.Fetch
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.controller.Link
import io.kud.tutti.protocol.theatre.BarHttpKey
import io.kud.tutti.protocol.theatre.BarHttpValue
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.platform.diagnostic.AppVersion
import io.kud.tutti.ui.common.SettingsGroup
import io.kud.tutti.ui.common.SettingsRow
import io.kud.tutti.ui.common.featureLabel

/**
 * Technical details (developer mode, Settings › Advanced): what tutti knows about each device and
 * link, read-only, one fact per row. The hardware address shows only as learnt or not, and the PSK
 * only as stored or not: never the values. New readouts (from the reverse map of BRAVIA Connect)
 * join as rows in these groups, or as a group of their own; the everyday screens stay untouched.
 *
 * Opening it asks [onOpen] for the on-demand readouts (bar detail keys on 33336, the bar's
 * HTTP 54545, the TV's supported functions). Every readout row says its value and where it came
 * from, or in words why there is none: never a state shown by colour alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechnicalScreen(state: TuttiUiState, onOpen: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { onOpen() }
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
                    Fact(
                        R.string.technical_ip_code,
                        stringResource(
                            when (state.ipCode) {
                                io.kud.tutti.controller.IpCodeStatus.SET -> R.string.technical_ip_code_stored
                                io.kud.tutti.controller.IpCodeStatus.RENEW -> R.string.technical_ip_code_renew
                                io.kud.tutti.controller.IpCodeStatus.NONE -> R.string.technical_ip_code_none
                            },
                        ),
                    )
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
                    Fact(R.string.technical_supported_functions, supportedFunctions(state))
                }
            }
            val bar = state.devices.bar
            SettingsGroup(stringResource(R.string.technical_bar)) {
                if (bar == null) {
                    Fact(R.string.technical_address, stringResource(R.string.technical_none_device))
                } else {
                    Fact(R.string.technical_address, bar.host)
                    Fact(R.string.technical_bar_link, linkLabel(state.links.bar))
                    for (feature in BAR_TCP_READOUTS) Fact(featureLabel(feature), tcpReadout(state, feature))
                    for ((key, label) in BAR_HTTP_READOUTS) Fact(label, httpReadout(state, key))
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

/** The 33336 detail keys, in reading order: health, identity, locale, then the link. */
private val BAR_TCP_READOUTS = listOf(
    TheatreFeature.TEMPERATURE,
    TheatreFeature.DEVICE_NAME,
    TheatreFeature.MANUFACTURER,
    TheatreFeature.REGION,
    TheatreFeature.LANGUAGE,
    TheatreFeature.TIMEZONE,
    TheatreFeature.TV_AV_SYNC,
    TheatreFeature.NETWORK_MODE,
    TheatreFeature.NETWORK_DHCP,
)

private val BAR_HTTP_READOUTS = listOf(
    BarHttpKey.NETWORK_TYPE to R.string.technical_bar_connection_type,
    BarHttpKey.INTERNET to R.string.technical_bar_internet,
    BarHttpKey.WIFI_STRENGTH to R.string.technical_bar_wifi,
    BarHttpKey.AIRPLAY_NAME to R.string.technical_bar_airplay,
)

@Composable
private fun from(value: String, source: Int): String = stringResource(R.string.technical_from, value, stringResource(source))

/** A 33336 detail key: its value, refused, asking, not reported, or why it cannot be asked. */
@Composable
private fun tcpReadout(state: TuttiUiState, feature: TheatreFeature): String {
    val bar = state.bar
    bar.confirmed[feature.key]?.let { return from(it, R.string.technical_source_tcp) }
    val asking = bar.outstanding.values.any { !it.isSet && it.feature == feature.key }
    return stringResource(
        when {
            feature.key in bar.refused -> R.string.technical_refused
            !bar.connected -> R.string.technical_needs_bar_link
            asking -> R.string.technical_asking
            else -> R.string.technical_unknown
        },
    )
}

/** A 54545 key from the last read; while a new read runs the last answer stays. */
@Composable
private fun httpReadout(state: TuttiUiState, key: BarHttpKey): String {
    val readouts = state.technical.barHttp
    if (readouts == null) {
        return stringResource(
            when (state.technical.barHttpFetch) {
                Fetch.IDLE, Fetch.LOADING -> R.string.technical_asking
                else -> R.string.technical_http_no_answer
            },
        )
    }
    return when (val value = readouts[key]) {
        is BarHttpValue.Answered -> from(value.value, R.string.technical_source_http)
        BarHttpValue.Refused -> stringResource(R.string.technical_refused)
        BarHttpValue.NotReported -> stringResource(R.string.technical_unknown)
    }
}

/** REST `getSystemSupportedFunction`: option names, with values only when they identify nothing. */
@Composable
private fun supportedFunctions(state: TuttiUiState): String {
    if (state.links.rest != Link.ONLINE) return stringResource(R.string.technical_needs_psk)
    val technical = state.technical
    val functions = technical.tvFunctions
    return when {
        functions != null && technical.tvFunctionsFetch != Fetch.FAILED -> {
            if (functions.isEmpty()) {
                stringResource(R.string.technical_none_reported)
            } else {
                val list = functions.joinToString(", ") { f -> f.shownValue?.let { "${f.option}: $it" } ?: f.option }
                from(list, R.string.technical_source_rest)
            }
        }
        technical.tvFunctionsFetch == Fetch.UNSUPPORTED -> stringResource(R.string.technical_not_listed)
        technical.tvFunctionsFetch == Fetch.FAILED -> stringResource(R.string.technical_no_answer)
        else -> stringResource(R.string.technical_asking)
    }
}
