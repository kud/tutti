package io.kud.tutti.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.rememberFeedback
import io.kud.tutti.ui.motion.Motion
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.atv.PairingCode

/**
 * The TV's code as a row of [PairingCode.LENGTH] cells that act as one field: one hidden
 * [BasicTextField] takes the typing, so the IME, paste and TalkBack see a single input, and the
 * cells are only drawn from its value. Hex only, uppercased ([PairingCode.normalise]); the caret
 * always sits at the end, so backspace takes the last character. The current cell wears a thicker
 * border and a caret, never colour alone. TalkBack reads "Pairing code, 3 of 6 entered".
 *
 * Motion (Iris's spec, step 3 and 4): each typed character pops 1.1 → 1 with a tick; [locked]
 * (the code is on its way) takes the cells to 70 % and stops input; a new [mismatch] count shakes
 * the row 3 × 6 dp with a reject. Reduced motion keeps the haptics and drops the pop and shake.
 */
@Composable
fun PairingCodeField(
    code: String,
    onCodeChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    mismatch: Int = 0,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val label = stringResource(R.string.pairing_code_field)
    val progress = stringResource(R.string.pairing_code_progress, code.length, PairingCode.LENGTH)
    val feedback = rememberFeedback()
    val reduced = LocalReducedMotion.current
    val shake = remember { Animatable(0f) }
    LaunchedEffect(mismatch) {
        if (mismatch == 0) return@LaunchedEffect
        feedback.reject()
        if (reduced) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(
            0f,
            keyframes {
                durationMillis = Motion.MEDIUM
                SHAKE_DP at 42
                -SHAKE_DP at 83
                SHAKE_DP at 125
                -SHAKE_DP at 167
                SHAKE_DP at 208
            },
        )
    }
    val dim by animateFloatAsState(if (locked) LOCKED_ALPHA else 1f, Motion.fade(), label = "locked")
    BasicTextField(
        value = TextFieldValue(code, selection = TextRange(code.length)),
        onValueChange = {
            val next = PairingCode.normalise(it.text)
            if (next.length > code.length) feedback.tick()
            onCodeChange(next)
        },
        readOnly = locked,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (PairingCode.isComplete(code)) onDone() }),
        modifier = modifier
            .focusRequester(focus)
            .semantics {
                contentDescription = label
                stateDescription = progress
            },
        decorationBox = {
            // The inner text field is never drawn: the cells are the field.
            // Six equal shares of whatever width the dialog gives: fixed cells overflowed a narrow one.
            Row(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(shake.value.dp.roundToPx(), 0) }
                    .graphicsLayer { alpha = dim },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (index in 0 until PairingCode.LENGTH) {
                    Cell(
                        char = code.getOrNull(index),
                        current = !locked && index == code.length,
                        pop = !reduced,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

@Composable
private fun Cell(char: Char?, current: Boolean, pop: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val scale = remember { Animatable(1f) }
    // A character arriving pops; one already there when the field appears does not.
    val seen = remember { mutableStateOf(char) }
    LaunchedEffect(char) {
        val arrived = char != null && seen.value == null
        seen.value = char
        if (arrived && pop) {
            scale.snapTo(POP_SCALE)
            scale.animateTo(1f, Motion.snappy())
        }
    }
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .widthIn(max = 48.dp)
            .height(56.dp)
            .background(colors.surfaceContainerHighest, shape)
            .border(if (current) 3.dp else 1.dp, if (current) colors.primary else colors.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            char != null -> Text(
                char.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurface,
            )
            current -> Box(Modifier.width(2.dp).height(24.dp).background(colors.primary))
        }
    }
}

private const val POP_SCALE = 1.1f
private const val LOCKED_ALPHA = 0.7f
private const val SHAKE_DP = 6f
