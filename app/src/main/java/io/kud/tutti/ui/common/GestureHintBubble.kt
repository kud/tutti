package io.kud.tutti.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.ui.motion.Motion

/**
 * The one-time hint for a hidden gesture (discoverability rule 3). [offer] asks the controller,
 * which says yes at most once per gesture ever and never twice the same day, and records it as
 * shown on the spot: showing is spending, so closing the bubble is the only thing left to do.
 */
@Stable
class GestureHintState internal constructor(private val take: (String) -> Boolean) {
    var showing by mutableStateOf<GestureEntry?>(null)
        private set

    /** Shows gesture [id]'s hint if it has never shown and none showed today. */
    fun offer(id: String) {
        if (showing != null) return
        val entry = Gestures.byId(id) ?: return
        if (take(id)) showing = entry
    }

    fun dismiss() {
        showing = null
    }
}

@Composable
fun rememberGestureHint(controller: TuttiController): GestureHintState =
    remember(controller) { GestureHintState(controller::takeHint) }

/**
 * The hint itself: quiet (a tonal card with an outline, no accent, no motion past a fade), named
 * in words ("Tip", then the gesture's own title and sentence from the registry, never a colour or
 * an icon alone), closed by a 48 dp ✕, and read once by TalkBack as a polite live region.
 */
@Composable
fun GestureHintBubble(hint: GestureHintState, modifier: Modifier = Modifier, only: String? = null) {
    // [only]: this slot shows that gesture's hint alone, so a hint sits beside the control it names.
    val shown = hint.showing?.takeIf { only == null || it.id == only }
    // Kept through the fade out, so the text doesn't vanish before the card does.
    var last by remember { mutableStateOf<GestureEntry?>(null) }
    shown?.let { last = it }
    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn(tween(Motion.MEDIUM)),
        exit = fadeOut(tween(Motion.FADE_OUT)),
        modifier = modifier,
    ) {
        val entry = last ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(16.dp)
        val scheme = MaterialTheme.colorScheme
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(scheme.surfaceContainerHighest, shape)
                .border(1.dp, scheme.outlineVariant, shape)
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                painterResource(R.drawable.ic_info),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(top = 1.dp)) {
                Text(
                    stringResource(R.string.hint_title, stringResource(entry.title)),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                )
                Text(
                    stringResource(entry.description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = hint::dismiss, modifier = Modifier.padding(end = 2.dp)) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.hint_close), modifier = Modifier.size(20.dp))
            }
        }
    }
}
