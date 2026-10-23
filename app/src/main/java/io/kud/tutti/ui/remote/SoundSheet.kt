package io.kud.tutti.ui.remote

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.theatre.FeatureKind
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.ui.common.belowStatusBar
import io.kud.tutti.ui.common.featureLabel
import io.kud.tutti.ui.common.motion
import io.kud.tutti.ui.common.openBraviaConnect
import io.kud.tutti.ui.common.rememberHaptics
import kotlin.math.roundToInt

/** Why a bar setting is greyed, or null when it is usable. A greyed setting always says why. */
@Composable
fun barReason(state: TuttiUiState, feature: TheatreFeature): String? = when {
    state.devices.bar == null -> stringResource(R.string.sound_reason_no_bar)
    !state.bar.connected -> stringResource(R.string.system_unreachable, state.devices.bar?.name.orEmpty())
    state.bar.value(feature) == null -> stringResource(R.string.sound_reason_unreported)
    else -> null
}

/** One line for the peek: what is on right now. */
@Composable
fun soundSummary(state: TuttiUiState): String {
    if (!state.bar.connected) return barReason(state, TheatreFeature.SOUND_FIELD).orEmpty()
    val on = listOf(TheatreFeature.SOUND_FIELD, TheatreFeature.VOICE, TheatreFeature.NIGHT, TheatreFeature.SPATIAL_360, TheatreFeature.AUTO_VOLUME)
        .filter { state.bar.isOn(it) == true }
        .map { stringResource(featureLabel(it)) }
    return if (on.isEmpty()) stringResource(R.string.sound_summary_plain) else on.joinToString(" · ")
}

/**
 * The "Son" sheet: large toggles (sound field, voice, night, DRC, auto volume, IMAX,
 * 360 Spatial), bass and rear sliders, Voice Zoom 3 only if the bar answered the probe,
 * and the BRAVIA Connect deep link for calibration.
 */
@Composable
fun SoundSheet(state: TuttiUiState, controller: TuttiController, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val context = LocalContext.current
    // The sheet may rise to the window's top; its handle stops below the status bar and the rest scrolls.
    val statusTop = WindowInsets.statusBars.getTop(LocalDensity.current)
    Column(
        modifier
            .fillMaxWidth()
            .belowStatusBar(statusTop)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        // Title and summary differ in size, so they share a baseline, centred as a pair in the row.
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.sound_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                soundSummary(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline().weight(1f, fill = false),
            )
        }
        Spacer(Modifier.height(8.dp))

        val tiles = listOf(
            TheatreFeature.SOUND_FIELD,
            TheatreFeature.VOICE,
            TheatreFeature.NIGHT,
            TheatreFeature.DRC,
            TheatreFeature.AUTO_VOLUME,
            TheatreFeature.IMAX,
            TheatreFeature.SPATIAL_360,
        ) + if (state.voiceZoomAvailable) listOf(TheatreFeature.VOICE_ZOOM) else emptyList()
        for (row in tiles.chunked(2)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (feature in row) {
                    SoundTile(
                        state = state,
                        feature = feature,
                        onTap = {
                            haptics.click()
                            when (val kind = feature.kind) {
                                is FeatureKind.Toggle -> controller.toggleBar(feature)
                                is FeatureKind.Choice -> {
                                    val current = state.bar.value(feature)
                                    val next = kind.options[(kind.options.indexOf(current) + 1).mod(kind.options.size)]
                                    controller.setBarChoice(feature, next)
                                }
                                else -> Unit
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        LevelSlider(state, TheatreFeature.BASS, onLevel = { controller.setBarLevel(TheatreFeature.BASS, it) })
        LevelSlider(state, TheatreFeature.REAR_LEVEL, onLevel = { controller.setBarLevel(TheatreFeature.REAR_LEVEL, it) })
        if (state.voiceZoomAvailable) {
            LevelSlider(state, TheatreFeature.VOICE_ZOOM_LEVEL, onLevel = { controller.setBarLevel(TheatreFeature.VOICE_ZOOM_LEVEL, it) })
        }

        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = { openBraviaConnect(context) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sound_recalibrate))
        }
    }
}

@Composable
private fun SoundTile(state: TuttiUiState, feature: TheatreFeature, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val reason = barReason(state, feature)
    val value = state.bar.value(feature)
    val on = when (feature.kind) {
        is FeatureKind.Toggle -> state.bar.isOn(feature) == true
        is FeatureKind.Choice -> value == "on" || value == "auto"
        else -> false
    }
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (on && reason == null) scheme.primaryContainer else scheme.surfaceContainerHigh, motion(), label = "tile")
    val content = if (on && reason == null) scheme.onPrimaryContainer else scheme.onSurface
    Column(
        modifier
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(container)
            .clickable(enabled = reason == null, role = Role.Switch, onClick = onTap)
            .alpha(if (reason == null) 1f else 0.6f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(featureLabel(feature)), style = MaterialTheme.typography.titleSmall, color = content)
        Text(
            text = reason ?: when (feature.kind) {
                is FeatureKind.Choice -> choiceLabel(value)
                else -> stringResource(if (on) R.string.state_on else R.string.state_off)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (reason == null) content else scheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

@Composable
fun choiceLabel(value: String?): String = when (value) {
    "auto" -> stringResource(R.string.state_auto)
    "on" -> stringResource(R.string.state_on)
    "off" -> stringResource(R.string.state_off)
    null -> "—"
    else -> value
}

/** A level slider that moves locally while dragging and sends once on release. */
@Composable
fun LevelSlider(state: TuttiUiState, feature: TheatreFeature, onLevel: (Int) -> Unit, modifier: Modifier = Modifier) {
    val kind = feature.kind as? FeatureKind.Level ?: return
    val haptics = rememberHaptics()
    val reason = barReason(state, feature)
    val current = state.bar.level(feature) ?: 0
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: current.toFloat()
    Column(modifier.fillMaxWidth().padding(top = 12.dp).alpha(if (reason == null) 1f else 0.6f)) {
        // Label and value differ in size: one baseline, and a long reason ellipsises instead of wrapping.
        Row(Modifier.fillMaxWidth()) {
            Text(
                stringResource(featureLabel(feature)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.alignByBaseline().weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                reason ?: signed(shown.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Slider(
            value = shown,
            onValueChange = {
                if (it.roundToInt() != shown.roundToInt()) haptics.tick()
                dragging = it
            },
            onValueChangeFinished = {
                dragging?.let { onLevel(it.roundToInt()) }
                dragging = null
            },
            valueRange = kind.min.toFloat()..kind.max.toFloat(),
            steps = (kind.max - kind.min - 1).coerceAtLeast(0),
            enabled = reason == null,
        )
    }
}

/** "−2", "0", "+3" with a real minus sign. */
fun signed(value: Int): String = when {
    value > 0 -> "+$value"
    value < 0 -> "−${-value}"
    else -> "0"
}
