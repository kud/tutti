package io.kud.tutti.ui.remote

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.RowScope
import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.theatre.FeatureKind
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.ui.common.brandColour
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

/** The glyph over an input's segment: no brand logos, so Spotify is a music note. */
@DrawableRes
fun barInputIcon(value: String): Int = when (value) {
    "tv" -> R.drawable.ic_tv
    "hdmi1" -> R.drawable.ic_input
    "spotify" -> R.drawable.brand_spotify
    "bluetooth" -> R.drawable.ic_bluetooth
    "airplay2" -> R.drawable.ic_airplay
    else -> R.drawable.ic_speaker
}

/** A segment's word, short enough for a fifth of a phone: "AirPlay", where the value line says "AirPlay 2". */
fun barInputSegmentLabel(value: String, full: String): String = if (value == "airplay2") "AirPlay" else full

/** Five inputs share the row's width; more scroll sideways at a fixed width, never wrapping. */
fun barInputScrolls(count: Int, fontScale: Float = 1f): Boolean = count > (MAX_FITTED_INPUTS / fontScale.coerceAtLeast(1f)).toInt()

private const val MAX_FITTED_INPUTS = 5

/**
 * "Bar input" in the Son sheet, in DRC and IMAX's dress: one outlined row of segments, each an
 * icon over its name, the current one filled with a tick in place of its icon (never colour
 * alone), read by TalkBack as radio buttons under the "Bar input" label. Choosing one is
 * optimistic like the tiles: the segment moves at once, an arc shows only if the bar is slow, and
 * a refusal moves it back, plays reject and says so for 3 s; a refused input then wears the
 * warning glyph and the word "refused", and stays tappable as a retry.
 */
@Composable
fun BarInputRow(state: TuttiUiState, controller: TuttiController, modifier: Modifier = Modifier, onHelp: () -> Unit = {}) {
    val feature = TheatreFeature.INPUT
    val reason = barReason(state, feature)
    val current = state.bar.value(feature)
    val haptics = rememberHaptics()
    val feedback = rememberFeedback()
    val pending = state.bar.pending[feature.key]?.value
    val waiting = rememberWaiting(pending != null)
    val outcome = remember { TileOutcome() }
    val refused = state.bar.refusedValues(feature)
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
    val title = stringResource(R.string.bar_input)
    val described = withHint(title, stringResource(R.string.explain_input))
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // The label, the current value, then the section's "?" (design.md, "Son" sheet).
        SectionLabel(title, onHelp = onHelp) {
            Spacer(Modifier.width(12.dp))
            if (failed && reason == null) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(14.dp))
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
            )
        }
        val choices = barInputChoices(current)
        val scrolls = barInputScrolls(choices.size, LocalDensity.current.fontScale)
        val outline = MaterialTheme.colorScheme.outline
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .alpha(if (reason == null) 1f else 0.6f)
                .then(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .height(IntrinsicSize.Min)
                .clip(CircleShape)
                .border(1.dp, outline, CircleShape)
                .selectableGroup()
                .semantics { contentDescription = described },
        ) {
            choices.forEachIndexed { index, option ->
                if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(outline))
                InputSegment(
                    icon = barInputIcon(option),
                    label = barInputSegmentLabel(option, barValueLabel(feature, option)),
                    selected = option == current,
                    pending = option == pending && waiting.spinner,
                    refused = option in refused && option != current,
                    fixedWidth = scrolls,
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
        // The refused word, as under DRC and IMAX: the segment wears the glyph, this line says it.
        val refusedLabels = refused.filter { it != current }.map { barValueLabel(feature, it) }
        val refusedNames = refusedLabels.joinToString()
        if (reason == null && refusedNames.isNotEmpty()) {
            Text(
                stringResource(R.string.choice_refused_note, refusedNames),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RowScope.InputSegment(
    @DrawableRes icon: Int,
    label: String,
    selected: Boolean,
    pending: Boolean,
    refused: Boolean,
    fixedWidth: Boolean,
    onTap: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) scheme.secondaryContainer else Color.Transparent, snappyOrFade(), label = "input")
    val content = if (selected) scheme.onSecondaryContainer else scheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPress(interaction, PressStyle.TILE)
    val refusedWord = stringResource(R.string.choice_refused)
    Column(
        Modifier
            .then(if (fixedWidth) Modifier.widthIn(min = 76.dp) else Modifier.weight(1f))
            .heightIn(min = 64.dp)
            .background(container)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = rememberTuttiIndication(SEGMENT_SHAPE, layerColor = content),
                role = Role.RadioButton,
                onClick = onTap,
            )
            .then(if (refused) Modifier.semantics { stateDescription = refusedWord } else Modifier)
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
            }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                pending && LocalReducedMotion.current ->
                    CircularProgressIndicator(progress = { 0.75f }, modifier = Modifier.size(18.dp), color = content, strokeWidth = 1.5.dp, trackColor = Color.Transparent)
                pending -> CircularProgressIndicator(modifier = Modifier.size(18.dp), color = content, strokeWidth = 1.5.dp, trackColor = Color.Transparent)
                // The tick replaces the icon on the chosen input, as on DRC's segments.
                selected -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                refused -> Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                // A brand logo keeps its own colour (Erwann, 2026-10-25), at the state's opacity.
                else -> Icon(painterResource(icon), contentDescription = null, tint = brandColour(icon)?.let { Color(it).copy(alpha = content.alpha) } ?: content, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private val SEGMENT_SHAPE = RoundedCornerShape(0.dp)

/** How long a refused input says so, like a refused tile. */
private const val INPUT_FAILED_MS = 3_000L
