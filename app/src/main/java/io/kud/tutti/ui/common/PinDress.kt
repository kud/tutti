package io.kud.tutti.ui.common

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kud.tutti.platform.data.DockLabels

/**
 * How one pin is dressed for the dock's [DockLabels] (Iris, 2026-10-29). The tile itself never
 * changes, 64 dp tall at the width six across give it, so switching never moves the pad or the
 * thumb's targets; only what sits inside does.
 *
 * - [DockLabels.BOTH], the dock as it shipped: a 20 dp glyph (an app's face 24 dp) over its name on one line.
 * - [DockLabels.ICON]: the glyph alone, grown to [PIN_ICON_LARGE] (an app's face [PIN_FACE_LARGE]),
 *   centred in the room the name left.
 * - [DockLabels.NAME]: the name alone, larger, on two lines when it has two words ("Play / pause"
 *   breaks after "Play /"), one when it is one word, so a word is never split; it shrinks before it
 *   is cut. An app pin is its name too: a logo is the glyph he chose to drop.
 *
 * In every dress TalkBack hears the pin's name, which never depended on the text drawn.
 */
data class PinDress(
    val glyph: Boolean,
    val name: Boolean,
    val glyphSize: Dp,
    val faceSize: Dp,
    val nameLines: Int,
)

fun pinDress(labels: DockLabels, name: String): PinDress = when (labels) {
    DockLabels.BOTH -> PinDress(glyph = true, name = true, glyphSize = PIN_ICON, faceSize = PIN_FACE, nameLines = 1)
    DockLabels.ICON -> PinDress(glyph = true, name = false, glyphSize = PIN_ICON_LARGE, faceSize = PIN_FACE_LARGE, nameLines = 0)
    DockLabels.NAME -> PinDress(glyph = false, name = true, glyphSize = PIN_ICON, faceSize = PIN_FACE, nameLines = nameLines(name))
}

/** Two lines only when there is a space to break at: a one-word name shrinks rather than splits. */
internal fun nameLines(name: String): Int = if (name.trim().contains(' ')) 2 else 1

/** The dock's dress for every pin drawn under it; the remote provides the stored choice. */
val LocalDockLabels = staticCompositionLocalOf { DockLabels.BOTH }

/**
 * A pin's name as the only thing on its tile: label size up to 13 sp, stepping down to 10 sp
 * before an ellipsis. [description] is what TalkBack hears here (the key's meaning on the target,
 * say); null when the pin's own node already names it, so the name is never read twice.
 */
@Composable
fun PinNameOnly(name: String, lines: Int, tint: Color, description: String?, modifier: Modifier = Modifier) {
    BasicText(
        text = name,
        style = MaterialTheme.typography.labelMedium.copy(color = tint, textAlign = TextAlign.Center, hyphens = Hyphens.None),
        maxLines = lines.coerceAtLeast(1),
        softWrap = lines > 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 13.sp, stepSize = 0.5.sp),
        modifier = modifier.clearAndSetSemantics { if (description != null) contentDescription = description },
    )
}

/** An app's face beside a name, and alone in the icon dress. */
val PIN_FACE = 24.dp
val PIN_ICON_LARGE = 28.dp
val PIN_FACE_LARGE = 36.dp
