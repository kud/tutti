package io.kud.tutti.ui.remote

import io.kud.tutti.ui.motion.snappyOrFade
import android.Manifest
import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.kud.tutti.R
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.ui.common.LocalChipNudge
import io.kud.tutti.ui.common.drawDownRing
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.protocol.atv.VoiceEvent
import io.kud.tutti.protocol.atv.VoiceFailure
import io.kud.tutti.protocol.atv.VoiceState
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.common.rememberProblemReporter

private enum class Hold { NONE, TALKING, CANCELLING }

/**
 * Hold to talk to the TV's assistant: press opens the mic and the TV's voice session, release
 * sends, sliding off the button cancels (a tick when crossing the edge). A tap shorter than
 * [MIN_HOLD_MS] cancels and explains the gesture. The mic closes on release, cancel, failure,
 * the 15 s cap, leaving the screen and the app going to the background.
 *
 * The microphone permission is asked on first use only, after a one-line rationale.
 */
@Composable
fun VoiceButton(
    controller: TuttiController,
    /** The TV's link isn't up (controlLink): the down dress, and a press bumps and opens nothing. */
    down: Boolean,
    /** Why, for TalkBack: "Connecting to the TV", "BRAVIA 8 unreachable". */
    downReason: String? = null,
    size: Dp = 56.dp,
) {
    val nudge = LocalChipNudge.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val voice by controller.voice.collectAsState()
    var hold by remember { mutableStateOf(Hold.NONE) }
    val reportProblem = rememberProblemReporter()
    val messages = LocalMessages.current
    var rationale by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val text = when {
            granted -> R.string.voice_hold_hint
            else -> R.string.voice_permission_denied
        }
        messages?.show(context.getString(text))
    }

    val isDown by rememberUpdatedState(down)

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (hold != Hold.NONE) {
            hold = Hold.NONE
            controller.cancelVoice()
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.cancelVoice() }
    }

    LaunchedEffect(controller) {
        controller.voiceEvents.collect { event ->
            when (event) {
                VoiceEvent.LISTENING -> haptics.tick()
                VoiceEvent.CAPPED -> {
                    haptics.bump()
                    hold = Hold.NONE
                    messages?.show(context.getString(R.string.voice_capped))
                }
                VoiceEvent.SENT, VoiceEvent.CANCELLED -> Unit
            }
        }
    }

    LaunchedEffect(voice) {
        val failed = voice as? VoiceState.Failed ?: return@LaunchedEffect
        hold = Hold.NONE
        haptics.bump()
        val text = when (failed.reason) {
            VoiceFailure.NOT_CONNECTED -> R.string.voice_not_connected
            VoiceFailure.UNSUPPORTED -> R.string.voice_unsupported
            VoiceFailure.NO_REPLY -> R.string.voice_no_reply
            VoiceFailure.DISCONNECTED -> R.string.voice_disconnected
        }
        reportProblem(context.getString(text), controller.failureDetail("voice: hold to talk", outcome = failed.reason.name))
        controller.clearVoiceFailure()
    }

    val slop = with(LocalDensity.current) { CANCEL_SLOP.toPx() }
    val scheme = MaterialTheme.colorScheme
    val talking = hold == Hold.TALKING
    val corner by animateIntAsState(if (hold != Hold.NONE) 30 else 50, snappyOrFade(), label = "corner")
    val scale by animateFloatAsState(
        when (hold) {
            Hold.TALKING -> 1.12f
            Hold.CANCELLING -> 0.92f
            Hold.NONE -> 1f
        },
        snappyOrFade(),
        label = "scale",
    )
    val container by animateColorAsState(
        if (down) Color.Transparent else when (hold) {
            Hold.TALKING -> scheme.primary
            Hold.CANCELLING -> scheme.errorContainer
            Hold.NONE -> scheme.secondaryContainer
        },
        snappyOrFade(),
        label = "container",
    )
    val content = if (down) scheme.onSurfaceVariant else when (hold) {
        Hold.TALKING -> scheme.onPrimary
        Hold.CANCELLING -> scheme.onErrorContainer
        Hold.NONE -> scheme.onSecondaryContainer
    }
    val label = stringResource(R.string.voice_button)
    val listeningLabel = stringResource(R.string.voice_listening)

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                val outline = RoundedCornerShape(percent = corner).createOutline(this.size, layoutDirection, this)
                if (down) drawDownRing(outline, scheme.outline) else drawOutline(outline, container)
            }
            .clip(RoundedCornerShape(percent = corner))
            .semantics {
                contentDescription = label
                when {
                    downReason != null -> stateDescription = downReason
                    talking -> stateDescription = listeningLabel
                }
            }
            .pointerInput(controller) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (isDown) {
                        // As every down control: a bump, the chip nudged to say why, nothing sent.
                        haptics.bump()
                        nudge.poke()
                        return@awaitEachGesture
                    }
                    if (!controller.micGranted()) {
                        rationale = true
                        return@awaitEachGesture
                    }
                    val pressedAt = SystemClock.uptimeMillis()
                    haptics.select()
                    hold = Hold.TALKING
                    controller.startVoice()
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            cancelled = true
                            break
                        }
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val p = change.position
                        val outside = p.x < -slop || p.y < -slop ||
                            p.x > this.size.width + slop || p.y > this.size.height + slop
                        val next = if (outside) Hold.CANCELLING else Hold.TALKING
                        if (next != hold && hold != Hold.NONE) {
                            haptics.tick()
                            hold = next
                        }
                        change.consume()
                    }
                    val wasCancelling = hold == Hold.CANCELLING
                    val stillHeld = hold != Hold.NONE
                    hold = Hold.NONE
                    when {
                        // Capped or failed while held: the session is already closed.
                        !stillHeld -> Unit
                        cancelled || wasCancelling -> controller.cancelVoice()
                        SystemClock.uptimeMillis() - pressedAt < MIN_HOLD_MS -> {
                            controller.cancelVoice()
                            messages?.show(context.getString(R.string.voice_hold_hint))
                        }
                        else -> {
                            haptics.click()
                            controller.finishVoice()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_mic), contentDescription = null, tint = content)
        if (hold != Hold.NONE) {
            val lift = with(LocalDensity.current) { -(size + 20.dp).roundToPx() }
            Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, lift)) {
                Text(
                    text = stringResource(if (talking) R.string.voice_listening else R.string.voice_release_to_cancel),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (talking) scheme.onPrimaryContainer else scheme.onErrorContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (talking) scheme.primaryContainer else scheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (rationale) {
        val activity = context as? Activity
        AlertDialog(
            onDismissRequest = { rationale = false },
            title = { Text(stringResource(R.string.voice_permission_title)) },
            text = { Text(stringResource(R.string.voice_permission_rationale)) },
            confirmButton = {
                TextButton(onClick = {
                    rationale = false
                    if (activity != null) permission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(stringResource(R.string.voice_permission_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { rationale = false }) { Text(stringResource(R.string.voice_permission_not_now)) }
            },
        )
    }
}

/** Below this, a press reads as a tap: nothing is sent. */
private const val MIN_HOLD_MS = 300L

/** How far past the button's edge the finger may drift before release cancels. */
private val CANCEL_SLOP = 24.dp
