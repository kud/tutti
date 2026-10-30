package io.kud.tutti.ui.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.kud.tutti.ui.common.keyLabel
import io.kud.tutti.ui.common.holdForMenu
import androidx.compose.ui.graphics.Shape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import io.kud.tutti.ui.common.drawDownRing
import io.kud.tutti.ui.common.keyIcon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.ui.common.pressScale
import io.kud.tutti.ui.common.rememberFeedback
import io.kud.tutti.ui.common.rememberPressSource
import io.kud.tutti.ui.common.rememberTuttiIndication
import io.kud.tutti.ui.common.PinnedHeader
import io.kud.tutti.ui.common.TuttiSheet

/**
 * The rest of a BRAVIA remote, out of the way of the pad: numbers and channels, the colour
 * keys, the TV keys and quick settings. All over the remote link; no IP control. No apps: they
 * have one door, the Dock's Apps (design.md, Information architecture).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreKeysSheet(
    onKey: (Int) -> Unit,
    onDismiss: () -> Unit,
    pin: KeyPin? = null,
    downReason: String? = null,
) {
    TuttiSheet(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalKeyPin provides pin) {
            MoreKeysContent(onKey, downReason = downReason)
        }
    }
}

/**
 * Why the TV's keys don't answer, or null when its link is up (`controlLink(Owner.TV)`). Every key
 * of the sheet is a TV key, so one reason dresses them all down (design.md, Visual, Down).
 */
private val LocalDownReason = staticCompositionLocalOf<String?> { null }

/**
 * Long press on a key of the sheet pins it to the dock (design.md, gestures: a key in All keys has
 * a one-row menu, so the long press goes straight to "Pin to dock"). [enabled] false while the TV
 * is connecting or out of reach: the long press is refused with a bump, like the tap's device.
 */
class KeyPin(val enabled: Boolean, val onPin: (keyCode: Int, name: String) -> Unit)

private val LocalKeyPin = staticCompositionLocalOf<KeyPin?> { null }

/** The pin modifier for key [keyCode], or nothing when the sheet was opened without a pinner. */
@Composable
private fun Modifier.pinKey(keyCode: Int?, shape: Shape): Modifier {
    val pin = LocalKeyPin.current ?: return this
    if (keyCode == null) return this
    val feedback = rememberFeedback()
    val name = keyLabel(keyCode)
    return holdForMenu(
        label = stringResource(R.string.pin_to_dock),
        shape = shape,
        enabled = pin.enabled,
        onRefused = feedback::bump,
    ) {
        feedback.longPress()
        pin.onPin(keyCode, name)
    }
}

private val KeyHeight = 56.dp

@Composable
fun MoreKeysContent(
    onKey: (Int) -> Unit,
    modifier: Modifier = Modifier,
    downReason: String? = null,
) {
    val scroll = rememberScrollState()
    PinnedHeader(
        scrolled = scroll.canScrollBackward,
        modifier = modifier.fillMaxWidth(),
        header = {
            Text(
                stringResource(R.string.more_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp).semantics { heading() },
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            CompositionLocalProvider(LocalDownReason provides downReason) {
                // Down, the reason heads the keys, as on the playback sheet: never a dimmed grid alone.
                if (downReason != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(downReason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Section(R.string.more_section_channels)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (row in listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { digit -> Digit(digit, onKey) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Key(stringResource(R.string.more_jump), onClick = { onKey(V2Key.LAST_CHANNEL) }, code = V2Key.LAST_CHANNEL)
                            Digit(0, onKey)
                            Key(stringResource(R.string.more_live_tv), onClick = { onKey(V2Key.TV) }, code = V2Key.TV)
                        }
                    }
                    // The channel rocker, as tall as the keypad, like the remote's right-hand rocker.
                    // Each wears the channel arrow (the glyph a CH pin has) over "CH": a bare "+" read as nothing.
                    Column(
                        Modifier.weight(1f).height(KeyHeight * 4 + 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        KeyTile(
                            stringResource(R.string.more_channel_short),
                            icon = keyIcon(V2Key.CHANNEL_UP),
                            description = stringResource(R.string.more_channel_up),
                            onClick = { onKey(V2Key.CHANNEL_UP) }, code = V2Key.CHANNEL_UP,
                            height = KeyHeight * 2 + 4.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        KeyTile(
                            stringResource(R.string.more_channel_short),
                            icon = keyIcon(V2Key.CHANNEL_DOWN),
                            description = stringResource(R.string.more_channel_down),
                            onClick = { onKey(V2Key.CHANNEL_DOWN) }, code = V2Key.CHANNEL_DOWN,
                            height = KeyHeight * 2 + 4.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Section(R.string.more_section_colours)
                // Named as well as coloured, in the remote's order: a colour is never the only cue.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColourKey(R.string.more_red, Color(0xFFE5484D), V2Key.PROG_RED, onKey)
                    ColourKey(R.string.more_green, Color(0xFF30A46C), V2Key.PROG_GREEN, onKey)
                    ColourKey(R.string.more_yellow, Color(0xFFF5D90A), V2Key.PROG_YELLOW, onKey)
                    ColourKey(R.string.more_blue, Color(0xFF3E63DD), V2Key.PROG_BLUE, onKey)
                }

                Section(R.string.more_section_tv)
                // Glyph over word (Erwann, 2026-10-25: "des icônes ici", "surtout sur la partie TV"):
                // the glyph each key wears everywhere else (`keyIcon`), never without its name.
                KeyRow {
                    TvKey(stringResource(R.string.key_guide), V2Key.GUIDE, onKey)
                    TvKey(stringResource(R.string.more_info), V2Key.INFO, onKey)
                    TvKey(stringResource(R.string.key_input), V2Key.TV_INPUT, onKey)
                }
                Spacer(Modifier.height(8.dp))
                KeyRow {
                    TvKey(stringResource(R.string.more_subtitles), V2Key.CAPTIONS, onKey)
                    TvKey(stringResource(R.string.more_audio), V2Key.MEDIA_AUDIO_TRACK, onKey)
                    TvKey(stringResource(R.string.more_menu), V2Key.MENU, onKey)
                }
                Spacer(Modifier.height(8.dp))
                KeyRow {
                    TvKey(stringResource(R.string.more_quick_settings), V2Key.SETTINGS, onKey)
                }
            }
        }
    }
}

@Composable
private fun Section(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun RowScope.Digit(digit: Int, onKey: (Int) -> Unit) {
    Key(digit.toString(), onClick = { onKey(V2Key.digit(digit)) }, code = V2Key.digit(digit), big = true)
}

/** A TV key: its glyph over its name, as tall as a colour key so the two sections share a rhythm. */
@Composable
private fun RowScope.TvKey(label: String, code: Int, onKey: (Int) -> Unit) =
    KeyTile(label, { onKey(code) }, Modifier.weight(1f), icon = keyIcon(code), height = KeyHeight + 8.dp, code = code)

@Composable
private fun RowScope.Key(
    label: String,
    onClick: () -> Unit,
    code: Int? = null,
    description: String? = null,
    big: Boolean = false,
) = KeyTile(label, onClick, Modifier.weight(1f), description, big, code = code)

@Composable
private fun KeyTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    big: Boolean = false,
    height: Dp = KeyHeight,
    icon: Int? = null,
    code: Int? = null,
) {
    Column(
        modifier
            .height(height)
            .pinKey(code, KeyShape)
            .keyPress(onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides keyInk()) {
            if (icon != null) {
                // Decorative: the word under it is what TalkBack reads (design.md, Glyphs).
                Icon(painterResource(icon), contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.size(if (height > KeyHeight * 2) 28.dp else 20.dp))
                Spacer(Modifier.height(4.dp))
            }
            Text(
                label,
                style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = if (icon != null) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The ink of a key's glyph and word: onSurfaceVariant while the TV is down, as the dress asks. */
@Composable
private fun keyInk(): Color =
    if (LocalDownReason.current != null) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current

@Composable
private fun RowScope.ColourKey(@StringRes name: Int, colour: Color, keyCode: Int, onKey: (Int) -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .height(KeyHeight + 8.dp)
            .pinKey(keyCode, KeyShape)
            .keyPress { onKey(keyCode) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(width = 28.dp, height = 12.dp)
                .alpha(if (LocalDownReason.current != null) 0.38f else 1f)
                .clip(CircleShape)
                .background(colour)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(name), style = MaterialTheme.typography.labelMedium, color = keyInk())
    }
}

/**
 * A key of the sheet: the click plays on down and the key tightens, the command goes on release.
 * Its surface, shape and layer live here so every key answers alike. With the TV connecting or out
 * of reach (design.md, Visual, Down) it wears the down dress: no fill, the dashed ring, its glyph
 * and word in onSurfaceVariant; a tap bumps and sends nothing, and TalkBack hears the reason.
 */
private fun Modifier.keyPress(onClick: () -> Unit): Modifier = composed {
    val feedback = rememberFeedback()
    val reason = LocalDownReason.current
    val press = rememberPressSource { if (reason == null) feedback.click() else feedback.bump() }
    val ring = MaterialTheme.colorScheme.outline
    pressScale(press.state)
        .clip(KeyShape)
        .then(
            if (reason == null) {
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
            } else {
                Modifier.drawBehind { drawDownRing(KeyShape.createOutline(size, layoutDirection, this), ring) }
            },
        )
        .clickable(
            interactionSource = press.interaction,
            indication = rememberTuttiIndication(KeyShape),
            role = Role.Button,
            onClick = if (reason == null) onClick else ({}),
        )
        .then(if (reason != null) Modifier.semantics { stateDescription = reason } else Modifier)
}

private val KeyShape = RoundedCornerShape(18.dp)
