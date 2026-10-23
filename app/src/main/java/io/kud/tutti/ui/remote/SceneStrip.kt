package io.kud.tutti.ui.remote

import io.kud.tutti.ui.motion.snappyOrFade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
 * A pill in its own tonal colour. Active: filled. Changed by hand: outlined and marked
 * "modifiée". Applying: fills left to right with the run's progress.
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
    val running = ui.progress != null
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(container)
            .then(if (ui.status == SceneStatus.MODIFIED) Modifier.border(1.5.dp, seed, shape) else Modifier)
            .drawBehind {
                if (running) drawRect(seed.copy(alpha = 0.6f), size = Size(size.width * progress, size.height))
            }
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(glyphRes(ui.scene.glyph)), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(ui.scene.name, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        if (ui.status == SceneStatus.MODIFIED) {
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.scene_modified), style = MaterialTheme.typography.labelSmall, color = seed)
        }
    }
}
