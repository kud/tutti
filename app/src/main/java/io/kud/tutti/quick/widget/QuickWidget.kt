package io.kud.tutti.quick.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.kud.tutti.MainActivity
import io.kud.tutti.R
import io.kud.tutti.quick.QuickRuntime
import io.kud.tutti.quick.QuickScene
import io.kud.tutti.quick.QuickState
import io.kud.tutti.quick.quickControls
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Home-screen widget: connection status, scene pills, volume −/+ and mute.
 * GlanceTheme's default colours are dynamic on Android 12+ and follow the system
 * night mode; the layout is drawn for dark first (tonal surfaces, no outlines).
 */
class QuickWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, MEDIUM, FULL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        QuickRuntime.start(context)
        val stateFlow = context.quickControls()?.state ?: MutableStateFlow(QuickState())
        provideContent {
            val state by stateFlow.collectAsState()
            GlanceTheme {
                QuickWidgetContent(state)
            }
        }
    }

    companion object {
        /** Volume row only. */
        val COMPACT = DpSize(180.dp, 56.dp)

        /** Status + volume. */
        val MEDIUM = DpSize(180.dp, 110.dp)

        /** Status + scene pills + volume. */
        val FULL = DpSize(250.dp, 170.dp)
    }
}

class QuickWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickWidget()
}

@Composable
private fun QuickWidgetContent(state: QuickState) {
    val size = LocalSize.current
    val showStatus = size.height >= QuickWidget.MEDIUM.height
    val showScenes = size.height >= QuickWidget.FULL.height && state.scenes.isNotEmpty()

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(24.dp)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showStatus) {
            StatusRow(state)
            Spacer(GlanceModifier.height(10.dp))
        }
        if (showScenes) {
            SceneRow(state, maxPills = ((size.width.value - 24f) / 84f).toInt().coerceIn(1, 4))
            Spacer(GlanceModifier.height(10.dp))
        }
        VolumeRow(state)
    }
}

@Composable
private fun StatusRow(state: QuickState) {
    val context = LocalContext.current
    val status = when {
        !state.connected -> context.getString(R.string.quick_status_disconnected)
        !state.power -> context.getString(R.string.quick_status_off)
        state.muted -> context.getString(R.string.quick_status_muted)
        else -> context.getString(R.string.quick_status_connected)
    }
    val scene = state.activeScene?.let {
        if (state.activeSceneModified) context.getString(R.string.quick_scene_modified, it.name) else it.name
    }
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .size(8.dp)
                .cornerRadius(4.dp)
                .background(if (state.connected) GlanceTheme.colors.primary else GlanceTheme.colors.outline),
        ) {}
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = if (scene != null) "$status · $scene" else status,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun SceneRow(state: QuickState, maxPills: Int) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        state.scenes.take(maxPills).forEachIndexed { index, scene ->
            if (index > 0) Spacer(GlanceModifier.width(6.dp))
            ScenePill(
                scene = scene,
                active = scene.id == state.activeSceneId,
                modified = scene.id == state.activeSceneId && state.activeSceneModified,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
    }
}

/** No border modifier in Glance: "modifiée" shows in the status line, the pill stays filled. */
@Composable
private fun ScenePill(scene: QuickScene, active: Boolean, modifier: GlanceModifier, modified: Boolean = false) {
    val seed = Color(scene.color)
    // Changed by hand since it ran: no longer "active", so it loses the fill and the tick and says so,
    // as the remote's pill does (outline and "modified").
    val full = active && !modified
    val fill = if (full) seed else seed.copy(alpha = 0.28f)
    val context = LocalContext.current
    val text = when {
        full && seed.luminance() > 0.5f -> ColorProvider(Color.Black)
        full -> ColorProvider(Color.White)
        else -> GlanceTheme.colors.onSurface
    }
    val described = when {
        modified -> context.getString(R.string.quick_scene_modified, scene.name)
        active -> context.getString(R.string.quick_scene_active, scene.name)
        else -> scene.name
    }
    Box(
        modifier = modifier
            .height(36.dp)
            .cornerRadius(18.dp)
            .background(fill)
            .semantics { contentDescription = described }
            .clickable(
                actionRunCallback<QuickWidgetAction>(
                    actionParametersOf(
                        QuickWidgetAction.Op to QuickWidgetAction.OP_SCENE,
                        QuickWidgetAction.SceneId to scene.id,
                    ),
                ),
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // Active wears a tick as well as the full fill: never told by colour alone.
            text = when {
                modified -> described
                full -> "\u2713 ${scene.name}"
                else -> scene.name
            },
            style = TextStyle(color = text, fontSize = 13.sp, fontWeight = if (full) FontWeight.Bold else FontWeight.Medium),
            maxLines = 1,
        )
    }
}

@Composable
private fun VolumeRow(state: QuickState) {
    val context = LocalContext.current
    val enabled = state.connected
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            imageProvider = ImageProvider(R.drawable.quick_ic_remove),
            contentDescription = context.getString(R.string.quick_action_volume_down),
            onClick = op(QuickWidgetAction.OP_VOLUME_DOWN),
            enabled = enabled,
        )
        Text(
            // Unknown until reported, never a made-up 0 (design.md, Quick access).
            text = state.level?.takeIf { state.connected }?.level?.toString() ?: "–",
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
        CircleIconButton(
            imageProvider = ImageProvider(R.drawable.quick_ic_add),
            contentDescription = context.getString(R.string.quick_action_volume_up),
            onClick = op(QuickWidgetAction.OP_VOLUME_UP),
            enabled = enabled,
        )
        Spacer(GlanceModifier.width(6.dp))
        CircleIconButton(
            imageProvider = ImageProvider(
                if (state.muted) R.drawable.quick_ic_volume_off else R.drawable.quick_ic_volume_up,
            ),
            contentDescription = context.getString(
                if (state.muted) R.string.quick_action_unmute else R.string.quick_action_mute,
            ),
            onClick = op(QuickWidgetAction.OP_MUTE),
            enabled = enabled,
            backgroundColor = if (state.muted) GlanceTheme.colors.primary else GlanceTheme.colors.secondaryContainer,
            contentColor = if (state.muted) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSecondaryContainer,
        )
    }
}

private fun op(op: String) = actionRunCallback<QuickWidgetAction>(actionParametersOf(QuickWidgetAction.Op to op))
