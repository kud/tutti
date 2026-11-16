package io.kud.tutti.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.ui.common.SettingsGroup
import io.kud.tutti.ui.common.Status
import io.kud.tutti.ui.common.StatusGlyph
import io.kud.tutti.ui.onboarding.IpControlDialog

/** Each step's status in tutti's one status language (design.md › Status); its word stays its own. */
val UnlockStatus.status: Status get() = when (this) {
    UnlockStatus.DONE -> Status.OK
    UnlockStatus.TODO -> Status.NOT_YET
    UnlockStatus.ATTENTION -> Status.NEEDS_YOU
    UnlockStatus.CHECKING -> Status.WORKING
    UnlockStatus.BLOCKED, UnlockStatus.UNKNOWN -> Status.UNAVAILABLE
}

@StringRes
fun statusWord(status: UnlockStatus): Int = when (status) {
    UnlockStatus.DONE -> R.string.unlock_status_done
    UnlockStatus.TODO -> R.string.unlock_status_todo
    UnlockStatus.ATTENTION -> R.string.unlock_status_attention
    UnlockStatus.CHECKING -> R.string.unlock_status_checking
    UnlockStatus.BLOCKED -> R.string.unlock_status_blocked
    UnlockStatus.UNKNOWN -> R.string.unlock_status_unknown
}

@StringRes
fun unlockTitle(step: Unlock): Int = when (step) {
    Unlock.PAIR_TV -> R.string.unlock_pair_title
    Unlock.IP_CONTROL -> R.string.unlock_ip_title
    Unlock.REMOTE_START -> R.string.unlock_start_title
    Unlock.SOUNDBAR -> R.string.unlock_bar_title
}

@StringRes
private fun unlockGives(step: Unlock): Int = when (step) {
    Unlock.PAIR_TV -> R.string.unlock_pair_gives
    Unlock.IP_CONTROL -> R.string.unlock_ip_gives
    Unlock.REMOTE_START -> R.string.unlock_start_gives
    Unlock.SOUNDBAR -> R.string.unlock_bar_gives
}

@StringRes
private fun unlockHow(step: Unlock): Int? = when (step) {
    Unlock.PAIR_TV -> R.string.unlock_pair_how
    Unlock.IP_CONTROL -> R.string.unlock_ip_how
    Unlock.REMOTE_START -> R.string.unlock_start_how
    Unlock.SOUNDBAR -> R.string.unlock_bar_how
}

@StringRes
private fun groupTitle(device: UnlockDevice): Int = when (device) {
    UnlockDevice.TV -> R.string.unlock_group_tv
    UnlockDevice.BAR -> R.string.unlock_group_bar
}

/**
 * What tutti can do (2026-10-24): one checklist per device. Every step shows its status
 * (glyph and word) and what it unlocks; one opens at a time, on the step asked for ([focus], from
 * a nudge) or the next to do, with the exact menu path on the TV or in BRAVIA Connect and a
 * button straight into tutti's own field for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockScreen(
    state: TuttiUiState,
    controller: TuttiController,
    focus: Unlock?,
    onBack: () -> Unit,
    onPair: () -> Unit,
    onAddBar: () -> Unit,
) {
    val items = unlocks(state)
    var open by rememberSaveable { mutableStateOf(openStep(items, focus)) }
    // IP control's dialog: null closed, true straight to the code on the TV, false on the choice.
    var ipDialog by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Remote start is read from the TV's settings (getWolMode), which load only on request.
    LaunchedEffect(state.links.rest) { if (state.links.rest == Link.ONLINE) controller.loadTvSettings() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.unlock_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Text(
                stringResource(R.string.unlock_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            for (device in UnlockDevice.entries) {
                val group = items.filter { it.step.device == device }
                SettingsGroup(stringResource(groupTitle(device))) {
                    for (item in group) {
                        StepRow(
                            item = item,
                            expanded = open == item.step,
                            onToggle = { open = if (open == item.step) null else item.step },
                        ) {
                            StepBody(item, state, controller, onIp = { ipDialog = it }, onPair = onPair, onAddBar = onAddBar)
                        }
                    }
                }
            }
        }
    }
    ipDialog?.let { code -> IpControlDialog(state, controller, onDismiss = { ipDialog = null }, startWithCode = code) }
}

@Composable
private fun StepRow(item: UnlockItem, expanded: Boolean, onToggle: () -> Unit, body: @Composable () -> Unit) {
    val word = stringResource(statusWord(item.status))
    val stateLabel = stringResource(if (expanded) R.string.unlock_expanded else R.string.unlock_collapsed)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = "$word, $stateLabel" }
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDisc(item.status)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(unlockTitle(item.step)), style = MaterialTheme.typography.bodyLarge)
                Text(word, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                painterResource(R.drawable.ic_arrow_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        Text(
            stringResource(unlockGives(item.step)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 56.dp, top = 4.dp),
        )
        if (expanded) {
            Column(Modifier.padding(start = 56.dp, top = 12.dp)) { body() }
        }
    }
}

/** The step's status glyph, centred in the 40 dp slot the row's words are aligned to. */
@Composable
private fun StatusDisc(status: UnlockStatus) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { StatusGlyph(status.status) }
}

@Composable
private fun StepBody(
    item: UnlockItem,
    state: TuttiUiState,
    controller: TuttiController,
    onIp: (startWithCode: Boolean) -> Unit,
    onPair: () -> Unit,
    onAddBar: () -> Unit,
) {
    val step = item.step
    val status = item.status
    // Set up but failing: say what tutti sees now, in the link's own words.
    val now = when {
        status != UnlockStatus.ATTENTION -> null
        step == Unlock.IP_CONTROL -> linkLabel(state.links.rest)
        step == Unlock.SOUNDBAR -> linkLabel(state.links.bar)
        step == Unlock.PAIR_TV -> linkLabel(state.links.tv)
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (now != null) Note(stringResource(R.string.unlock_now, now))
        if (step == Unlock.REMOTE_START && status == UnlockStatus.UNKNOWN) Note(stringResource(R.string.unlock_start_unknown))
        if (step == Unlock.IP_CONTROL && status == UnlockStatus.DONE) {
            Note(stringResource(if (canWakeFromOff(state)) R.string.unlock_wake_ready else R.string.unlock_wake_pending))
        }
        unlockHow(step)?.let {
            Text(stringResource(R.string.unlock_how), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (step) {
                Unlock.PAIR_TV -> when (status) {
                    UnlockStatus.TODO -> Primary(R.string.unlock_pair_action, onPair)
                    UnlockStatus.ATTENTION -> Primary(R.string.unlock_pair_again, onPair)
                    else -> Unit
                }
                Unlock.IP_CONTROL -> when {
                    status == UnlockStatus.ATTENTION && ipNeedsTvPairing(state) -> Primary(R.string.unlock_pair_again, onPair)
                    else -> when (status) {
                    UnlockStatus.TODO, UnlockStatus.ATTENTION -> Primary(R.string.unlock_ip_action) { onIp(true) }
                    UnlockStatus.DONE, UnlockStatus.CHECKING -> Quiet(R.string.unlock_ip_change) { onIp(false) }
                    else -> Unit
                    }
                }
                Unlock.REMOTE_START -> if (state.ipControl && status != UnlockStatus.DONE) {
                    Quiet(R.string.unlock_start_recheck, controller::loadTvSettings)
                }
                Unlock.SOUNDBAR -> when (status) {
                    UnlockStatus.TODO -> Primary(R.string.unlock_bar_action, onAddBar)
                    UnlockStatus.ATTENTION -> Primary(R.string.unlock_bar_again, onAddBar)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Primary(@StringRes label: Int, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) { Text(stringResource(label)) }
}

@Composable
private fun Quiet(@StringRes label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(label)) }
}

