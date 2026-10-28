package io.kud.tutti.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.kud.tutti.ui.common.drawDownRing
import io.kud.tutti.ui.common.keyIcon
import io.kud.tutti.ui.common.keyLabel

/*
 * The playback sheet's compact chips (Iris, 2026-10-27): Captions and Audio track, which open a
 * picker on the TV. 40 dp tall, the word always shown. Voice and Night left the sheet on
 * 2026-10-28 (Erwann: « Pourquoi on a encore des settings de son ici ? »): sound lives in the
 * Sound sheet, which is their only home.
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
