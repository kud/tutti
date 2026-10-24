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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
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
) {
    TuttiSheet(onDismissRequest = onDismiss) {
        MoreKeysContent(onKey)
    }
}

private val KeyHeight = 56.dp

@Composable
fun MoreKeysContent(
    onKey: (Int) -> Unit,
    modifier: Modifier = Modifier,
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

            Section(R.string.more_section_channels)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { digit -> Digit(digit, onKey) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Key(stringResource(R.string.more_jump), onClick = { onKey(V2Key.LAST_CHANNEL) })
                        Digit(0, onKey)
                        Key(stringResource(R.string.more_live_tv), onClick = { onKey(V2Key.TV) })
                    }
                }
                // The channel rocker, as tall as the keypad, like the remote's right-hand rocker.
                Column(
                    Modifier.weight(1f).height(KeyHeight * 4 + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KeyTile(
                        "+",
                        description = stringResource(R.string.more_channel_up),
                        onClick = { onKey(V2Key.CHANNEL_UP) },
                        height = KeyHeight * 2 + 4.dp,
                        caption = stringResource(R.string.more_channel_short),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    KeyTile(
                        "−",
                        description = stringResource(R.string.more_channel_down),
                        onClick = { onKey(V2Key.CHANNEL_DOWN) },
                        height = KeyHeight * 2 + 4.dp,
                        caption = stringResource(R.string.more_channel_short),
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
            KeyRow {
                Key(stringResource(R.string.key_guide), onClick = { onKey(V2Key.GUIDE) })
                Key(stringResource(R.string.more_info), onClick = { onKey(V2Key.INFO) })
                Key(stringResource(R.string.key_input), onClick = { onKey(V2Key.TV_INPUT) })
            }
            Spacer(Modifier.height(8.dp))
            KeyRow {
                Key(stringResource(R.string.more_subtitles), onClick = { onKey(V2Key.CAPTIONS) })
                Key(stringResource(R.string.more_audio), onClick = { onKey(V2Key.MEDIA_AUDIO_TRACK) })
                Key(stringResource(R.string.more_menu), onClick = { onKey(V2Key.MENU) })
            }
            Spacer(Modifier.height(8.dp))
            KeyRow {
                Key(stringResource(R.string.more_quick_settings), onClick = { onKey(V2Key.SETTINGS) })
            }

        }
    }
}

@Composable
private fun Section(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun RowScope.Digit(digit: Int, onKey: (Int) -> Unit) {
    Key(digit.toString(), onClick = { onKey(V2Key.digit(digit)) }, big = true)
}

@Composable
private fun RowScope.Key(
    label: String,
    onClick: () -> Unit,
    description: String? = null,
    big: Boolean = false,
) = KeyTile(label, onClick, Modifier.weight(1f), description, big)

@Composable
private fun KeyTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    big: Boolean = false,
    height: Dp = KeyHeight,
    caption: String? = null,
) {
    Column(
        modifier
            .height(height)
            .keyPress(onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RowScope.ColourKey(@StringRes name: Int, colour: Color, keyCode: Int, onKey: (Int) -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .height(KeyHeight + 8.dp)
            .keyPress { onKey(keyCode) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(width = 28.dp, height = 12.dp)
                .clip(CircleShape)
                .background(colour)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(name), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * A key of the sheet: the click plays on down and the key tightens, the command goes on release.
 * Its surface, shape and layer live here so every key answers alike.
 */
private fun Modifier.keyPress(onClick: () -> Unit): Modifier = composed {
    val feedback = rememberFeedback()
    val press = rememberPressSource { feedback.click() }
    pressScale(press.state)
        .clip(KeyShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(
            interactionSource = press.interaction,
            indication = rememberTuttiIndication(KeyShape),
            role = Role.Button,
            onClick = onClick,
        )
}

private val KeyShape = RoundedCornerShape(18.dp)
