package io.kud.tutti.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.kud.tutti.ui.motion.Motion
import io.kud.tutti.ui.motion.snappyOrFade
import io.kud.tutti.ui.relief.LocalRelief
import io.kud.tutti.ui.relief.ReliefCorner
import io.kud.tutti.ui.relief.ReliefDepth
import io.kud.tutti.ui.relief.relief
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlin.math.min

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
    stateDescription: String? = null,
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
    // When the long press lands, a tag above the key names what it ran, for TAG_MS. A release
    // never cuts it short: only a fresh landing restarts it.
    var tag by remember { mutableStateOf(false) }
    val tagLabel = onLongClickLabel.takeIf { longPress }
    LaunchedEffect(ring, tagLabel) {
        if (tagLabel == null) return@LaunchedEffect
        snapshotFlow { ring.value >= 1f }.filter { it }.collectLatest {
            tag = true
            delay(TAG_MS)
            tag = false
        }
    }
    val tagAlpha by animateFloatAsState(if (tag) 1f else 0f, tween(Motion.FADE_OUT), label = "tag")
    val shape = remember(press, restCorner) {
        MorphCornerShape { restCorner.value + (ON_CORNER - restCorner.value).coerceAtMost(0f) * press.fraction }
    }
    // "3D relief": a slab of the key's own colour that the press pushes flat. An unreachable key
    // is only a dashed ring, not a thing standing there, so it stays flat.
    val relief = LocalRelief.current.takeIf { !unreachable }
    val reliefCorner = remember(press, restCorner) {
        ReliefCorner { w, h, _ -> min(w, h) * (restCorner.value + (ON_CORNER - restCorner.value).coerceAtMost(0f) * press.fraction) / 100f }
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
            .relief(relief, ReliefDepth.KEY, side = { container }, corner = reliefCorner, press = { press.fraction })
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
            )
            .then(if (stateDescription != null) Modifier.semantics { this.stateDescription = stateDescription } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = contentDescription, tint = content)
        if (tagAlpha > 0f && tagLabel != null) {
            val gap = with(LocalDensity.current) { TAG_GAP.roundToPx() }
            Popup(popupPositionProvider = remember(gap) { AboveAnchor(gap) }, properties = PopupProperties(focusable = false)) {
                ValueTag(tagLabel, Modifier.graphicsLayer { alpha = tagAlpha })
            }
        }
    }
}

/** Centred above the anchor, kept inside the window. */
private class AboveAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** How long the long-press tag stays, and its gap above the key. */
private const val TAG_MS = 1200L
private val TAG_GAP = 8.dp

/** A small label over a control: the value a gesture sets ("Vol 24"), or the action a long press ran. */
@Composable
fun ValueTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.inverseOnSurface,
        maxLines = 1,
        modifier = modifier
            .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
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
