package io.kud.tutti.ui.remote

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.theatre.FeatureKind
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.PressStyle
import io.kud.tutti.ui.common.barValueLabel
import io.kud.tutti.ui.common.rememberFeedback
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.common.rememberPress
import io.kud.tutti.ui.common.rememberTuttiIndication
import io.kud.tutti.ui.motion.rememberWaiting
import io.kud.tutti.ui.motion.snappyOrFade
import kotlinx.coroutines.delay

/**
 * The inputs a chip row offers: the documented `main.input` values, plus whatever the bar reports
 * if it is none of them (TCP reports only the current value, never a list), so the current input
 * always has a chip and is never shown as "—" beside a row that lacks it.
 */
fun barInputChoices(current: String?): List<String> {
    val options = (TheatreFeature.INPUT.kind as FeatureKind.Choice).options
    return if (current == null || current in options) options else options + current
}

/**
 * "Bar input" in the Son sheet: one chip per input, the current one filled and ticked (never
 * colour alone). Choosing one is optimistic like the tiles: the chip moves at once, an arc shows
 * only if the bar is slow, and a refusal moves it back, plays reject and says so for 3 s.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BarInputRow(state: TuttiUiState, controller: TuttiController, modifier: Modifier = Modifier) {
    val feature = TheatreFeature.INPUT
    val reason = barReason(state, feature)
    val current = state.bar.value(feature)
    val haptics = rememberHaptics()
    val feedback = rememberFeedback()
    val pending = state.bar.pending[feature.key]?.value
    val waiting = rememberWaiting(pending != null)
    val outcome = remember { TileOutcome() }
    var refusals by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(pending, current, state.bar.connected) {
        if (outcome.observe(pending, current, state.bar.connected) == TileSettle.REFUSED) refusals++
    }
    LaunchedEffect(refusals) {
        if (refusals == 0) return@LaunchedEffect
        failed = true
        feedback.reject()
        delay(INPUT_FAILED_MS)
        failed = false
    }
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.bar_input),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.alignByBaseline().weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            if (failed && reason == null) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = when {
                    reason != null -> reason
                    failed -> stringResource(R.string.sound_tile_failed)
                    current != null -> barValueLabel(feature, current)
                    else -> "—"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline(),
            )
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp).alpha(if (reason == null) 1f else 0.6f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (option in barInputChoices(current)) {
                InputChip(
                    label = barValueLabel(feature, option),
                    selected = option == current,
                    pending = option == pending && waiting.spinner,
                    onTap = {
                        when {
                            reason != null -> feedback.bump()
                            option == current -> haptics.tick()
                            else -> {
                                haptics.select()
                                controller.setBarChoice(feature, option)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun InputChip(label: String, selected: Boolean, pending: Boolean, onTap: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) scheme.primaryContainer else Color.Transparent, snappyOrFade(), label = "input")
    val content = if (selected) scheme.onPrimaryContainer else scheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPress(interaction, PressStyle.TILE)
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
            }
            .background(container, CHIP_SHAPE)
            // Unselected chips wear an outline; the selected one a fill and a tick.
            .border(1.dp, if (selected) Color.Transparent else scheme.outline, CHIP_SHAPE)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = rememberTuttiIndication(CHIP_SHAPE, layerColor = content),
                role = Role.RadioButton,
                onClick = onTap,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected || pending) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when {
                    pending && LocalReducedMotion.current ->
                        CircularProgressIndicator(progress = { 0.75f }, modifier = Modifier.size(14.dp), color = content, strokeWidth = 1.5.dp, trackColor = Color.Transparent)
                    pending -> CircularProgressIndicator(modifier = Modifier.size(14.dp), color = content, strokeWidth = 1.5.dp, trackColor = Color.Transparent)
                    else -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

private val CHIP_SHAPE = RoundedCornerShape(12.dp)

/** How long a refused input says so, like a refused tile. */
private const val INPUT_FAILED_MS = 3_000L
