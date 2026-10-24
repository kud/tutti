package io.kud.tutti.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.ui.motion.Motion
import kotlinx.coroutines.delay

/*
 * tutti's one transient message: what a system toast or a default snackbar used to say, drawn in
 * the app's own theme (Iris, 2026-10-24). A tonal card, never the inverse colours: dark-first means
 * no light bar flashing over a dark remote. The kind is told by its glyph (and, for a problem, by
 * TalkBack's "Problem" before the words), never by colour alone. One message at a time; a new one
 * replaces the old.
 *
 * The host lives at the app root, and every modal sheet carries its own: a sheet is a window of its
 * own, so a message drawn at the root would sit under it. Only the topmost host draws.
 *
 * The words are one short sentence, 14 words at most in any locale; longer help goes behind the
 * action (docs/design.md). A new message string joins MessageCopyTest's list, which enforces it.
 */

enum class MessageKind { INFO, PROBLEM }

@Stable
class Message internal constructor(
    val id: Long,
    val text: String,
    val kind: MessageKind,
    val actionLabel: String?,
    val onAction: (() -> Unit)?,
    val long: Boolean,
)

@Stable
class Messages {
    var current by mutableStateOf<Message?>(null)
        private set

    private val layers = mutableStateListOf<Any>()
    private var next = 0L

    /** Shows [text], replacing whatever was showing. [long] (or an action) keeps it twice as long. */
    fun show(
        text: String,
        kind: MessageKind = MessageKind.INFO,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        long: Boolean = false,
    ) {
        current = Message(++next, text, kind, actionLabel, onAction, long)
    }

    fun dismiss(id: Long? = null) {
        if (id == null || current?.id == id) current = null
    }

    internal fun enter(layer: Any) { layers.add(layer) }

    internal fun leave(layer: Any) { layers.remove(layer) }

    internal fun isTop(layer: Any): Boolean = layers.lastOrNull() === layer
}

/** Provided by MainActivity; null only in previews, where a message simply goes unsaid. */
val LocalMessages = staticCompositionLocalOf<Messages?> { null }

/**
 * Where messages appear: bottom centre, above the navigation bar, at most 560 dp wide. Fills its
 * parent without taking a touch outside the card. Place it last in a full-size box (the app root,
 * a sheet), so it draws above the content.
 */
@Composable
fun MessageHost(modifier: Modifier = Modifier, insetNavigationBar: Boolean = true) {
    val messages = LocalMessages.current ?: return
    val layer = remember { Any() }
    DisposableEffect(messages, layer) {
        messages.enter(layer)
        onDispose { messages.leave(layer) }
    }
    val shown = messages.current?.takeIf { messages.isTop(layer) }
    val a11y = LocalAccessibilityManager.current
    LaunchedEffect(shown?.id) {
        val message = shown ?: return@LaunchedEffect
        val base = if (message.long || message.onAction != null) LONG_MS else SHORT_MS
        // TalkBack users get the longer time their accessibility settings ask for.
        val wait = a11y?.calculateRecommendedTimeoutMillis(
            base,
            containsIcons = true,
            containsText = true,
            containsControls = true,
        ) ?: base
        delay(wait)
        messages.dismiss(message.id)
    }
    val reduced = LocalReducedMotion.current
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedContent(
            targetState = shown,
            transitionSpec = { messageTransition(reduced) },
            contentKey = { it?.id },
            contentAlignment = Alignment.BottomCenter,
            label = "message",
            modifier = Modifier
                .then(if (insetNavigationBar) Modifier.navigationBarsPadding() else Modifier)
                .padding(16.dp),
        ) { message ->
            if (message != null) MessageCard(message, onDismiss = { messages.dismiss(message.id) })
        }
    }
}

/** Rises 16 dp and fades in on a spring; sinks and fades out. Reduced motion: the fades alone. */
private fun messageTransition(reduced: Boolean): ContentTransform {
    if (reduced) return fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
    return (slideInVertically(Motion.snappy()) { it / 2 } + fadeIn(Motion.enter())) togetherWith
        (slideOutVertically(Motion.exit()) { it / 2 } + fadeOut(Motion.exit()))
}

@Composable
private fun MessageCard(message: Message, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val problem = message.kind == MessageKind.PROBLEM
    val dismissLabel = stringResource(R.string.message_dismiss)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerHighest,
        contentColor = scheme.onSurface,
        shadowElevation = 6.dp,
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .semantics {
                liveRegion = if (problem) LiveRegionMode.Assertive else LiveRegionMode.Polite
                dismiss(dismissLabel) {
                    onDismiss()
                    true
                }
            },
    ) {
        // The action sits under the words, end-aligned, rather than beside them: beside them it
        // squeezed a long message into a narrow column ("Show me how" next to six lines of text).
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(
                Modifier.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painterResource(if (problem) R.drawable.ic_warning else R.drawable.ic_info),
                    contentDescription = if (problem) stringResource(R.string.message_problem) else null,
                    tint = if (problem) scheme.error else scheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = dismissLabel,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (message.actionLabel != null && message.onAction != null) {
                TextButton(
                    onClick = {
                        message.onAction.invoke()
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.End).padding(end = 8.dp),
                ) { Text(message.actionLabel) }
            }
        }
    }
}

/** How long a message stays: a plain note, and one that is long or offers an action. */
private const val SHORT_MS = 4_000L
private const val LONG_MS = 8_000L
