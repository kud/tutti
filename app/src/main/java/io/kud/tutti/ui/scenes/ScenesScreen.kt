package io.kud.tutti.ui.scenes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.controller.SceneStatus
import io.kud.tutti.controller.SceneUi
import io.kud.tutti.ui.common.glyphRes
import io.kud.tutti.ui.common.motion
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.remote.contentOn
import io.kud.tutti.ui.remote.tonalOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenesScreen(
    state: TuttiUiState,
    controller: TuttiController,
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
) {
    val haptics = rememberHaptics()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scenes_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(null) },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.scenes_new)) },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 168.dp),
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.scenes, key = { it.scene.id }) { ui ->
                SceneCard(
                    ui = ui,
                    summary = sceneSummary(ui.scene, state),
                    onApply = {
                        haptics.click()
                        controller.applyScene(ui.scene.id)
                    },
                    onEdit = { onEdit(ui.scene.id) },
                )
            }
        }
    }
}

@Composable
private fun SceneCard(ui: SceneUi, summary: String, onApply: () -> Unit, onEdit: () -> Unit) {
    val seed = Color(ui.scene.color)
    val active = ui.status == SceneStatus.ACTIVE
    val container by animateColorAsState(if (active) seed else tonalOf(seed), motion(), label = "card")
    val content = if (active) contentOn(seed) else MaterialTheme.colorScheme.onSurface
    val progress by animateFloatAsState(ui.progress ?: 0f, motion(), label = "progress")
    val running = ui.progress != null
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .clip(shape)
            .background(container)
            .then(if (ui.status == SceneStatus.MODIFIED) Modifier.border(1.5.dp, seed, shape) else Modifier)
            .drawBehind {
                if (running) drawRect(seed.copy(alpha = 0.6f), size = Size(size.width * progress, size.height))
            }
            .clickable(onClick = onApply)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (active) content.copy(alpha = 0.16f) else seed),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(glyphRes(ui.scene.glyph)),
                    contentDescription = null,
                    tint = if (active) content else contentOn(seed),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit) {
                Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.scenes_edit), tint = content)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(ui.scene.name, style = MaterialTheme.typography.titleMedium, color = content)
        Spacer(Modifier.height(4.dp))
        Text(summary, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.8f), maxLines = 3)
        when (ui.status) {
            SceneStatus.ACTIVE -> Text(stringResource(R.string.scenes_active), style = MaterialTheme.typography.labelMedium, color = content)
            SceneStatus.MODIFIED -> Text(stringResource(R.string.scene_modified), style = MaterialTheme.typography.labelMedium, color = seed)
            SceneStatus.IDLE -> Unit
        }
    }
}
