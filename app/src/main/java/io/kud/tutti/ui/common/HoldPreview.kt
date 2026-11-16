package io.kud.tutti.ui.common

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.kud.tutti.ui.motion.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The hold preview (design.md § Gestures, rule 1): a bubble means "this will happen", a ring alone
 * "this will open". For a control whose long press runs an action directly (a dock pin's
 * secondary), the bubble names that action before it fires, and its own ring tells the time.
 *
 * A tap must stay exactly what it was (Erwann, 2026-11-11): nothing is composed, shown or felt
 * before [HOLD_PREVIEW_DELAY_MS], the tap still runs on release, and the bubble never takes a
 * touch. The timing is a coroutine started when the finger lands and cancelled on the lift, on
 * Release or Cancel, or past the touch slop, the shape of [rememberLongPressProgress].
 */
@Stable
class HoldPreview internal constructor() {
    /** The bubble is up: from the delay until a lift, a slide, or 100 ms after the hold fires. */
    var shown by mutableStateOf(false)
        internal set

    /** The bubble's ring, 0..1: linear from the delay to the long-press timeout. */
    internal val progress = Animatable(0f)

    /**
     * A finger that slid past the touch slop since its last down: nothing fires for this gesture,
     * neither the tap nor the hold. Reset on the next down.
     */
    var cancelled = false
        internal set

    internal var fired = false
    internal var job: Job? = null
    internal var scope: CoroutineScope? = null
    internal var enabled = false
    internal var timeout = 500L
    internal var reduced = false

    /** The hold fired: the bubble holds full for a beat, then fades. */
    fun fire() {
        fired = true
        job?.cancel()
        if (!shown) return
        job = scope?.launch {
            progress.snapTo(1f)
            delay(HOLD_AFTER_FIRE_MS)
            shown = false
        }
    }

    /**
     * The finger is down. Timed from the touch itself, not from [PressInteraction.Press], which a
     * scrolling parent (the dock's pager) delays by the tap timeout while the long press is not:
     * the bubble would otherwise get a third of its time.
     */
    internal fun begin() {
        cancelled = false
        fired = false
        job?.cancel()
        shown = false
        if (!enabled) return
        job = scope?.launch {
            progress.snapTo(0f)
            delay(HOLD_PREVIEW_DELAY_MS)
            shown = true
            val rest = (timeout - HOLD_PREVIEW_DELAY_MS).coerceAtLeast(1L)
            // Reduced motion: the outline is whole from the start and nothing sweeps.
            if (reduced) {
                progress.snapTo(1f)
            } else {
                progress.animateTo(1f, tween(rest.toInt(), easing = LinearEasing))
            }
        }
    }

    /** Lifted or cancelled before the hold fired: the tap (if any) is the control's, the bubble fades. */
    internal fun end() {
        if (fired) return
        job?.cancel()
        shown = false
    }

    internal fun cancel() {
        cancelled = true
        end()
    }
}

/** The bubble waits as long as every ring, so there is one threshold (design.md rule 1). */
const val HOLD_PREVIEW_DELAY_MS = RING_DELAY_MS

private const val HOLD_AFTER_FIRE_MS = 100L
private const val ENTER_MS = 120
private const val LEAVE_SLACK_MS = 32L

/** [enabled] false (no direct hold, or its device's link is down): no bubble is ever shown. */
@Composable
fun rememberHoldPreview(interaction: InteractionSource, enabled: Boolean): HoldPreview {
    val preview = remember { HoldPreview() }
    preview.scope = rememberCoroutineScope()
    preview.enabled = enabled
    preview.timeout = LocalViewConfiguration.current.longPressTimeoutMillis
    preview.reduced = LocalReducedMotion.current
    LaunchedEffect(interaction, enabled) {
        if (!enabled) preview.end()
        interaction.interactions.collect { event ->
            if (event is PressInteraction.Release || event is PressInteraction.Cancel) preview.end()
        }
    }
    return preview
}

/**
 * The pin's tap and direct hold, with the preview's rules: a slide past the touch slop (or off the
 * control) cancels both, the hold runs [onHold] at the long-press timeout and swallows the release,
 * and the tap stays in [onClick], on release. TalkBack hears the hold as [holdLabel] and runs it
 * directly. The slop is watched on the Initial pass and nothing is consumed, so a pager still pages.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.holdPreviewClickable(
    preview: HoldPreview,
    interaction: MutableInteractionSource,
    indication: Indication?,
    role: Role?,
    holdLabel: String,
    onHold: () -> Unit,
    onClick: () -> Unit,
): Modifier = this
    .pointerInput(preview) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            preview.begin()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    preview.end()
                    break
                }
                if ((change.position - down.position).getDistance() > slop) {
                    preview.cancel()
                    break
                }
            }
        }
    }
    .combinedClickable(
        interactionSource = interaction,
        indication = indication,
        role = role,
        onLongClickLabel = holdLabel,
        onLongClick = {
            if (!preview.cancelled) {
                preview.fire()
                onHold()
            }
        },
        onClick = { if (!preview.cancelled) onClick() },
    )

/**
 * The bubble: [face] (24 dp, drawn in the given tint) above a one-line [label], centred 12 dp
 * above the control, in a [Popup] that is neither focusable nor touchable, so the finger already
 * down keeps its gesture and a second one passes through. Invisible to TalkBack, which already
 * hears the hold as the control's action.
 */
@Composable
fun HoldBubble(preview: HoldPreview, label: String, face: @Composable (tint: Color) -> Unit) {
    // The popup stays for the fade out, then leaves; a plain tap never reaches it.
    val shown = preview.shown
    var present by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        if (shown) {
            present = true
        } else if (present) {
            delay(Motion.FADE_OUT + LEAVE_SLACK_MS)
            present = false
        }
    }
    if (!shown && !present) return
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val position = remember(density) {
        with(density) { BubblePosition(gap = 12.dp.roundToPx(), margin = 16.dp.roundToPx(), room = BUBBLE_ROOM.roundToPx()) }
    }
    Popup(
        popupPositionProvider = position,
        properties = PopupProperties(
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val scheme = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(20.dp)
        // Starts hidden, so the first frame enters rather than appearing whole.
        val visible = remember { MutableTransitionState(false) }
        visible.targetState = shown
        AnimatedVisibility(
            visibleState = visible,
            enter = if (reduced) fadeIn(tween(ENTER_MS)) else fadeIn(tween(ENTER_MS)) + scaleIn(tween(ENTER_MS), initialScale = 0.9f),
            exit = fadeOut(tween(Motion.FADE_OUT)),
        ) {
            // The room round the bubble holds its ring (3 dp out, 3 dp wide) and its shadow.
            Box(Modifier.clearAndSetSemantics {}.padding(BUBBLE_ROOM)) {
                Column(
                    Modifier
                        .longPressRing({ preview.progress.value }, shape, scheme.onSurface)
                        .shadow(3.dp, shape)
                        .clip(shape)
                        .background(scheme.surfaceContainerHighest)
                        .border(1.dp, scheme.outline, shape)
                        .widthIn(min = 64.dp, max = 200.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { face(scheme.onSurface) }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val BUBBLE_ROOM = 8.dp

private class BubblePosition(val gap: Int, val margin: Int, val room: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        bubbleOffset(anchorBounds, windowSize, popupContentSize, gap, margin, room)
}

/**
 * Where the bubble's window goes. The window is the bubble plus [room] on every side, so the
 * bubble's own edge is what keeps [gap] from the control and [margin] from the screen. Above the
 * control, centred, when it fits; otherwise beside it, towards the middle of the screen; never
 * below, where the finger is.
 */
internal fun bubbleOffset(anchor: IntRect, window: IntSize, content: IntSize, gap: Int, margin: Int, room: Int): IntOffset {
    val minX = margin - room
    val maxX = maxOf(minX, window.width - margin + room - content.width)
    val minY = margin - room
    val maxY = maxOf(minY, window.height - margin + room - content.height)
    val above = anchor.top - gap - content.height + room
    if (above >= minY) {
        return IntOffset((anchor.center.x - content.width / 2).coerceIn(minX, maxX), above)
    }
    val x = if (anchor.center.x < window.width / 2) anchor.right + gap - room else anchor.left - gap - content.width + room
    return IntOffset(x.coerceIn(minX, maxX), (anchor.center.y - content.height / 2).coerceIn(minY, maxY))
}
