package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.PressStyle
import io.kud.tutti.ui.common.drawDownRing
import io.kud.tutti.ui.common.featureLabel
import io.kud.tutti.ui.common.holdForMenu
import io.kud.tutti.ui.common.keyIcon
import io.kud.tutti.ui.common.keyLabel
import io.kud.tutti.ui.common.rememberFeedback
import io.kud.tutti.ui.common.rememberPress
import io.kud.tutti.ui.motion.Motion
import io.kud.tutti.ui.motion.refusalShake

/*
 * The playback sheet's compact chips (Iris, 2026-10-27, from Erwann's « Genre Voice etc, on peut
 * les virer ou au moins gagner de la place »): Captions and Audio track, which open a picker on
 * the TV, and the bar's Voice and Night, which switch. All 40 dp tall, the word always shown.
 * The Sound sheet stays Voice and Night's full home, with their explanations.
 */

/** The chips' height: under the 56 dp keys, so the transport row stays the sheet's lead. */
private val CHIP_HEIGHT = 40.dp
private val CHIP_SHAPE = RoundedCornerShape(20.dp)
private val CHIP_GAP = 8.dp

/**
 * One row of chips when they all fit, each widened by an equal share of the room left; else rows
 * of two, each chip half the width, so a narrow phone or a longer language (French) gets a tidy
 * 2 × 2 and never one chip orphaned on a second row.
 */
@Composable
internal fun PlaybackChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = CHIP_GAP.roundToPx()
        val width = constraints.maxWidth
        val natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val fits = natural.sum() + gap * (measurables.size - 1).coerceAtLeast(0) <= width
        val rows: List<List<Int>> = if (fits) listOf(measurables.indices.toList()) else measurables.indices.chunked(2)
        val placeables = arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
        for (row in rows) {
            val free = width - gap * (row.size - 1)
            val widths = if (fits) {
                val slack = (free - row.sumOf { natural[it] }) / row.size
                row.map { natural[it] + slack }
            } else {
                row.map { if (row.size == 1) width else free / 2 }
            }
            row.forEachIndexed { i, index ->
                placeables[index] = measurables[index].measure(Constraints.fixedWidth(widths[i].coerceAtLeast(0)))
            }
        }
        val heights = rows.map { row -> row.maxOf { placeables[it]!!.height } }
        val total = heights.sum() + gap * (rows.size - 1).coerceAtLeast(0)
        layout(width, total) {
            var y = 0
            rows.forEachIndexed { r, row ->
                var x = 0
                for (index in row) {
                    val p = placeables[index]!!
                    p.placeRelative(x, y + (heights[r] - p.height) / 2)
                    x += p.width + gap
                }
                y += heights[r] + gap
            }
        }
    }
}

/**
 * Captions or Audio track: glyph and word, no ring or check, because a press opens a picker on
 * the TV rather than switching something here. Down: the dashed ring, no fill, TalkBack hearing why.
 */
@Composable
internal fun KeyChip(code: Int, downReason: String?, onKey: (Int) -> Unit) {
    val label = keyLabel(code)
    val down = downReason != null
    val scheme = MaterialTheme.colorScheme
    val ink = if (down) scheme.onSurfaceVariant else scheme.onSurface
    val ring = scheme.outline
    Row(
        Modifier
            .heightIn(min = CHIP_HEIGHT)
            .clip(CHIP_SHAPE)
            .then(
                if (down) {
                    Modifier.drawBehind { drawDownRing(CHIP_SHAPE.createOutline(size, layoutDirection, this), ring) }
                } else {
                    Modifier.background(scheme.surfaceContainerHigh, CHIP_SHAPE)
                },
            )
            .clickable(enabled = !down, role = Role.Button, onClickLabel = label) { onKey(code) }
            .semantics(mergeDescendants = true) {
                contentDescription = label
                downReason?.let { stateDescription = it }
            }
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(keyIcon(code)), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Voice or Night as a toggle chip. On: filled in the accent with a check. Off: the plain dress with
 * an empty ring. Down (the bar out of reach): the dashed ring, the empty ring in onSurfaceVariant,
 * a tap bumps. The word never changes; On, Off or why it is down goes to TalkBack as the state. A
 * refusal shakes the chip and swaps the mark for a warning for 3 s ("Didn't take" to TalkBack).
 * The state changes by opacity alone, on and off drawn as two layers that cross-fade, which is
 * also the reduced-motion form. A long press pins it to the dock, as on the Sound sheet.
 */
@Composable
internal fun BarToggleChip(state: TuttiUiState, feature: TheatreFeature, onTap: () -> Unit, onPin: (String) -> Unit) {
    val status = rememberBarToggleStatus(state, feature)
    val down = status.reason != null
    val lit = status.on && !down
    val scheme = MaterialTheme.colorScheme
    val litAlpha by animateFloatAsState(if (lit) 1f else 0f, Motion.fade(), label = "chip")
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPress(interaction, PressStyle.TILE)
    val feedback = rememberFeedback()
    val name = stringResource(featureLabel(feature))
    val hint = explanation(feature)?.let { stringResource(it) }
    val ring = scheme.outline
    Box(
        Modifier
            .heightIn(min = CHIP_HEIGHT)
            .refusalShake(status.shake)
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
            }
            .clip(CHIP_SHAPE)
            .holdForMenu(stringResource(R.string.pin_to_dock), CHIP_SHAPE, enabled = !down, onRefused = feedback::bump) { onPin(name) }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
                onClick = { if (!down) onTap() else feedback.bump() },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = withHint(name, hint)
                stateDescription = status.stateLine
            },
        contentAlignment = Alignment.Center,
    ) {
        // Off (or down) underneath, on over it: one fades into the other, nothing moves.
        ToggleChipLook(
            name = name,
            mark = if (status.failed && !down) R.drawable.ic_warning else R.drawable.ic_circle_outline,
            ink = if (down) scheme.onSurfaceVariant else scheme.onSurface,
            modifier = Modifier
                .alpha(1f - litAlpha)
                .then(
                    if (down) {
                        Modifier.drawBehind { drawDownRing(CHIP_SHAPE.createOutline(size, layoutDirection, this), ring) }
                    } else {
                        Modifier.background(scheme.surfaceContainerHigh, CHIP_SHAPE)
                    },
                ),
            spinner = status.spinner && !lit,
        )
        ToggleChipLook(
            name = name,
            mark = R.drawable.ic_check_circle,
            ink = scheme.onPrimary,
            modifier = Modifier.alpha(litAlpha).background(scheme.primary, CHIP_SHAPE),
            spinner = status.spinner && lit,
        )
    }
}

@Composable
private fun ToggleChipLook(name: String, @DrawableRes mark: Int, ink: Color, modifier: Modifier, spinner: Boolean) {
    Row(
        modifier.heightIn(min = CHIP_HEIGHT).padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(mark), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            if (spinner) {
                // A status, not decoration: static under reduced motion, like the tile's.
                val arc = Modifier.requiredSize(24.dp)
                if (LocalReducedMotion.current) {
                    CircularProgressIndicator(progress = { 0.75f }, modifier = arc, color = ink, strokeWidth = 1.5.dp, trackColor = Color.Transparent)
                } else {
                    CircularProgressIndicator(modifier = arc, color = ink, strokeWidth = 1.5.dp, trackColor = Color.Transparent)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
