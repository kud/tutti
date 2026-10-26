package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
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
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.ui.common.TonalRoundButton
import io.kud.tutti.ui.common.actionLabel

/** The icon a row button wears, in the row and in the edit mode's "add" chips. */
@DrawableRes
fun rowButtonIcon(button: RowButton, muted: Boolean = false): Int = when (button) {
    RowButton.BACK -> R.drawable.ic_tv_back
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

/** The four that take a long-press action of his own. */
private fun RowButton.secondary(): SecondaryButton? = when (this) {
    RowButton.BACK -> SecondaryButton.BACK
    RowButton.HOME -> SecondaryButton.HOME
    RowButton.PLAY -> SecondaryButton.PLAY
    RowButton.INPUT -> SecondaryButton.INPUT
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
    /** A TV field is open behind the input view he left: the Keyboard key wears its "on" look. */
    fieldWaiting: Boolean,
    onKeyboard: () -> Unit,
    onSecondary: (SecondaryButton) -> Unit,
    size: Dp,
    /** Where a key goes: the remote's target router ([RemoteScreen]); the TV by default. */
    onKey: (Int) -> Unit = controller::key,
    /** The key is down for the target (a TV-only key while the computer is targeted): bumps, says [keyDownReason]. */
    keyDown: (Int) -> Boolean = { false },
    keyDownReason: String? = null,
    /** What the key means on the target, for TalkBack ("Escape on the computer"); null keeps the TV's. */
    keyMeaning: (Int) -> String? = { null },
    /** Input's tap: tutti's own input chooser ([TvInputSheet]). */
    onInputs: () -> Unit = {},
) {
    // One rule for every control (controlLink): a TV key goes down with the TV's link, Mute with
    // the bar's, connecting and out of reach alike, and a down key bumps and sends nothing.
    val tv = state.controlLink(Owner.TV)
    val tvReason = controlLinkReason(tv, Owner.TV, state)
    val clipboard = LocalClipboardManager.current
    val messages = LocalMessages.current
    val pasteEmpty = stringResource(R.string.keyboard_long_paste_empty)
    val pasteNoField = stringResource(R.string.keyboard_long_paste_no_field)
    val pasteDone = stringResource(R.string.keyboard_long_paste_done)
    when (button) {
        RowButton.VOICE -> VoiceButton(controller = controller, down = tv.down, downReason = tvReason, size = size)
        RowButton.KEYBOARD -> TonalRoundButton(
            icon = R.drawable.ic_keyboard,
            contentDescription = rowButtonLabel(button),
            onClick = onKeyboard,
            // Long press pastes the phone's clipboard into the TV's open field, without the view.
            onLongClick = {
                when (val outcome = paste(clipboard.getText()?.text, state.keyboardField?.text)) {
                    Paste.Empty -> {
                        haptics.reject()
                        messages?.show(pasteEmpty)
                    }
                    Paste.NoField -> {
                        haptics.reject()
                        messages?.show(pasteNoField)
                    }
                    is Paste.Send -> {
                        haptics.longPress()
                        controller.text(outcome.text)
                        messages?.show(pasteDone)
                    }
                }
            },
            onLongClickLabel = stringResource(R.string.keyboard_long_paste),
            // A field remembered from before a drop is not a field now: never "on" while the TV is down.
            active = fieldWaiting && !tv.down,
            unreachable = tv.down,
            size = size,
            stateDescription = tvReason,
        )
        RowButton.MUTE -> {
            val bar = state.controlLink(Owner.BAR)
            // A remembered mute is not shown once the bar has gone: the reducer keeps the last value.
            val muted = state.muted && !bar.down
            TonalRoundButton(
                icon = rowButtonIcon(button, muted),
                // Like the rail's speaker: the label says what a tap does, the state what is true now.
                contentDescription = stringResource(if (muted) R.string.volume_unmute else R.string.volume_mute),
                onClick = { controller.toggleMute() },
                active = muted,
                unreachable = bar.down,
                size = size,
                stateDescription = controlLinkReason(bar, Owner.BAR, state) ?: if (muted) stringResource(R.string.volume_muted) else null,
            )
        }
        // Input is a door, like Apps (Erwann, 2026-10-26): a tap opens tutti's input chooser, which
        // says in words why it can't switch, so the key never wears the down dress and always opens.
        // Its long press is a key's, the TV's own input menu by default (RowGestures), and like every
        // TV key's it is refused with a bump and the reason while the TV's link is down.
        RowButton.INPUT -> {
            val secondary = SecondaryButton.INPUT
            TonalRoundButton(
                icon = rowButtonIcon(button),
                contentDescription = rowButtonLabel(button),
                onClick = onInputs,
                onLongClick = {
                    if (tv.down) {
                        haptics.bump()
                        tvReason?.let { messages?.show(it) }
                    } else {
                        onSecondary(secondary)
                    }
                },
                onLongClickLabel = state.dock.longPress[secondary]?.let { actionLabel(it, state) }
                    ?: rowDefault(secondary)?.let { stringResource(it.label) },
                size = size,
            )
        }
        else -> {
            val key = button.key() ?: return
            val secondary = button.secondary()
            TonalRoundButton(
                icon = rowButtonIcon(button),
                contentDescription = listOfNotNull(rowButtonLabel(button), keyMeaning(key), keyDownReason.takeIf { keyDown(key) }).joinToString(", "),
                onClick = { onKey(key) },
                unreachable = tv.down || keyDown(key),
                onLongClick = secondary?.let { { onSecondary(it) } },
                // Names the second action: read aloud by TalkBack, and tagged over the key when it lands.
                // His own action when he chose one, else the key's default (RowGestures).
                onLongClickLabel = secondary?.let { which ->
                    state.dock.longPress[which]?.let { actionLabel(it, state) }
                        ?: rowDefault(which)?.let { stringResource(it.label) }
                },
                size = size,
                stateDescription = tvReason,
            )
        }
    }
}

/** Six round buttons (with the mic) must fit a 360dp phone: 6 × 52 + gaps inside 328dp. */
val ROW_BUTTON = 52.dp
