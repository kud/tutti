package io.kud.tutti.ui.remote

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import io.kud.tutti.ui.motion.rememberWaiting
import kotlinx.coroutines.delay
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.res.painterResource
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.PressStyle
import io.kud.tutti.ui.common.barValueLabel
import io.kud.tutti.ui.common.rememberFeedback
import io.kud.tutti.ui.common.rememberPress
import io.kud.tutti.ui.common.rememberTuttiIndication
import io.kud.tutti.ui.motion.Motion
import io.kud.tutti.ui.motion.snappyOrFade
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.theatre.FeatureKind
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.ui.common.PinnedHeader
import io.kud.tutti.ui.common.barIcon
import io.kud.tutti.ui.common.belowStatusBar
import io.kud.tutti.ui.common.featureLabel
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
    // The bar on its own names its source first ("Bluetooth · Night"): it is what's playing.
    val source = barAlone(state)?.let { barValueLabel(TheatreFeature.INPUT, it.input) }
    if (source != null) return (listOf(source) + on).joinToString(" · ")
    return if (on.isEmpty()) stringResource(R.string.sound_summary_plain) else on.joinToString(" · ")
}

/**
 * The "Son" sheet: large toggles (sound field, voice, night, DRC, auto volume, IMAX,
 * 360 Spatial), bass and rear sliders, Voice Zoom 3 only if the bar answered the probe,
 * and the BRAVIA Connect deep link for calibration. With no bar added yet, "Add the soundbar"
 * leads straight to the onboarding's bar step ([onAddBar]), never back through the TV's.
 */
@Composable
fun SoundSheet(
    state: TuttiUiState,
    controller: TuttiController,
    modifier: Modifier = Modifier,
    peeking: Boolean = false,
    onAddBar: () -> Unit = {},
) {
    val haptics = rememberHaptics()
    // Folded, the sheet shows its title line and nothing else: the peek also covers the gesture bar's
    // inset, which used to reveal the top of "Bar input" under the summary. The rest fades in as the
    // sheet opens (opacity only, so it is also the reduced-motion form) and stays out of TalkBack's
    // reach until then; the drag handle's own action opens it.
    val rest by animateFloatAsState(if (peeking) 0f else 1f, tween(Motion.MEDIUM), label = "sheet-rest")
    val context = LocalContext.current
    // The sheet may rise to the window's top; its handle stops below the status bar and the rest scrolls.
    val statusTop = WindowInsets.statusBars.getTop(LocalDensity.current)
    val scroll = rememberScrollState()
    // The section whose "?" was tapped: its explanation sheet is open (design.md, "Son" sheet).
    var explaining by remember { mutableStateOf<SoundSection?>(null) }
    val layout = soundSheetLayout(state.voiceZoomAvailable)
    explaining?.let { ExplainSheet(it, layout, onDismiss = { explaining = null }) }
    // The title line is the sheet's pinned header: folded, it is the whole peek; open, it stays put
    // while the settings scroll under it (design.md, Sheets).
    PinnedHeader(
        scrolled = scroll.canScrollBackward,
        modifier = modifier.fillMaxWidth().belowStatusBar(statusTop),
        header = {
            // Title and summary differ in size, so they share a baseline, centred as a pair in the row.
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
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
        },
    ) {
        Column(
            Modifier
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .graphicsLayer { alpha = rest }
                .then(if (peeking) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            if (state.devices.bar == null) {
                // The way in for a bar added after the TV (Erwann, 2026-10-24): the bar step alone.
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onAddBar, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sound_add_bar))
                }
            }
            Spacer(Modifier.height(4.dp))
            BarInputRow(state, controller, onHelp = { explaining = SoundSection.Input })
            Spacer(Modifier.height(4.dp))

            val tiles = layout.tiles
            SectionLabel(stringResource(R.string.sound_section_effects), onHelp = { explaining = SoundSection.Effects })
            for (row in tiles.chunked(2)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (feature in row) {
                        SoundTile(
                            state = state,
                            feature = feature,
                            onTap = {
                                haptics.select()
                                if (feature.kind is FeatureKind.Toggle) controller.toggleBar(feature)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            for (feature in layout.choices) {
                ChoiceSegments(state, feature, onSelect = {
                    haptics.select()
                    controller.setBarChoice(feature, it)
                }, onHelp = { explaining = SoundSection.Choice(feature) })
            }

            if (layout.levels.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SectionLabel(stringResource(R.string.sound_section_levels), onHelp = { explaining = SoundSection.Levels })
            }
            for (feature in layout.levels) {
                LevelSlider(state, feature, onLevel = { controller.setBarLevel(feature, it) })
            }

            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = { openBraviaConnect(context) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sound_recalibrate))
            }
        }
    }
}

/** The Sound sheet's settings, in order; [soundSheetLayout] sorts them by kind. */
val SOUND_SHEET_FEATURES = listOf(
    TheatreFeature.SOUND_FIELD,
    TheatreFeature.VOICE,
    TheatreFeature.NIGHT,
    TheatreFeature.DRC,
    TheatreFeature.AUTO_VOLUME,
    TheatreFeature.IMAX,
    TheatreFeature.SPATIAL_360,
    TheatreFeature.VOICE_ZOOM,
    TheatreFeature.BASS,
    TheatreFeature.REAR_LEVEL,
    TheatreFeature.VOICE_ZOOM_LEVEL,
)

data class SoundSheetLayout(val tiles: List<TheatreFeature>, val choices: List<TheatreFeature>, val levels: List<TheatreFeature>)

/**
 * The rule (Erwann, 2026-10-24: "on a pas mal d'options qui ont plus que on/off"): only an on/off
 * setting ([FeatureKind.Toggle]) is a tile; every [FeatureKind.Choice], today DRC and IMAX, gets a
 * row showing all its options; levels are sliders. Voice Zoom 3 shows only if the bar answered the
 * probe. Tested in `SoundSheetLayoutTest`, so a new feature can't land as a misleading tile.
 */
fun soundSheetLayout(voiceZoom: Boolean, features: List<TheatreFeature> = SOUND_SHEET_FEATURES): SoundSheetLayout {
    val shown = features.filter {
        voiceZoom || (it != TheatreFeature.VOICE_ZOOM && it != TheatreFeature.VOICE_ZOOM_LEVEL)
    }
    return SoundSheetLayout(
        tiles = shown.filter { it.kind is FeatureKind.Toggle },
        choices = shown.filter { it.kind is FeatureKind.Choice },
        levels = shown.filter { it.kind is FeatureKind.Level },
    )
}

/**
 * A three-way bar setting (DRC, IMAX: auto, on, off) as one labelled row of segments, every option
 * in view and the current one ticked as well as filled, so it never rests on colour. TalkBack reads
 * the setting's name, then each option as a radio button with its selected state. Unavailable,
 * the segments are disabled and the reason shows under them, never hidden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSegments(state: TuttiUiState, feature: TheatreFeature, onSelect: (String) -> Unit, onHelp: () -> Unit) {
    val kind = feature.kind as? FeatureKind.Choice ?: return
    val reason = barReason(state, feature)
    val value = state.bar.value(feature)
    val label = stringResource(featureLabel(feature))
    val refused = state.bar.refusedValues(feature)
    val refusedWord = stringResource(R.string.choice_refused)
    val described = withHint(label, explanation(feature)?.let { stringResource(it) })
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        SectionLabel(label, onHelp = onHelp, modifier = Modifier.padding(bottom = 2.dp))
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().semantics { contentDescription = described },
        ) {
            kind.options.forEachIndexed { index, option ->
                val isRefused = option in refused
                SegmentedButton(
                    selected = option == value,
                    // A refused option stays tappable: a tap is a retry.
                    onClick = { if (option != value) onSelect(option) },
                    enabled = reason == null,
                    shape = SegmentedButtonDefaults.itemShape(index, kind.options.size),
                    icon = {
                        if (isRefused && option != value) {
                            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize))
                        } else {
                            SegmentedButtonDefaults.Icon(active = option == value)
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).then(
                        if (isRefused) Modifier.semantics { stateDescription = refusedWord } else Modifier,
                    ),
                ) {
                    Text(choiceLabel(option), maxLines = 1)
                }
            }
        }
        val labels = (kind.options + refused).associateWith { choiceLabel(it) }
        val note = reason ?: refused.filter { it != value }.joinToString { labels[it] ?: it }.ifEmpty { null }
            ?.let { stringResource(R.string.choice_refused_note, it) }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SoundTile(state: TuttiUiState, feature: TheatreFeature, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val reason = barReason(state, feature)
    val value = state.bar.value(feature)
    val on = state.bar.isOn(feature) == true
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (on && reason == null) scheme.primary else scheme.surfaceContainerHigh, snappyOrFade(), label = "tile")
    val content = if (on && reason == null) scheme.onPrimary else scheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPress(interaction, PressStyle.TILE)
    val feedback = rememberFeedback()
    val reduced = LocalReducedMotion.current
    val corner by animateDpAsState(if (on && reason == null) 16.dp else 24.dp, if (reduced) snap() else Motion.snappy(), label = "corner")
    val name = stringResource(featureLabel(feature))
    val hint = explanation(feature)?.let { stringResource(it) }

    // Pending, then the outcome: the tile has already flipped (optimistic). An arc shows only if the
    // bar takes more than 150 ms; a refusal springs the tile back (the rollback), shakes it 4 dp,
    // plays reject and says so for 3 s. Reduced motion keeps the words and the cross, not the shake.
    val pending = state.bar.pending[feature.key]?.value
    val waiting = rememberWaiting(pending != null)
    val outcome = remember { TileOutcome() }
    var refusals by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(pending, value, state.bar.connected) {
        if (outcome.observe(pending, value, state.bar.connected) == TileSettle.REFUSED) refusals++
    }
    LaunchedEffect(refusals) {
        if (refusals == 0) return@LaunchedEffect
        failed = true
        feedback.reject()
        if (!reduced) {
            shake.snapTo(0f)
            shake.animateTo(
                0f,
                keyframes {
                    durationMillis = Motion.MEDIUM
                    4f at 40
                    -4f at 100
                    3f at 160
                    -2f at 210
                },
            )
        }
        delay(FAILED_MS)
        failed = false
    }
    val stateLine = if (failed && reason == null) {
        stringResource(R.string.sound_tile_failed)
    } else {
        reason ?: stringResource(if (on) R.string.state_on else R.string.state_off)
    }
    Column(
        modifier
            .heightIn(min = 76.dp)
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
                translationX = shake.value.dp.toPx()
            }
            .background(container, RoundedCornerShape(corner))
            // A disabled tile still answers: a bump, and its reason stays in view (design.md: never hidden).
            .clickable(
                interactionSource = interaction,
                indication = rememberTuttiIndication(TILE_SHAPE, layerColor = content),
                role = Role.Switch,
                onClick = { if (reason == null) onTap() else feedback.bump() },
            )
            // TalkBack: the name, its state (or why it's greyed), then what it does, as its section's "?" says it.
            .semantics { contentDescription = withHint("$name, $stateLine", hint) }
            .alpha(if (reason == null) 1f else 0.6f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        // The glyph (recognition, never state) and the mark (state) share the top line, at opposite
        // corners, so the name below has the tile's whole width and never wraps at 390 dp.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(barIcon(feature.key)), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.weight(1f))
            TileMark(on = on, locked = reason != null, pending = waiting.spinner && !failed, color = content, onColor = container)
        }
        Spacer(Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, color = content)
        if (failed && reason == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.sound_tile_failed), style = MaterialTheme.typography.bodySmall, color = content, maxLines = 2)
            }
        } else {
            Text(
                text = reason ?: stringResource(if (on) R.string.state_on else R.string.state_off),
                style = MaterialTheme.typography.bodySmall,
                color = if (reason == null) content else scheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/**
 * Top right of a tile: an empty ring (off), a filled check (on), a lock in a dashed ring
 * (unavailable). While the bar is slow to confirm, a thin arc turns round it (static under
 * reduced motion: a status, not decoration).
 */
@Composable
private fun TileMark(on: Boolean, locked: Boolean, pending: Boolean, color: Color, onColor: Color) {
    val dashed = MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .size(20.dp)
            .drawBehind {
                val width = 1.5.dp.toPx()
                val radius = size.minDimension / 2f - width / 2f
                when {
                    locked -> drawCircle(dashed, radius, style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
                    on -> drawCircle(color)
                    else -> drawCircle(color, radius, style = Stroke(width))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            locked -> Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = dashed, modifier = Modifier.size(12.dp))
            on -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = onColor, modifier = Modifier.size(14.dp))
        }
        if (pending) {
            val arc = Modifier.requiredSize(28.dp)
            if (LocalReducedMotion.current) {
                CircularProgressIndicator(progress = { 0.75f }, modifier = arc, color = color, strokeWidth = 1.5.dp, trackColor = Color.Transparent)
            } else {
                CircularProgressIndicator(modifier = arc, color = color, strokeWidth = 1.5.dp, trackColor = Color.Transparent)
            }
        }
    }
}

private val TILE_SHAPE = RoundedCornerShape(24.dp)

/** How long a refused tile says so. */
private const val FAILED_MS = 3_000L

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
    val described = withHint(stringResource(featureLabel(feature)), explanation(feature)?.let { stringResource(it) })
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
            modifier = Modifier.semantics { contentDescription = described },
        )
    }
}

/** "−2", "0", "+3" with a real minus sign. */
fun signed(value: Int): String = when {
    value > 0 -> "+$value"
    value < 0 -> "−${-value}"
    else -> "0"
}
