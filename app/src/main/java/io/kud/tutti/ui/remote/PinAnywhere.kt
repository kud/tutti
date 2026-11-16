package io.kud.tutti.ui.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import androidx.compose.ui.platform.LocalResources
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.ui.common.actionLabel
import io.kud.tutti.ui.common.rememberHaptics

/**
 * "Pin to dock" from anywhere (gesture audit item 9): the effect tiles, the bar's inputs, DRC and
 * IMAX, All keys and the TV settings all ask through one [DockPinner], which shows the same slot
 * dialog as the Apps sheet and writes the dock's own model ([DockAction]), so a pin made here is
 * any other pin: editable, movable, with its own long press.
 */
@Stable
class DockPinner internal constructor() {
    internal var pending by mutableStateOf<Pair<DockAction, String>?>(null)

    /** Asks which slot takes [action]; [name] titles the dialog ("Pin “Night”"). */
    fun request(action: DockAction, name: String) {
        pending = action to name
    }
}

@Composable
fun rememberDockPinner(state: TuttiUiState, controller: TuttiController): DockPinner {
    val pinner = remember { DockPinner() }
    val pin = rememberPinToSlot(state, controller)
    pinner.pending?.let { (action, name) ->
        PinToDockDialog(
            name = name,
            state = state,
            onPick = { slot ->
                pin(slot, action, name)
                pinner.pending = null
            },
            onDismiss = { pinner.pending = null },
        )
    }
    return pinner
}

/**
 * Writes a pin into a slot and says so: the dialog closes on the tap, and with the dock behind a
 * sheet nothing on screen changed (Erwann, 2026-10-28: no sign a pin from the Apps sheet worked).
 * The message names the slot and offers Undo, which puts back what the slot held.
 */
@Composable
fun rememberPinToSlot(state: TuttiUiState, controller: TuttiController): (slot: Int, action: DockAction, name: String) -> Unit {
    val haptics = rememberHaptics()
    val messages = LocalMessages.current
    val resources = LocalResources.current
    return { slot, action, name ->
        haptics.longPress()
        val before = state.dock.slot(slot)
        controller.assignDock(slot, action, before.secondary)
        val pages = state.dock.pages.size
        val number = slot % DockConfig.PAGE_SIZE + 1
        val text = if (pages > 1) {
            resources.getString(R.string.pin_done_page, name, number, slot / DockConfig.PAGE_SIZE + 1)
        } else {
            resources.getString(R.string.pin_done, name, number)
        }
        messages?.show(
            text,
            actionLabel = resources.getString(R.string.pin_undo),
            onAction = { controller.assignDock(slot, before.primary, before.secondary) },
        )
    }
}

/**
 * Takes an app off tutti's Apps list and says so, with Undo; Settings → Apps shows it again.
 * The TV keeps the app: this only tidies tutti's own list.
 */
@Composable
fun rememberHideApp(controller: TuttiController): (packageName: String, name: String) -> Unit {
    val haptics = rememberHaptics()
    val messages = LocalMessages.current
    val resources = LocalResources.current
    return { packageName, name ->
        haptics.select()
        controller.setAppShown(packageName, false)
        messages?.show(
            resources.getString(R.string.app_hidden, name),
            actionLabel = resources.getString(R.string.pin_undo),
            onAction = { controller.setAppShown(packageName, true) },
        )
    }
}

/** Which dock slot takes the pin; each row says what it replaces, every page under its heading. */
@Composable
fun PinToDockDialog(name: String, state: TuttiUiState, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_to_dock_title, name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val pages = state.dock.pages
                for (index in 0 until pages.size * DockConfig.PAGE_SIZE) {
                    val page = index / DockConfig.PAGE_SIZE
                    val offset = index % DockConfig.PAGE_SIZE
                    if (pages.size > 1 && offset == 0) {
                        Text(
                            stringResource(R.string.apps_pin_page, page + 1),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = if (page > 0) 8.dp else 0.dp, bottom = 2.dp),
                        )
                    }
                    val current = state.dock.slot(index).primary
                    val what = current?.let { actionLabel(it, state) } ?: stringResource(R.string.apps_slot_empty)
                    Text(
                        stringResource(R.string.apps_slot, offset + 1, what),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(index) }
                            .padding(vertical = 14.dp, horizontal = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
