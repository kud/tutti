package io.kud.tutti.quick.overlay

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.kud.tutti.MainActivity
import io.kud.tutti.R
import io.kud.tutti.quick.QuickControls
import io.kud.tutti.quick.QuickRuntime
import io.kud.tutti.quick.QuickScene
import io.kud.tutti.quick.QuickState
import io.kud.tutti.quick.overlayRail
import io.kud.tutti.quick.quickControls
import io.kud.tutti.ui.common.FeedbackPrefs
import io.kud.tutti.ui.common.LocalFeedbackPrefs
import io.kud.tutti.ui.common.rememberFeedback
import io.kud.tutti.ui.theme.TuttiTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The compact remote the Quick Settings tile opens: status, Power, volume rail,
 * mute and scene pills, in a bottom card over a dimmed scrim. Tap outside to close.
 * Phone volume keys drive the bar while it is in front (design.md § Quick access).
 */
class QuickOverlayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        QuickRuntime.start(this)
        val stateFlow: StateFlow<QuickState> = quickControls()?.state ?: MutableStateFlow(QuickState())
        setContent {
            TuttiTheme {
                val state by stateFlow.collectAsStateWithLifecycle()
                CompositionLocalProvider(LocalFeedbackPrefs provides FeedbackPrefs(state.vibration, state.keySounds)) {
                    QuickOverlay(
                        state = state,
                        act = { block -> QuickRuntime.act(this, block) },
                        onOpenApp = {
                            startActivity(
                                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            finish()
                        },
                        onDismiss = ::finish,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        quickControls()?.retain()
    }

    override fun onStop() {
        quickControls()?.release()
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> {
            QuickRuntime.act(this) { volumeUp() }
            true
        }
        KeyEvent.KEYCODE_VOLUME_DOWN -> {
            QuickRuntime.act(this) { volumeDown() }
            true
        }
        KeyEvent.KEYCODE_VOLUME_MUTE -> {
            QuickRuntime.act(this) { toggleMute() }
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun QuickOverlay(
    state: QuickState,
    act: (suspend QuickControls.() -> Unit) -> Job?,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
) {
    val feedback = rememberFeedback()
    val scope = rememberCoroutineScope()
    val enabled = state.connected

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp)
                // Swallow taps on the card so they don't reach the scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Header(
                    state = state,
                    onPower = {
                        feedback.click()
                        act { togglePower() }
                    },
                )
                Spacer(Modifier.height(12.dp))
                VolumeRow(
                    state = state,
                    enabled = enabled,
                    onStep = { up ->
                        if (overlayRail(state).atLimit(up)) feedback.bump() else feedback.tick()
                        act { if (up) volumeUp() else volumeDown() }
                    },
                    onSet = { level -> act { setVolume(level) } },
                    onMute = {
                        feedback.click()
                        act { toggleMute() }
                    },
                )
                if (state.scenes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.scenes, key = { it.id }) { scene ->
                            ScenePill(
                                scene = scene,
                                active = scene.id == state.activeSceneId,
                                modified = scene.id == state.activeSceneId && state.activeSceneModified,
                                enabled = enabled,
                                onClick = {
                                    feedback.click()
                                    scope.launch {
                                        act { applyScene(scene.id) }?.join()
                                        feedback.doublePulse()
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onOpenApp,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.quick_action_open_app))
                }
            }
        }
    }
}

@Composable
private fun Header(state: QuickState, onPower: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = statusLine(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val powerLabel = stringResource(R.string.quick_action_power)
        if (state.power) {
            FilledIconButton(onClick = onPower, enabled = state.connected, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.quick_ic_power), contentDescription = powerLabel)
            }
        } else {
            FilledTonalIconButton(onClick = onPower, enabled = state.connected, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.quick_ic_power), contentDescription = powerLabel)
            }
        }
    }
}

@Composable
private fun statusLine(state: QuickState): String {
    // Stopped at the high-volume guard: the overlay can't ask, so it says where to go.
    if (state.guardHeld && state.guardLine != null) return stringResource(R.string.quick_guard_held, state.guardLine)
    val base = when {
        !state.connected -> stringResource(R.string.quick_status_disconnected)
        !state.power -> stringResource(R.string.quick_status_off)
        else -> stringResource(R.string.quick_status_connected)
    }
    val scene = state.activeScene ?: return base
    val name = if (state.activeSceneModified) {
        stringResource(R.string.quick_scene_modified, scene.name)
    } else {
        scene.name
    }
    return "$base · $name"
}

@Composable
private fun VolumeRow(
    state: QuickState,
    enabled: Boolean,
    onStep: (up: Boolean) -> Unit,
    onSet: (Int) -> Unit,
    onMute: () -> Unit,
) {
    val feedback = rememberFeedback()
    // While dragging, the thumb follows the finger; the controller only hears the release.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val rail = overlayRail(state)
    // Null until the level is reported (and nobody is dragging): an empty rail and a dash, never 0.
    val shown = dragging ?: rail.level?.toFloat()
    val limit = rail.limit.toFloat()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onStep(false) }, enabled = enabled) {
            Icon(
                painterResource(R.drawable.quick_ic_remove),
                contentDescription = stringResource(R.string.quick_action_volume_down),
            )
        }
        val sliderLabel = stringResource(R.string.quick_volume_slider)
        val unknownLabel = stringResource(R.string.quick_volume_unknown)
        val scheme = MaterialTheme.colorScheme
        Slider(
            value = shown ?: 0f,
            onValueChange = { raw ->
                val next = raw.coerceAtMost(limit)
                if (next.roundToInt() != shown?.roundToInt()) {
                    if (raw > limit) feedback.bump() else feedback.tick()
                }
                dragging = next
            },
            onValueChangeFinished = {
                dragging?.let { onSet(it.roundToInt()) }
                dragging = null
            },
            valueRange = 0f..rail.range.toFloat(),
            enabled = enabled,
            // Unknown: no thumb and no filled part, the bare track. A drag still sets a level.
            colors = if (shown == null) {
                SliderDefaults.colors(
                    thumbColor = Color.Transparent,
                    activeTrackColor = scheme.surfaceContainerHighest,
                    inactiveTrackColor = scheme.surfaceContainerHighest,
                )
            } else {
                SliderDefaults.colors()
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
                .semantics {
                    contentDescription = sliderLabel
                    if (shown == null) stateDescription = unknownLabel
                },
        )
        IconButton(onClick = { onStep(true) }, enabled = enabled) {
            Icon(
                painterResource(R.drawable.quick_ic_add),
                contentDescription = stringResource(R.string.quick_action_volume_up),
            )
        }
        Text(
            text = shown?.roundToInt()?.toString() ?: "–",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(32.dp),
        )
        val muteLabel = stringResource(
            if (state.muted) R.string.quick_action_unmute else R.string.quick_action_mute,
        )
        if (state.muted) {
            FilledIconButton(onClick = onMute, enabled = enabled) {
                Icon(painterResource(R.drawable.quick_ic_volume_off), contentDescription = muteLabel)
            }
        } else {
            FilledTonalIconButton(onClick = onMute, enabled = enabled) {
                Icon(painterResource(R.drawable.quick_ic_volume_up), contentDescription = muteLabel)
            }
        }
    }
}

/** Active: filled in its own colour. Active then changed by hand: outlined ("modifiée"). Else tonal. */
@Composable
private fun ScenePill(
    scene: QuickScene,
    active: Boolean,
    modified: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val seed = Color(scene.color)
    val filled = active && !modified
    val container = if (filled) seed else seed.copy(alpha = 0.22f)
    val content = when {
        filled && seed.luminance() > 0.5f -> Color.Black
        filled -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
        border = if (modified) BorderStroke(2.dp, seed) else null,
    ) {
        Text(
            text = scene.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
