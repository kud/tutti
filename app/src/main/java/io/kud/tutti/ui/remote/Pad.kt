package io.kud.tutti.ui.remote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.platform.data.PadMode
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.ui.common.DOWN_STROKE
import io.kud.tutti.ui.common.FeedbackEvent
import io.kud.tutti.ui.common.LocalChipNudge
import io.kud.tutti.ui.common.drawDownRing
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.RING_DELAY_MS
import io.kud.tutti.ui.common.ValueTag
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.intro.LocalIntroAnchors
import io.kud.tutti.ui.intro.TouchpadAnchor
import io.kud.tutti.ui.motion.Motion
import io.kud.tutti.ui.motion.snappyOrFade
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The pad: one large quiet surface, no grid lines. Touchpad (swipe steps) or D-pad (a disc with
 * a chevron at each point and an OK centre); [PadModeSwitch], a quiet glyph in its top corner facing the rail
 * ([switchOnLeft]: top-left when the pad stands on the right), switches in one tap.
 * Tap = OK; long press = held Select for the TV.
 * A held direction repeats on the [KeyRepeat] schedule, on the D-pad and under a finger resting after a swipe step.
 * Two gestures only, in touchpad mode: two-finger tap = play/pause, two-finger vertical drag = bar volume.
 *
 * The halo follows the finger and steps leave no mark (Erwann, 2026-10-24: the halo stays, the
 * step dot goes); a two-finger step shows a "Vol 24" tag at the top, from [volume] and [volumeCap], ticking inside the range (the walls are
 * the controller's bump).
 */
@Composable
fun Pad(
    mode: PadMode,
    onKey: (Int) -> Unit,
    onLongSelect: () -> Unit,
    onPlayPause: () -> Unit,
    onVolumeStep: (Int) -> Unit,
    /** Null where the pad's mode is fixed (the input view's D-pad): no switch is drawn or hit. */
    onModeChange: ((PadMode) -> Unit)?,
    modifier: Modifier = Modifier,
    volume: Int? = null,
    volumeCap: Int? = null,
    /** The switch's corner: top-left for a right-hand layout (the rail on the left), else top-right. */
    switchOnLeft: Boolean = false,
    /**
     * The TV's link isn't up (controlLink): the surface stays, a dashed edge runs round it, the
     * D-pad face and the switch dim, and any touch bumps and nudges the chip; nothing is sent.
     */
    down: Boolean = false,
    /** Why, for TalkBack: "Connecting to the TV", "BRAVIA 8 unreachable". */
    downReason: String? = null,
) {
    val haptics = rememberHaptics()
    val nudge = LocalChipNudge.current
    val scope = rememberCoroutineScope()
    val glowAlpha = remember { Animatable(0f) }
    var glow by remember { mutableStateOf(Offset.Unspecified) }
    val key by rememberUpdatedState(onKey)
    val longSelect by rememberUpdatedState(onLongSelect)
    val playPause by rememberUpdatedState(onPlayPause)
    val volumeStep by rememberUpdatedState(onVolumeStep)
    val currentVolume by rememberUpdatedState(volume)
    val currentCap by rememberUpdatedState(volumeCap)
    var tagVisible by remember { mutableStateOf(false) }
    var tagLevel by remember { mutableIntStateOf(0) }
    var tagJob by remember { mutableStateOf<Job?>(null) }
    val glowColor = MaterialTheme.colorScheme.primary
    // D-pad press feedback: the zone under the finger lights from press to release, then fades.
    var pressedZone by remember { mutableStateOf<Int?>(null) }
    val pressAlpha = remember { Animatable(0f) }
    // OK held: a ring runs inside the moat from 150 ms to the long-press timeout, linear, so it
    // tells the time honestly. Reduced motion: no sweep, it appears whole at the timeout.
    val okRing = remember { Animatable(0f) }
    val reducedNow by rememberUpdatedState(LocalReducedMotion.current)
    val modeChange by rememberUpdatedState(onModeChange)
    // The corner switch: pressed while a finger that went down in its square is still there.
    var switchPressed by remember { mutableStateOf(false) }
    val switchable = onModeChange != null
    fun switchTo(target: PadMode) {
        val change = modeChange ?: return
        haptics.select()
        change(target)
    }

    fun flash(at: Offset) {
        glow = at
        scope.launch {
            glowAlpha.snapTo(0.28f)
            glowAlpha.animateTo(0f, tween(durationMillis = 260))
        }
    }

    // The two-finger level, counted from the tag while it shows so quick steps never read a stale state.
    fun volumeTag(delta: Int) {
        val base = (if (tagVisible) tagLevel else null) ?: currentVolume ?: return
        val ceiling = currentCap ?: TuttiController.MAX_VOLUME
        val next = VolumeSteps.step(base, delta, ceiling)
        if (VolumeSteps.ticks(next, ceiling)) haptics.tick()
        tagLevel = next
        tagVisible = true
        tagJob?.cancel()
        tagJob = scope.launch {
            delay(TAG_MS)
            tagVisible = false
        }
    }

    val padLabel = stringResource(R.string.pad_label)
    val gestures = if (down) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    haptics.bump()
                    nudge.poke()
                },
            )
        }
    } else if (mode == PadMode.TOUCHPAD) {
        Modifier.pointerInput(haptics, switchable, switchOnLeft) {
            val corner = if (switchable) PAD_SWITCH_ZONE.dp.toPx() else 0f
            touchpadGestures(
                inCorner = { at -> inPadSwitchCorner(at.x, at.y, size.width.toFloat(), corner, switchOnLeft) },
                onCornerPress = { switchPressed = it },
                onSwitch = { switchTo(PadMode.DPAD) },
                onStep = { code ->
                    haptics.tick()
                    key(code)
                },
                onTap = { at ->
                    haptics.click()
                    flash(at)
                    key(V2Key.DPAD_CENTER)
                },
                onLong = {
                    haptics.longPress()
                    longSelect()
                },
                onTwoFingerTap = {
                    haptics.select()
                    playPause()
                },
                onTwoFingerStep = { delta ->
                    volumeTag(delta)
                    volumeStep(delta)
                },
                // Finger down shows a soft halo under it, and nothing is sent.
                onDown = { at ->
                    glow = at
                    scope.launch { glowAlpha.animateTo(HALO_ALPHA, tween(durationMillis = Motion.FADE_OUT)) }
                },
                onUp = { scope.launch { glowAlpha.animateTo(0f, tween(durationMillis = 260)) } },
                onMove = { at -> glow = at },
            )
        }
    } else {
        Modifier.pointerInput(haptics, switchable, switchOnLeft) {
            val corner = if (switchable) PAD_SWITCH_ZONE.dp.toPx() else 0f
            fun zoneAt(at: Offset): Int? = dpadZone(at, size.width.toFloat(), size.height.toFloat(), corner, switchOnLeft)
            fun cornerAt(at: Offset): Boolean = inPadSwitchCorner(at.x, at.y, size.width.toFloat(), corner, switchOnLeft)
            detectTapGestures(
                onPress = { at ->
                    val zone = zoneAt(at)
                    if (zone == null && cornerAt(at)) {
                        switchPressed = true
                        tryAwaitRelease()
                        switchPressed = false
                    }
                    if (zone != null) {
                        pressedZone = zone
                        // On down, in the frame: OK is a key (click), a direction a step (tick).
                        haptics.perform(if (zone == V2Key.DPAD_CENTER) FeedbackEvent.KEY else FeedbackEvent.STEP)
                        pressAlpha.snapTo(1f)
                        val timeout = viewConfiguration.longPressTimeoutMillis
                        val ring = if (zone != V2Key.DPAD_CENTER) {
                            null
                        } else {
                            scope.launch {
                                okRing.snapTo(0f)
                                delay(RING_DELAY_MS)
                                val rest = (timeout - RING_DELAY_MS).coerceAtLeast(1L)
                                if (reducedNow) {
                                    delay(rest)
                                    okRing.snapTo(1f)
                                } else {
                                    okRing.animateTo(1f, tween(rest.toInt(), easing = LinearEasing))
                                }
                            }
                        }
                        // A held direction repeats (KeyRepeat); a quick one goes once, on release.
                        var repeats = 0
                        val repeater = if (zone == V2Key.DPAD_CENTER) {
                            null
                        } else {
                            scope.launch {
                                KeyRepeat.run {
                                    repeats++
                                    haptics.tick()
                                    key(zone)
                                }
                            }
                        }
                        val released = tryAwaitRelease()
                        repeater?.cancel()
                        if (repeater != null && released && repeats == 0) key(zone)
                        scope.launch { pressAlpha.animateTo(0f, tween(durationMillis = 180)) }
                        if (ring != null) {
                            ring.cancel()
                            scope.launch { okRing.animateTo(0f, tween(durationMillis = Motion.FADE_OUT)) }
                        }
                    }
                },
                // Directions are sent from onPress, so a hold can repeat; only OK waits for the tap.
                onTap = { at ->
                    val zone = zoneAt(at)
                    if (zone == V2Key.DPAD_CENTER && pressedZone == V2Key.DPAD_CENTER) key(V2Key.DPAD_CENTER)
                    // dpadZone leaves the switch's corner out, so a tap there is only the switch.
                    if (zone == null && cornerAt(at)) switchTo(PadMode.TOUCHPAD)
                },
                onLongPress = { at ->
                    if (zoneAt(at) == V2Key.DPAD_CENTER) {
                        haptics.longPress()
                        longSelect()
                    }
                },
            )
        }
    }

    val dpadLabel = stringResource(R.string.pad_dpad_label)
    val okLabel = stringResource(R.string.pad_ok)
    val directionLabels = listOf(
        V2Key.DPAD_UP to stringResource(R.string.pad_up),
        V2Key.DPAD_DOWN to stringResource(R.string.pad_down),
        V2Key.DPAD_LEFT to stringResource(R.string.pad_left),
        V2Key.DPAD_RIGHT to stringResource(R.string.pad_right),
    )
    val semanticsModifier = if (down) {
        // Down, it is one node that says why, with no actions: TalkBack can't send what a finger can't.
        Modifier.semantics {
            contentDescription = if (mode == PadMode.TOUCHPAD) padLabel else dpadLabel
            if (downReason != null) stateDescription = downReason
        }
    } else {
        // Both modes offer TalkBack the same OK and four directions: a swipe can't be read out,
        // but the keys it stands for can.
        Modifier.semantics {
            contentDescription = if (mode == PadMode.TOUCHPAD) padLabel else dpadLabel
            onClick(label = okLabel) {
                key(V2Key.DPAD_CENTER)
                true
            }
            customActions = directionLabels.map { (code, label) ->
                CustomAccessibilityAction(label) {
                    key(code)
                    true
                }
            }
        }
    }
    // One surface in both modes, the same box, corner and tone (Erwann, 2026-10-24: "D-pad et
    // touchpad" the same size): switching modes changes what is on it, never its size. In D-pad
    // mode the disc sits on it a tone up, and the whole surface answers (dpadZone).
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh

    // The launch intro's ring becomes this surface, in either mode: it reads its box and corner.
    val introAnchors = LocalIntroAnchors.current
    val cornerPx = with(LocalDensity.current) { PAD_CORNER.toPx() }
    DisposableEffect(introAnchors) { onDispose { introAnchors.touchpad = null } }
    val touchpadAnchor = Modifier.onGloballyPositioned { introAnchors.touchpad = TouchpadAnchor(it.boundsInWindow(), cornerPx) }

    // The mode switch sits on the surface (PadModeSwitch) but never reads a touch: touchpadGestures reads
    // every touch, consumed or not, so the pad hit-tests the corner itself and a tap there is only the switch.
    // The D-pad face fades in and out; it also scales unless motion is reduced.
    val reducedMotion = LocalReducedMotion.current
    val faceShown by animateFloatAsState(
        targetValue = if (mode == PadMode.DPAD) 1f else 0f,
        animationSpec = snappyOrFade(),
        label = "dpadFace",
    )
    // Down dims what sends (the chevrons, OK, the switch) and leaves the surface whole, so the big
    // pad reads as waiting, not broken. An opacity change, which is also its reduced-motion form.
    val live by animateFloatAsState(if (down) DOWN_ALPHA else 1f, snappyOrFade(), label = "padLive")
    val downEdge = MaterialTheme.colorScheme.outline
    BoxWithConstraints(
        modifier = modifier
            .then(touchpadAnchor)
            .clip(RoundedCornerShape(PAD_CORNER))
            .background(surfaceColor)
            .then(semanticsModifier)
            .drawBehind {
                if (down) {
                    val half = DOWN_STROKE.toPx() / 2f
                    inset(half) {
                        drawDownRing(RoundedCornerShape(PAD_CORNER - DOWN_STROKE / 2).createOutline(size, layoutDirection, this), downEdge)
                    }
                }
                if (glow.isSpecified && glowAlpha.value > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glowColor.copy(alpha = glowAlpha.value), glowColor.copy(alpha = 0f)),
                            center = glow,
                            radius = 120.dp.toPx(),
                        ),
                        radius = 120.dp.toPx(),
                        center = glow,
                    )
                }
            }
            .then(gestures),
    ) {
        val tagAlpha by animateFloatAsState(if (tagVisible) 1f else 0f, tween(durationMillis = Motion.FADE_OUT), label = "volumeTag")
        if (tagAlpha > 0f) {
            ValueTag(
                stringResource(R.string.pad_volume_tag, tagLevel),
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .graphicsLayer { alpha = tagAlpha },
            )
        }
        if (mode == PadMode.DPAD || faceShown > 0f) {
            // The launch intro's OK lands here: it reads where the live D-pad is drawn.
            val anchors = LocalIntroAnchors.current
            DisposableEffect(anchors) { onDispose { anchors.pad = null } }
            // The face is a square of the disc's own size, shrunk if needed so it clears the switch's corner.
            val faceSide = with(LocalDensity.current) {
                (2f * dpadFaceRadius(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), (if (switchable) PAD_SWITCH_ZONE.dp.toPx() else 0f), switchOnLeft)).toDp()
            }
            DpadFace(
                pressedZone = pressedZone,
                pressed = pressAlpha.value,
                reduced = reducedMotion,
                okRing = okRing.value,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(faceSide)
                    .graphicsLayer {
                        alpha = faceShown.coerceIn(0f, 1f) * live
                        val scale = if (reducedMotion) 1f else 0.92f + 0.08f * faceShown
                        scaleX = scale
                        scaleY = scale
                    }
                    .onGloballyPositioned { if (mode == PadMode.DPAD) anchors.pad = it.boundsInWindow() },
            )
        }
        if (switchable) {
            PadModeSwitch(
                mode = mode,
                pressed = switchPressed,
                onSwitch = ::switchTo,
                atLeft = switchOnLeft,
                modifier = Modifier
                    .align(if (switchOnLeft) AbsoluteAlignment.TopLeft else AbsoluteAlignment.TopRight)
                    .graphicsLayer { alpha = live },
            )
        }
    }
}

/**
 * The D-pad face, drawn on the pad's surface a tone up. Geometry comes from the same fractions
 * as [dpadZone], so what is drawn is what answers: the OK disc and its moat are the centre zone,
 * each quarter of the ring between the diagonals is a direction (and so is the surface round it,
 * along the same diagonals). [pressed] (0..1) lights [pressedZone] with a state layer and brings
 * its chevron to full contrast. Tonal only: no outlines, no dividers. [okRing] (0..1) is the
 * long-press sweep, drawn in the moat round OK.
 */
@Composable
internal fun DpadFace(pressedZone: Int?, pressed: Float, modifier: Modifier = Modifier, reduced: Boolean = false, okRing: Float = 0f) {
    val scheme = MaterialTheme.colorScheme
    val disc = scheme.surfaceContainerHighest
    val chevron = scheme.onSurfaceVariant
    val chevronPressed = scheme.onSurface
    val ringLayer = scheme.onSurface
    val centre = scheme.secondaryContainer
    val centreLayer = scheme.onSecondaryContainer
    val ringColor = scheme.onSurface
    Canvas(modifier) {
        val radius = min(size.width, size.height) / 2f
        val outer = radius * DISC_FRACTION
        val inner = radius * CENTRE_FRACTION
        val moat = 4.dp.toPx()
        val ringWidth = outer - inner
        val ringMid = (outer + inner) / 2f

        drawCircle(disc, radius = ringMid, style = Stroke(width = ringWidth))

        if (pressedZone != null && pressedZone != V2Key.DPAD_CENTER && pressed > 0f) {
            drawArc(
                color = ringLayer.copy(alpha = PRESSED_LAYER * pressed),
                startAngle = sweepStart(pressedZone),
                sweepAngle = 90f,
                useCenter = false,
                topLeft = center - Offset(ringMid, ringMid),
                size = Size(ringMid * 2f, ringMid * 2f),
                style = Stroke(width = ringWidth),
            )
        }

        // OK tightens to 0.94 under the finger; the disc itself never scales.
        val okPressed = if (pressedZone == V2Key.DPAD_CENTER && !reduced) pressed else 0f
        val okRadius = (inner - moat) * (1f - (1f - OK_SCALE) * okPressed)
        drawCircle(centre, radius = okRadius)
        if (pressedZone == V2Key.DPAD_CENTER && pressed > 0f) {
            drawCircle(centreLayer.copy(alpha = PRESSED_LAYER * pressed), radius = okRadius)
        }
        if (okRing > 0f) {
            val ringRadius = inner - moat / 2f
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * okRing.coerceAtMost(1f),
                useCenter = false,
                topLeft = center - Offset(ringRadius, ringRadius),
                size = Size(ringRadius * 2f, ringRadius * 2f),
                style = Stroke(width = OK_RING_WIDTH.toPx(), cap = StrokeCap.Round),
            )
        }

        val arm = ringWidth * 0.18f
        for (code in DIRECTIONS) {
            val lit = if (code == pressedZone) pressed else 0f
            // Position and weight carry the press, not hue: the chevron steps 3 dp out and gets bolder.
            val stroke = Stroke(width = (3.5f + 1.5f * lit).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val travel = if (reduced) 0f else CHEVRON_TRAVEL.toPx() * lit
            drawChevron(code, ringMid + travel, arm, lerp(chevron, chevronPressed, lit), stroke)
        }
    }
}

/** One chevron, [distance] from [centre] (the face's own centre by default); the launch intro draws them too. */
internal fun DrawScope.drawChevron(code: Int, distance: Float, arm: Float, color: Color, stroke: Stroke, centre: Offset = center) {
    // Pointing up, the tip sits a little outward of the ring's middle line; rotate for the others.
    val (ux, uy) = when (code) {
        V2Key.DPAD_UP -> 0f to -1f
        V2Key.DPAD_DOWN -> 0f to 1f
        V2Key.DPAD_LEFT -> -1f to 0f
        else -> 1f to 0f
    }
    val tip = centre + Offset(ux, uy) * (distance + arm * 0.25f)
    val back = centre + Offset(ux, uy) * (distance - arm * 0.75f)
    val side = Offset(-uy, ux) * arm
    val path = Path().apply {
        moveTo(back.x + side.x, back.y + side.y)
        lineTo(tip.x, tip.y)
        lineTo(back.x - side.x, back.y - side.y)
    }
    drawPath(path, color, style = stroke)
}

private fun sweepStart(code: Int): Float = when (code) {
    V2Key.DPAD_RIGHT -> -45f
    V2Key.DPAD_DOWN -> 45f
    V2Key.DPAD_LEFT -> 135f
    else -> 225f
}

/**
 * The key under [at] in a pad of [width] x [height]: OK inside the centre zone, else the direction
 * of the quarter between the diagonals, anywhere on the surface (the D-pad is the whole surface,
 * as the touchpad is), except the switch's top [corner] square, which is null. Same units throughout.
 */
internal fun dpadZone(at: Offset, width: Float, height: Float, corner: Float, cornerOnLeft: Boolean): Int? {
    if (inPadSwitchCorner(at.x, at.y, width, corner, cornerOnLeft)) return null
    val dx = at.x - width / 2f
    val dy = at.y - height / 2f
    val radius = dpadFaceRadius(width, height, corner, cornerOnLeft)
    val distance = Offset(dx, dy).getDistance()
    // Only the drawn disc answers: the surface round it is the touchpad's plate, inert in D-pad
    // mode (Erwann, 2026-10-24: "on devrait activer les flèches que quand on touche les flèches").
    return when {
        distance > radius -> null
        distance < radius * CENTRE_FRACTION -> V2Key.DPAD_CENTER
        abs(dx) > abs(dy) -> if (dx > 0) V2Key.DPAD_RIGHT else V2Key.DPAD_LEFT
        else -> if (dy > 0) V2Key.DPAD_DOWN else V2Key.DPAD_UP
    }
}

private val DIRECTIONS = listOf(V2Key.DPAD_UP, V2Key.DPAD_DOWN, V2Key.DPAD_LEFT, V2Key.DPAD_RIGHT)

/** The D-pad's OK radius in a face of [width] x [height] px, moat of [moat] px off, as [DpadFace] draws it. */
internal fun dpadOkRadius(width: Float, height: Float, moat: Float): Float = min(width, height) / 2f * CENTRE_FRACTION - moat

/** The D-pad disc's outer edge in a face of [width] x [height] px, as [DpadFace] draws it. */
internal fun dpadEdgeRadius(width: Float, height: Float): Float = min(width, height) / 2f * DISC_FRACTION

/** The ring's inner edge (OK plus its moat) in a face of [width] x [height] px, as [DpadFace] draws it. */
internal fun dpadRingInner(width: Float, height: Float): Float = min(width, height) / 2f * CENTRE_FRACTION

/** A resting chevron's arm in a face of [width] x [height] px, as [DpadFace] draws it. */
internal fun dpadChevronArm(width: Float, height: Float): Float = (dpadEdgeRadius(width, height) - dpadRingInner(width, height)) * 0.18f

/** A resting chevron's stroke width, as [DpadFace] draws it. */
internal val DPAD_CHEVRON_STROKE = 3.5.dp

/** The pad surface's corner (touchpad mode), which the launch intro's ring lands on. */
internal val PAD_CORNER = 36.dp

/** The directions clockwise from the top, for anything that plays the chevrons in turn. */
internal val DPAD_CLOCKWISE = listOf(V2Key.DPAD_UP, V2Key.DPAD_RIGHT, V2Key.DPAD_DOWN, V2Key.DPAD_LEFT)

/** Disc radius, as a fraction of half the face's side ([dpadFaceRadius]). */
private const val DISC_FRACTION = 0.94f

/** OK zone radius (disc plus its moat), same unit; drawn and hit-tested alike. */
private const val CENTRE_FRACTION = 0.38f

/** The clear margin kept between the disc's edge and the switch's corner, same unit. */
private const val SLACK_FRACTION = 0.06f

/**
 * How far the drawn disc and its margin reach, as a fraction of the face's half side: what
 * [dpadFaceRadius] keeps out of the switch's corner, so the glyph never sits on the ring.
 */
internal const val DPAD_REACH = DISC_FRACTION + SLACK_FRACTION

/** Material 3's pressed state layer is 10%; a little more reads on a dark surface. */
private const val PRESSED_LAYER = 0.14f

/** OK's press, like a key's but a touch less: it is the biggest target. */
private const val OK_SCALE = 0.94f

/** The long-press ring round OK: it fits the 4 dp moat. */
private val OK_RING_WIDTH = 3.dp

/** How far a pressed chevron steps outward; none when motion is reduced. */
private val CHEVRON_TRAVEL = 3.dp

/** How long the "Vol 24" tag stays after the last two-finger step. */
private const val TAG_MS = 900L

/** The touchpad's halo under a resting finger; a tap flashes brighter. */
private const val HALO_ALPHA = 0.16f

/**
 * One gesture at a time: a swipe emits a D-pad step every [STEP_DP] along its dominant axis,
 * and a finger that rests after a step (within touch slop) repeats it on the [KeyRepeat] schedule
 * until it moves or lifts; a still finger held past the long-press timeout is a long Select; two fingers become
 * play/pause (tap) or bar volume (vertical drag, one step per [VOLUME_STEP_DP]).
 */
private suspend fun PointerInputScope.touchpadGestures(
    inCorner: (Offset) -> Boolean,
    onCornerPress: (Boolean) -> Unit,
    onSwitch: () -> Unit,
    onStep: (Int) -> Unit,
    onTap: (Offset) -> Unit,
    onLong: () -> Unit,
    onTwoFingerTap: () -> Unit,
    onTwoFingerStep: (Int) -> Unit,
    onDown: (Offset) -> Unit,
    onUp: () -> Unit,
    onMove: (Offset) -> Unit,
) {
    val stepPx = STEP_DP.dp.toPx()
    val volumeStepPx = VOLUME_STEP_DP.dp.toPx()
    val slop = viewConfiguration.touchSlop
    val longPressMillis = viewConfiguration.longPressTimeoutMillis
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // The corner is the switch's own key: no halo, no long Select, and a still tap there only switches.
            val startedInCorner = inCorner(down.position)
            if (startedInCorner) onCornerPress(true) else onDown(down.position)
            var pointers = 1
            var moved = false
            var longFired = false
            var travel = Offset.Zero
            var accumulated = Offset.Zero
            var volumeAccumulated = 0f
            var volumeTravel = 0f
            var lastUptime = down.uptimeMillis
            // The repeat after a step: where the finger rested when it went, and the running job.
            var restAt = Offset.Unspecified
            var repeater: Job? = null
            fun stopRepeat() {
                repeater?.cancel()
                repeater = null
                restAt = Offset.Unspecified
            }
            fun step(code: Int, at: Offset) {
                onStep(code)
                stopRepeat()
                restAt = at
                repeater = launch { KeyRepeat.run { onStep(code) } }
            }
            while (true) {
                val event: PointerEvent? = if (!moved && !longFired && pointers == 1) {
                    val remaining = (longPressMillis - (lastUptime - down.uptimeMillis)).coerceAtLeast(1L)
                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    longFired = true
                    if (!startedInCorner) onLong()
                    continue
                }
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size > 1) stopRepeat()
                lastUptime = event.changes.first().uptimeMillis
                if (pressed.size > pointers) pointers = pressed.size
                if (pointers >= 2) {
                    val dy = pressed.map { it.positionChange().y }.average().toFloat()
                    volumeAccumulated += dy
                    volumeTravel += abs(dy)
                    if (volumeTravel > slop) moved = true
                    while (volumeAccumulated <= -volumeStepPx) {
                        onTwoFingerStep(1)
                        volumeAccumulated += volumeStepPx
                    }
                    while (volumeAccumulated >= volumeStepPx) {
                        onTwoFingerStep(-1)
                        volumeAccumulated -= volumeStepPx
                    }
                } else if (!longFired) {
                    val change = pressed.first()
                    val delta = change.positionChange()
                    // Leaving the corner's slop turns its press into a swipe: the icon lets go.
                    if (startedInCorner && !moved && (travel + delta).getDistance() > slop) onCornerPress(false)
                    onMove(change.position)
                    travel += delta
                    accumulated += delta
                    if (!moved && travel.getDistance() > slop) moved = true
                    // Moving again ends the repeat; the travel goes on counting steps as before.
                    if (restAt.isSpecified && (change.position - restAt).getDistance() > slop) stopRepeat()
                    if (moved) {
                        if (abs(accumulated.x) >= stepPx && abs(accumulated.x) >= abs(accumulated.y)) {
                            val right = accumulated.x > 0
                            step(if (right) V2Key.DPAD_RIGHT else V2Key.DPAD_LEFT, change.position)
                            accumulated = Offset(accumulated.x - if (right) stepPx else -stepPx, 0f)
                        } else if (abs(accumulated.y) >= stepPx && abs(accumulated.y) > abs(accumulated.x)) {
                            val downwards = accumulated.y > 0
                            step(if (downwards) V2Key.DPAD_DOWN else V2Key.DPAD_UP, change.position)
                            accumulated = Offset(0f, accumulated.y - if (downwards) stepPx else -stepPx)
                        }
                    }
                }
                event.changes.forEach { it.consume() }
            }
            stopRepeat()
            onUp()
            if (startedInCorner) onCornerPress(false)
            when (padTouchEnd(moved, longFired, pointers, startedInCorner)) {
                PadTouchEnd.TWO_FINGER_TAP -> onTwoFingerTap()
                PadTouchEnd.SWITCH -> onSwitch()
                PadTouchEnd.TAP -> onTap(down.position)
                PadTouchEnd.NOTHING -> Unit
            }
        }
    }
}

private const val STEP_DP = 44
private const val VOLUME_STEP_DP = 14

/** How far a down pad's face and switch dim: enough to read as waiting, the glyphs still legible. */
private const val DOWN_ALPHA = 0.38f
