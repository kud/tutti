package io.kud.tutti.ui.remote

import io.kud.tutti.ui.motion.snappyOrFade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import io.kud.tutti.ui.common.PressStyle
import io.kud.tutti.ui.common.rememberFeedback
import io.kud.tutti.ui.common.rememberPress
import io.kud.tutti.ui.common.rememberTuttiIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import io.kud.tutti.ui.common.TuttiMenu
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.ui.common.longPressRing
import io.kud.tutti.ui.common.rememberLongPressProgress
import io.kud.tutti.controller.SceneStatus
import io.kud.tutti.controller.SceneUi
import io.kud.tutti.ui.common.glyphRes

fun contentOn(color: Color): Color = if (color.luminance() > 0.45f) Color.Black else Color.White

@Composable
fun tonalOf(seed: Color): Color = seed.copy(alpha = 0.24f).compositeOver(MaterialTheme.colorScheme.surfaceContainer)

/**
 * The strip of scene pills. A tap applies; a long press opens the pill's menu (Iris's gesture
 * audit, 2026-10-25), the most useful first: "Undo the last scene" on the pill of the scene that
 * can still be undone ([undoSceneId]), then "Apply", "Edit scene" and "Pin to the dock" last.
 * Each row is also a TalkBack action on the pill.
 */
@Composable
fun SceneStrip(
    scenes: List<SceneUi>,
    onApply: (String) -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
    undoSceneId: String? = null,
    onUndo: () -> Unit = {},
    onPin: ((String) -> Unit)? = null,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(scenes, key = { it.scene.id }) { ui ->
            val id = ui.scene.id
            SceneWithMenu(
                ui = ui,
                undoable = id == undoSceneId,
                onApply = { onApply(id) },
                onUndo = onUndo,
                onEdit = { onEdit(id) },
                onPin = onPin?.let { pin -> { pin(id) } },
            )
        }
    }
}

/** The rows of a pill's menu, in order: the most useful first, Edit and Pin last. */
enum class SceneMenuRow { UNDO, APPLY, EDIT, PIN }

/** Which rows a pill's menu offers (tested): Undo only on the scene that can still be undone. */
fun sceneMenu(undoable: Boolean, canPin: Boolean): List<SceneMenuRow> = buildList {
    if (undoable) add(SceneMenuRow.UNDO)
    add(SceneMenuRow.APPLY)
    add(SceneMenuRow.EDIT)
    if (canPin) add(SceneMenuRow.PIN)
}

@Composable
private fun SceneWithMenu(
    ui: SceneUi,
    undoable: Boolean,
    onApply: () -> Unit,
    onUndo: () -> Unit,
    onEdit: () -> Unit,
    onPin: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    val haptics = io.kud.tutti.ui.common.rememberHaptics()
    val rows = sceneMenu(undoable, onPin != null)
    val labels = rows.associateWith { row ->
        when (row) {
            SceneMenuRow.UNDO -> stringResource(R.string.scene_undo_menu)
            SceneMenuRow.APPLY -> stringResource(R.string.scene_apply)
            SceneMenuRow.EDIT -> stringResource(R.string.editor_edit)
            SceneMenuRow.PIN -> stringResource(R.string.apps_pin_action)
        }
    }
    fun run(row: SceneMenuRow) {
        menu = false
        when (row) {
            SceneMenuRow.UNDO -> onUndo()
            SceneMenuRow.APPLY -> onApply()
            SceneMenuRow.EDIT -> onEdit()
            SceneMenuRow.PIN -> onPin?.invoke()
        }
    }
    Box {
        ScenePill(
            ui,
            onClick = onApply,
            onLongClick = {
                haptics.longPress()
                menu = true
            },
            longClickLabel = stringResource(R.string.scene_options),
            // TalkBack: the tap applies; every other row is a named action, so nothing needs the long press.
            modifier = Modifier.semantics {
                customActions = rows.filter { it != SceneMenuRow.APPLY }.map { row ->
                    CustomAccessibilityAction(labels.getValue(row)) { run(row); true }
                }
            },
        )
        TuttiMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for (row in rows) {
                DropdownMenuItem(
                    text = { Text(labels.getValue(row), fontWeight = if (row == rows.first()) FontWeight.Medium else null) },
                    leadingIcon = {
                        Icon(
                            painterResource(
                                when (row) {
                                    SceneMenuRow.UNDO -> R.drawable.ic_back
                                    SceneMenuRow.APPLY -> R.drawable.ic_play
                                    SceneMenuRow.EDIT -> R.drawable.ic_edit
                                    SceneMenuRow.PIN -> R.drawable.ic_add
                                },
                            ),
                            contentDescription = null,
                        )
                    },
                    onClick = { run(row) },
                )
            }
        }
    }
}

/**
 * Whether a run's progress moved [before] -> [now] by a step worth a tick. The last step is left
 * to the outcome (doublePulse or reject), so a scene never ends on two haptics.
 */
internal fun isSceneStep(before: Float?, now: Float?): Boolean = before != null && now != null && now > before && now < 1f

/** Ticks once per scene step while [progress] runs. */
@Composable
fun SceneStepTicks(progress: Float?) {
    val feedback = rememberFeedback()
    val last = remember { floatArrayOf(Float.NaN) }
    LaunchedEffect(progress) {
        val before = last[0].takeUnless { it.isNaN() }
        if (isSceneStep(before, progress)) feedback.tick()
        last[0] = progress ?: Float.NaN
    }
}

/**
 * A pill in its own tonal colour. Active: filled, with a check. Changed by hand: outlined and
 * marked "modifiée". Applying: fills left to right with the run's progress, a tick per step.
 * Ended short: the fill drains and a cross says "failed". The press answers on down (scale, layer,
 * click); callers add no haptic of their own.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScenePill(ui: SceneUi, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier, longClickLabel: String? = null) {
    val seed = Color(ui.scene.color)
    val tonal = tonalOf(seed)
    val container by animateColorAsState(
        when (ui.status) {
            SceneStatus.ACTIVE -> seed
            SceneStatus.MODIFIED -> MaterialTheme.colorScheme.surfaceContainer
            SceneStatus.IDLE -> tonal
        },
        snappyOrFade(),
        label = "pill",
    )
    val content = if (ui.status == SceneStatus.ACTIVE) contentOn(seed) else MaterialTheme.colorScheme.onSurface
    val progress by animateFloatAsState(ui.progress ?: 0f, snappyOrFade(), label = "progress")
    val shape = RoundedCornerShape(percent = 50)
    val failed = ui.failure != null && ui.progress == null
    val interaction = remember { MutableInteractionSource() }
    val feedback = rememberFeedback()
    val press = rememberPress(interaction, PressStyle.TILE, onDown = feedback::click)
    // The long press opens the editor: the same ring as the segments and tiles (rule 1).
    val ring = rememberLongPressProgress(interaction, enabled = true)
    val ringColour = MaterialTheme.colorScheme.onSurface
    SceneStepTicks(ui.progress)
    Row(
        modifier = modifier
            // 48 dp to touch, a 40 dp pill to see.
            .height(48.dp)
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
            }
            // Before the clip, so the focus ring sits outside the pill.
            .combinedClickable(
                interactionSource = interaction,
                indication = rememberTuttiIndication(shape, layerColor = content),
                onLongClickLabel = longClickLabel ?: stringResource(R.string.editor_edit),
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(vertical = 4.dp)
            .longPressRing({ ring.value }, shape, ringColour)
            .clip(shape)
            .background(container)
            .then(if (ui.status == SceneStatus.MODIFIED) Modifier.border(1.5.dp, seed, shape) else Modifier)
            // Drawn while the fill animates, so a run that ends drains rather than vanishing.
            .drawBehind {
                if (progress > 0f) drawRect(seed.copy(alpha = 0.6f), size = Size(size.width * progress, size.height))
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(glyphRes(ui.scene.glyph)), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(ui.scene.name, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        if (ui.status == SceneStatus.ACTIVE && !failed) {
            Spacer(Modifier.width(6.dp))
            Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.scenes_active), tint = content, modifier = Modifier.size(16.dp))
        }
        if (failed) {
            Spacer(Modifier.width(6.dp))
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(2.dp))
            Text(stringResource(R.string.scene_failed_short), style = MaterialTheme.typography.labelSmall, color = content)
        } else if (ui.status == SceneStatus.MODIFIED) {
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.scene_modified), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Which dock slot takes the scene; each row says what it replaces, as the Apps sheet's pin dialog does. */
@Composable
internal fun ScenePinDialog(
    sceneName: String,
    state: io.kud.tutti.controller.TuttiUiState,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_pin_title, sceneName)) },
        text = {
            androidx.compose.foundation.layout.Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                val pages = state.dock.pages
                for (index in 0 until pages.size * io.kud.tutti.platform.data.DockConfig.PAGE_SIZE) {
                    val page = index / io.kud.tutti.platform.data.DockConfig.PAGE_SIZE
                    val offset = index % io.kud.tutti.platform.data.DockConfig.PAGE_SIZE
                    if (pages.size > 1 && offset == 0) {
                        Text(
                            stringResource(R.string.apps_pin_page, page + 1),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = if (page > 0) 8.dp else 0.dp, bottom = 2.dp),
                        )
                    }
                    val current = state.dock.slot(index).primary
                    val what = current?.let { io.kud.tutti.ui.common.actionLabel(it, state) } ?: stringResource(R.string.apps_slot_empty)
                    Text(
                        stringResource(R.string.apps_slot, offset + 1, what),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(index) }
                            .padding(vertical = 14.dp, horizontal = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
