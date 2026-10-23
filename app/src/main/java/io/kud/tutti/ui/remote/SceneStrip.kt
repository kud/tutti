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
import io.kud.tutti.controller.SceneStatus
import io.kud.tutti.controller.SceneUi
import io.kud.tutti.ui.common.glyphRes

fun contentOn(color: Color): Color = if (color.luminance() > 0.45f) Color.Black else Color.White

@Composable
fun tonalOf(seed: Color): Color = seed.copy(alpha = 0.24f).compositeOver(MaterialTheme.colorScheme.surfaceContainer)

@Composable
fun SceneStrip(
    scenes: List<SceneUi>,
    onApply: (String) -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(scenes, key = { it.scene.id }) { ui ->
            ScenePill(ui, onClick = { onApply(ui.scene.id) }, onLongClick = { onEdit(ui.scene.id) })
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
fun ScenePill(ui: SceneUi, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
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
    SceneStepTicks(ui.progress)
    Row(
        modifier = modifier
            .height(40.dp)
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
            }
            // Before the clip, so the focus ring sits outside the pill.
            .combinedClickable(
                interactionSource = interaction,
                indication = rememberTuttiIndication(shape, layerColor = content),
                onLongClick = onLongClick,
                onClick = onClick,
            )
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
            Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(2.dp))
            Text(stringResource(R.string.scene_failed_short), style = MaterialTheme.typography.labelSmall, color = content)
        } else if (ui.status == SceneStatus.MODIFIED) {
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.scene_modified), style = MaterialTheme.typography.labelSmall, color = seed)
        }
    }
}
