package io.kud.tutti.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.unit.dp
import io.kud.tutti.ui.motion.Motion
import kotlinx.coroutines.launch

/**
 * One indication for every pressable thing (Iris's interaction spec): state layers for pressed
 * (12 %), hovered (8 %) and dragged (16 %) in [layerColor], and a 3 dp focus ring that follows
 * [shape], 3 dp outside it or, with [ringInset], inside (full-width rows). With [ripple] the
 * Material ripple draws the press and hover layers and this adds only the ring.
 *
 * Provided as `LocalIndication` app-wide (rows: ripple and an inset ring), so hover and focus
 * come free everywhere; keys and tiles pass their own, shape-aware one.
 *
 * Reduced motion: the layer fades in over 120 ms instead of landing with the press-in. The ring
 * appears at once and leaves over 120 ms in both modes.
 */
class TuttiIndication(
    private val shape: Shape,
    private val layerColor: Color,
    private val ringColor: Color,
    private val ripple: IndicationNodeFactory? = null,
    private val ringInset: Boolean = false,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TuttiIndicationNode(interactionSource, shape, layerColor, ringColor, ringInset, ripple?.create(interactionSource))

    override fun equals(other: Any?): Boolean = other is TuttiIndication &&
        shape == other.shape && layerColor == other.layerColor && ringColor == other.ringColor &&
        ripple == other.ripple && ringInset == other.ringInset

    override fun hashCode(): Int {
        var result = shape.hashCode()
        result = 31 * result + layerColor.hashCode()
        result = 31 * result + ringColor.hashCode()
        result = 31 * result + (ripple?.hashCode() ?: 0)
        result = 31 * result + ringInset.hashCode()
        return result
    }
}

/** A [TuttiIndication] in the theme's colours: the ring is always `onSurface`. */
@Composable
fun rememberTuttiIndication(
    shape: Shape,
    layerColor: Color = MaterialTheme.colorScheme.onSurface,
    ripple: Boolean = false,
    ringInset: Boolean = false,
): TuttiIndication {
    val ringColor = MaterialTheme.colorScheme.onSurface
    return remember(shape, layerColor, ringColor, ripple, ringInset) {
        TuttiIndication(shape, layerColor, ringColor, if (ripple) ripple() else null, ringInset)
    }
}

private class TuttiIndicationNode(
    private val interactionSource: InteractionSource,
    private val shape: Shape,
    private val layerColor: Color,
    private val ringColor: Color,
    private val ringInset: Boolean,
    rippleNode: DelegatableNode?,
) : DelegatingNode(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val drawsLayer = rippleNode == null
    private val layer = Animatable(0f)
    private val ring = Animatable(0f)

    init {
        rippleNode?.let { delegate(it) }
    }

    override fun onAttach() {
        coroutineScope.launch {
            val presses = mutableListOf<PressInteraction.Press>()
            val hovers = mutableListOf<HoverInteraction.Enter>()
            val focuses = mutableListOf<FocusInteraction.Focus>()
            val drags = mutableListOf<DragInteraction.Start>()
            interactionSource.interactions.collect { interaction: Interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                    is HoverInteraction.Enter -> hovers += interaction
                    is HoverInteraction.Exit -> hovers -= interaction.enter
                    is FocusInteraction.Focus -> focuses += interaction
                    is FocusInteraction.Unfocus -> focuses -= interaction.focus
                    is DragInteraction.Start -> drags += interaction
                    is DragInteraction.Stop -> drags -= interaction.start
                    is DragInteraction.Cancel -> drags -= interaction.start
                }
                val reduced = currentValueOf(LocalReducedMotion)
                if (drawsLayer) {
                    val target = when {
                        drags.isNotEmpty() -> DRAGGED
                        presses.isNotEmpty() -> PRESSED
                        hovers.isNotEmpty() -> HOVERED
                        else -> 0f
                    }
                    val rising = target > layer.targetValue
                    val duration = if (rising && !reduced && presses.isNotEmpty()) Motion.PRESS_IN else Motion.FADE_OUT
                    launch { layer.animateTo(target, tween(duration)) }
                }
                val focused = focuses.isNotEmpty()
                launch { ring.animateTo(if (focused) 1f else 0f, if (focused) snap() else tween(Motion.FADE_OUT)) }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = layer.value
        if (alpha > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), layerColor.copy(alpha = alpha))
        val focus = ring.value
        if (focus <= 0f) return
        val width = RING.toPx()
        val offset = if (ringInset) -width / 2f else RING.toPx() + width / 2f
        val ringSize = Size(size.width + offset * 2f, size.height + offset * 2f)
        translate(-offset, -offset) {
            drawOutline(shape.createOutline(ringSize, layoutDirection, this), ringColor.copy(alpha = focus), style = Stroke(width))
        }
    }

    private companion object {
        const val PRESSED = 0.12f
        const val HOVERED = 0.08f
        const val DRAGGED = 0.16f
        val RING = 3.dp
    }
}
