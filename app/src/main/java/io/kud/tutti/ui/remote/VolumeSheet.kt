package io.kud.tutti.ui.remote

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.CapUntil
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.controller.VolumeAsk
import io.kud.tutti.controller.VolumeRules
import io.kud.tutti.ui.common.PinnedHeader
import io.kud.tutti.ui.common.TuttiMenu
import io.kud.tutti.ui.common.TuttiSheet
import io.kud.tutti.ui.common.rememberHaptics
import java.time.ZoneId
import java.util.Date

/**
 * The volume sheet (Iris's gesture audit, 2026-10-25), opened by a tap on the rail's number: the
 * bar's level exactly, three saved levels and a cap for tonight. The rail drives the soundbar, so
 * this sheet does too.
 *
 * Nothing here jumps the volume from a stray touch: −, + and the slider move a *new level* drawn
 * large, and only "Set to n" sends it. A saved level is a deliberate tap on a labelled tile. Both
 * go through the controller like the rail, so the ceiling clamps them and the high-volume guard
 * asks before going above its line.
 */
@Composable
fun VolumeSheet(
    state: TuttiUiState,
    onSet: (Int) -> Unit,
    onSavePreset: (slot: Int, level: Int?) -> Unit,
    onCap: (level: Int, until: Long) -> Unit,
    onClearCap: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberHaptics()
    val barName = state.devices.bar?.name
    val connected = state.bar.connected
    val volume = state.volume.takeIf { connected }
    val ceiling = state.volumeCap ?: TuttiController.MAX_VOLUME
    val downReason = controlLinkReason(state.controlLink(Owner.BAR), Owner.BAR, state)

    TuttiSheet(onDismissRequest = onDismiss) {
        val scroll = rememberScrollState()
        PinnedHeader(
            scrolled = scroll.canScrollBackward,
            modifier = Modifier.fillMaxWidth(),
            header = {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    Text(
                        stringResource(R.string.volume_sheet_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (barName != null) {
                        Text(barName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (barName == null) {
                    Text(stringResource(R.string.volume_sheet_no_bar), style = MaterialTheme.typography.bodyMedium)
                    return@Column
                }

                // ── The exact level ──
                var draft by rememberSaveable { mutableIntStateOf(volume ?: 0) }
                var touched by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(volume) { if (!touched && volume != null) draft = volume }
                val target = draft.coerceIn(0, ceiling)
                val newLabel = stringResource(R.string.volume_sheet_new)
                if (downReason != null) {
                    DownLine(downReason)
                } else {
                    Text(
                        if (volume != null) stringResource(R.string.volume_sheet_now, volume) else stringResource(R.string.volume_sheet_now_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                LevelStepper(
                    label = stringResource(R.string.volume_sheet_new),
                    value = target,
                    max = ceiling,
                    enabled = connected,
                    downLabel = stringResource(R.string.volume_sheet_down),
                    upLabel = stringResource(R.string.volume_sheet_up),
                    large = true,
                    onChange = {
                        touched = true
                        draft = it
                        haptics.tick()
                    },
                )
                Slider(
                    value = target.toFloat(),
                    onValueChange = {
                        touched = true
                        val next = it.toInt()
                        if (next != draft) haptics.tick()
                        draft = next
                    },
                    valueRange = 0f..ceiling.toFloat().coerceAtLeast(1f),
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = newLabel },
                )
                state.volumeGuardLine?.takeIf { target > it }?.let { line ->
                    Text(stringResource(R.string.volume_sheet_guard_note, line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = {
                        haptics.confirm()
                        touched = false
                        onSet(target)
                    },
                    enabled = connected && target != volume,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        if (target == volume) stringResource(R.string.volume_sheet_already, target) else stringResource(R.string.volume_sheet_set, target),
                    )
                }

                // ── Saved levels ──
                SectionLabel(stringResource(R.string.volume_presets_title))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VolumeRules.presets(state.prefs.volumePresets).forEachIndexed { slot, level ->
                        PresetTile(
                            level = level,
                            current = volume,
                            enabled = connected,
                            onUse = { saved ->
                                haptics.click()
                                onSet(saved)
                            },
                            onSave = { now ->
                                haptics.confirm()
                                onSavePreset(slot, now)
                            },
                            onRemove = {
                                haptics.select()
                                onSavePreset(slot, null)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(stringResource(R.string.volume_presets_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // ── Cap for tonight ──
                SectionLabel(stringResource(R.string.volume_cap_title))
                val context = LocalContext.current
                val tonight = state.tonightCap
                val sceneCap = state.sceneVolumeCap
                val sceneName = state.activeScene?.scene?.name
                if (sceneCap != null && sceneName != null) {
                    Text(stringResource(R.string.volume_cap_scene, sceneName, sceneCap), style = MaterialTheme.typography.bodyMedium)
                }
                if (tonight != null) {
                    val time = android.text.format.DateFormat.getTimeFormat(context).format(Date(tonight.until))
                    Text(stringResource(R.string.volume_cap_active, tonight.level, time), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(
                        onClick = {
                            haptics.select()
                            onClearCap()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.volume_cap_remove)) }
                } else {
                    Text(stringResource(R.string.volume_cap_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var capLevel by rememberSaveable { mutableIntStateOf(minOf(volume ?: DEFAULT_CAP, DEFAULT_CAP)) }
                    var until by rememberSaveable { mutableStateOf(CapUntil.MORNING) }
                    LevelStepper(
                        label = stringResource(R.string.volume_cap_level),
                        value = capLevel,
                        max = TuttiController.MAX_VOLUME,
                        enabled = true,
                        downLabel = stringResource(R.string.volume_cap_down),
                        upLabel = stringResource(R.string.volume_cap_up),
                        large = false,
                        onChange = {
                            capLevel = it
                            haptics.tick()
                        },
                    )
                    val morning = android.text.format.DateFormat.getTimeFormat(context).format(
                        Date(VolumeRules.capUntil(System.currentTimeMillis(), ZoneId.systemDefault(), CapUntil.MORNING)),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (choice in CapUntil.entries) {
                            val text = when (choice) {
                                CapUntil.ONE_HOUR -> stringResource(R.string.volume_cap_one_hour)
                                CapUntil.THREE_HOURS -> stringResource(R.string.volume_cap_three_hours)
                                CapUntil.MORNING -> stringResource(R.string.volume_cap_until, morning)
                            }
                            FilterChip(
                                selected = until == choice,
                                onClick = {
                                    haptics.select()
                                    until = choice
                                },
                                label = { Text(text, maxLines = 1) },
                                // The choice is told by the tick, never by the fill alone.
                                leadingIcon = if (until == choice) {
                                    { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else {
                                    null
                                },
                                modifier = Modifier.semantics { role = Role.RadioButton },
                            )
                        }
                    }
                    Button(
                        onClick = {
                            haptics.confirm()
                            onCap(capLevel, VolumeRules.capUntil(System.currentTimeMillis(), ZoneId.systemDefault(), until))
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.volume_cap_set, capLevel)) }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

/** Why the level can't be set now: the warning glyph and the words, never colour alone. */
@Composable
private fun DownLine(reason: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** − value +, each key a 48 dp target; the value is what will be sent, not what is playing. */
@Composable
private fun LevelStepper(
    label: String,
    value: Int,
    max: Int,
    enabled: Boolean,
    downLabel: String,
    upLabel: String,
    large: Boolean,
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(0)) }, enabled = enabled && value > 0) {
            Icon(painterResource(R.drawable.ic_remove), contentDescription = downLabel)
        }
        Text(
            value.toString(),
            style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(if (large) 88.dp else 64.dp).semantics {
                contentDescription = label
                stateDescription = value.toString()
            },
        )
        IconButton(onClick = { onChange((value + 1).coerceAtMost(max)) }, enabled = enabled && value < max) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = upLabel)
        }
    }
}

/**
 * One saved level. Filled: the level, large; a tap sets it, a long press offers "Replace with n"
 * and "Remove". Empty: "Save n here", a dashed outline, a tap saves the level now. TalkBack hears
 * the tap as the click and the rest as named actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetTile(
    level: Int?,
    current: Int?,
    enabled: Boolean,
    onUse: (Int) -> Unit,
    onSave: (Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val replaceLabel = current?.let { stringResource(R.string.volume_preset_replace, it) }
    val removeLabel = stringResource(R.string.volume_preset_remove)
    val tileLabel = level?.let { stringResource(R.string.volume_preset_label, it) }
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) }
    val outline = scheme.outline
    Box(modifier) {
        val base = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
        val dressed = if (level != null) {
            base.background(scheme.secondaryContainer, shape)
        } else {
            // Empty is a dashed outline, a shape and not a colour.
            base.drawBehind {
                drawRoundRect(outline, style = Stroke(width = 1.5.dp.toPx(), pathEffect = dash), cornerRadius = CornerRadius(16.dp.toPx()))
            }
        }
        val clickable = when {
            level != null -> dressed
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.volume_sheet_set, level),
                    onLongClick = { menu = true },
                    onLongClickLabel = listOfNotNull(replaceLabel, removeLabel).joinToString(" · "),
                    onClick = { onUse(level) },
                )
                .semantics {
                    contentDescription = tileLabel.orEmpty()
                    customActions = listOfNotNull(
                        replaceLabel?.let { label -> CustomAccessibilityAction(label) { onSave(current); true } },
                        CustomAccessibilityAction(removeLabel) { onRemove(); true },
                    )
                }
            current != null -> dressed.combinedClickable(role = Role.Button, onClick = { onSave(current) })
            else -> dressed
        }
        Column(clickable.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            when {
                level != null -> {
                    Text(level.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSecondaryContainer)
                }
                current != null -> Text(stringResource(R.string.volume_preset_empty, current), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                else -> Text(stringResource(R.string.volume_preset_empty_unknown), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            }
        }
        if (level != null) {
            TuttiMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (current != null && replaceLabel != null) {
                    DropdownMenuItem(
                        text = { Text(replaceLabel) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_refresh), contentDescription = null) },
                        onClick = {
                            menu = false
                            onSave(current)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(removeLabel) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                    onClick = {
                        menu = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/**
 * The high-volume guard's question (Erwann, 2026-10-25: « Attention, le volume devient élevé.
 * Monter au-delà de 30 ? »). The level already stopped on the line; "Go to n" says exactly where
 * it goes, and dismissing stays.
 */
@Composable
fun VolumeGuardDialog(ask: VolumeAsk, onGo: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        icon = { Icon(painterResource(R.drawable.ic_volume_up), contentDescription = null) },
        title = { Text(stringResource(R.string.volume_guard_title)) },
        text = { Text(stringResource(R.string.volume_guard_body, ask.threshold, ask.wanted)) },
        confirmButton = { TextButton(onClick = onGo) { Text(stringResource(R.string.volume_guard_go, ask.wanted)) } },
        dismissButton = { TextButton(onClick = onStay) { Text(stringResource(R.string.volume_guard_stay, ask.threshold)) } },
    )
}

/** A cap for tonight starts at 20, Nuit's own cap, or lower when the bar is already quieter. */
private const val DEFAULT_CAP = 20
