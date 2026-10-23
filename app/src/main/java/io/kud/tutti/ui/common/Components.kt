package io.kud.tutti.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kud.tutti.ui.motion.Motion
import io.kud.tutti.ui.motion.snappyOrFade

/**
 * A big round tonal key (Iris's interaction spec, round keys). On press-down, in the frame: it
 * tightens to 0.92 with its corners squarer (50 → 30 %), a 12 % layer lands, and [feedback]
 * plays; nothing waits for [onClick], which runs on release so a long press can still be told
 * apart. No ripple: the shape is the response.
 *
 * - [active] ("on") is carried by shape first: the key rests as a squircle (30 %), and the
 *   caller passes a solid or struck icon where one exists. The fill only doubles it.
 * - [unreachable]: the device this key drives can't be reached. The key stays tappable, drawn
 *   as a dashed outline with no fill and its icon kept; a tap bumps and nudges the system chip
 *   instead of sending (Erwann, 2026-10-23: no greying).
 * - With [onLongClick], a ring sweeps the edge from 150 ms to the long-press timeout and the key
 *   pops to 1.04 when it lands.
 * - Reduced motion: no scale, no corner travel, no sweep; the layer fades and the ring appears
 *   whole at the timeout. Haptics stay.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TonalRoundButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    unreachable: Boolean = false,
    feedback: FeedbackEvent = FeedbackEvent.KEY,
    size: Dp = 56.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberFeedback()
    val nudge = LocalChipNudge.current
    val reduced = LocalReducedMotion.current
    val press = rememberPress(interaction, PressStyle.KEY) { haptics.perform(if (unreachable) FeedbackEvent.BUMP else feedback) }
    val longPress = onLongClick != null && !unreachable && enabled
    val ring = rememberLongPressProgress(interaction, longPress)
    val restCorner = animateFloatAsState(if (active) ON_CORNER else REST_CORNER, if (reduced) snap() else Motion.snappy(), label = "corner")
    val pop by animateFloatAsState(if (ring.value >= 1f && !reduced) POP else 1f, Motion.snappy(), label = "pop")
    val shape = remember(press, restCorner) {
        MorphCornerShape { restCorner.value + (ON_CORNER - restCorner.value).coerceAtMost(0f) * press.fraction }
    }
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when {
            unreachable -> Color.Transparent
            active -> scheme.primary
            else -> scheme.secondaryContainer
        },
        snappyOrFade(),
        label = "container",
    )
    val content = when {
        unreachable -> scheme.onSurfaceVariant
        active -> scheme.onPrimary
        else -> scheme.onSecondaryContainer
    }
    val dashed = scheme.outline
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val scale = press.scale * pop
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.38f
            }
            .drawBehind {
                val outline = shape.createOutline(this.size, layoutDirection, this)
                if (unreachable) {
                    val dash = 4.dp.toPx()
                    drawOutline(
                        outline,
                        dashed,
                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                    )
                } else {
                    drawOutline(outline, container)
                }
            }
            .longPressRing({ ring.value }, shape, scheme.onSurface)
            .combinedClickable(
                interactionSource = interaction,
                indication = rememberTuttiIndication(shape, layerColor = content),
                enabled = enabled,
                role = Role.Button,
                onLongClickLabel = onLongClickLabel,
                onLongClick = if (longPress) onLongClick else null,
                onClick = if (unreachable) nudge::poke else onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = contentDescription, tint = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

private const val REST_CORNER = 50f
private const val ON_CORNER = 30f
private const val POP = 1.04f
