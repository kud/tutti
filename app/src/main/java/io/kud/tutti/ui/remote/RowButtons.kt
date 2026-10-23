package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.RowButton
import io.kud.tutti.platform.data.SecondaryButton
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.ui.common.Haptics
import io.kud.tutti.ui.common.TonalRoundButton

/** The icon a row button wears, in the row and in the edit mode's "add" chips. */
@DrawableRes
fun rowButtonIcon(button: RowButton, muted: Boolean = false): Int = when (button) {
    RowButton.BACK -> R.drawable.ic_back
    RowButton.HOME -> R.drawable.ic_home
    RowButton.PLAY -> R.drawable.ic_play_pause
    RowButton.VOICE -> R.drawable.ic_mic
    RowButton.KEYBOARD -> R.drawable.ic_keyboard
    RowButton.MUTE -> if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
    RowButton.INFO -> R.drawable.ic_info
    RowButton.CAPTIONS -> R.drawable.ic_subtitles
    RowButton.INPUT -> R.drawable.ic_input
    RowButton.GUIDE -> R.drawable.ic_guide
    RowButton.TV_SETTINGS -> R.drawable.ic_tune
}

/** Its name, for TalkBack and the chips. */
@Composable
fun rowButtonLabel(button: RowButton): String = stringResource(
    when (button) {
        RowButton.BACK -> R.string.action_back
        RowButton.HOME -> R.string.action_home
        RowButton.PLAY -> R.string.action_play_pause
        RowButton.VOICE -> R.string.row_voice
        RowButton.KEYBOARD -> R.string.action_keyboard
        RowButton.MUTE -> R.string.action_mute
        RowButton.INFO -> R.string.row_info
        RowButton.CAPTIONS -> R.string.row_captions
        RowButton.INPUT -> R.string.row_input
        RowButton.GUIDE -> R.string.row_guide
        RowButton.TV_SETTINGS -> R.string.row_tv_settings
    },
)

/** The three that take a long-press action of his own. */
private fun RowButton.secondary(): SecondaryButton? = when (this) {
    RowButton.BACK -> SecondaryButton.BACK
    RowButton.HOME -> SecondaryButton.HOME
    RowButton.PLAY -> SecondaryButton.PLAY
    else -> null
}

/** A plain key each extra button sends. */
private fun RowButton.key(): Int? = when (this) {
    RowButton.BACK -> V2Key.BACK
    RowButton.HOME -> V2Key.HOME
    RowButton.PLAY -> V2Key.MEDIA_PLAY_PAUSE
    RowButton.INFO -> V2Key.INFO
    RowButton.CAPTIONS -> V2Key.CAPTIONS
    RowButton.INPUT -> V2Key.TV_INPUT
    RowButton.GUIDE -> V2Key.GUIDE
    RowButton.TV_SETTINGS -> V2Key.SETTINGS
    RowButton.VOICE, RowButton.KEYBOARD, RowButton.MUTE -> null
}

/**
 * Six fill the row edge to edge exactly as it shipped; fewer spread evenly, so three buttons
 * don't hug the margins with a hole in the middle.
 */
fun rowArrangement(count: Int): Arrangement.Horizontal =
    if (count >= 6) Arrangement.SpaceBetween else Arrangement.SpaceEvenly

/** One live button of the row: its tap, its long press, its state. */
@Composable
fun LiveRowButton(
    button: RowButton,
    state: TuttiUiState,
    controller: TuttiController,
    haptics: Haptics,
    keyboardOpen: Boolean,
    onKeyboard: () -> Unit,
    onSecondary: (SecondaryButton) -> Unit,
    size: Dp,
) {
    when (button) {
        RowButton.VOICE -> VoiceButton(controller = controller, enabled = state.remote.ready, size = size)
        RowButton.KEYBOARD -> TonalRoundButton(
            icon = R.drawable.ic_keyboard,
            contentDescription = rowButtonLabel(button),
            onClick = {
                haptics.click()
                onKeyboard()
            },
            active = keyboardOpen,
            size = size,
        )
        RowButton.MUTE -> TonalRoundButton(
            icon = rowButtonIcon(button, state.muted),
            contentDescription = rowButtonLabel(button),
            onClick = {
                haptics.click()
                controller.toggleMute()
            },
            active = state.muted,
            enabled = state.bar.connected,
            size = size,
        )
        else -> {
            val key = button.key() ?: return
            val secondary = button.secondary()
            TonalRoundButton(
                icon = rowButtonIcon(button),
                contentDescription = rowButtonLabel(button),
                onClick = {
                    haptics.click()
                    controller.key(key)
                },
                onLongClick = secondary?.let { { onSecondary(it) } },
                size = size,
            )
        }
    }
}

/** Six round buttons (with the mic) must fit a 360dp phone: 6 × 52 + gaps inside 328dp. */
val ROW_BUTTON = 52.dp
