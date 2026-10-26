package io.kud.tutti.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.ui.motion.Motion

/**
 * The settings language (Iris's Lawnchair-style mock, 2026-10-23): a titled group is one rounded
 * card, its rows stacked 2 dp apart so the background reads as the divider. Every row has the
 * same anatomy: an optional icon in a tonal disc, a title, one detail line, an optional footer,
 * and one trailing control.
 *
 * Rhythm: 16 dp page margin, 16 dp inside a row, 2 dp between rows, 24 dp above a group title.
 */
@Composable
fun SettingsGroup(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 8.dp),
            )
        }
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(GroupShape),
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            CompositionLocalProvider(LocalRowSurface provides MaterialTheme.colorScheme.surfaceContainer) {
                content()
            }
        }
    }
}

/** The fill a row paints: the card's surface inside a [SettingsGroup], nothing elsewhere. */
val LocalRowSurface = staticCompositionLocalOf { Color.Transparent }

/**
 * One settings row. [notice] replaces [detail] when the setting is inactive for a reason outside
 * tutti: it leads with a warning glyph so the state reads in shape and words, never colour alone.
 * A disabled row fades but always keeps a detail that says why.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    @DrawableRes icon: Int? = null,
    notice: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    role: Role? = null,
    footer: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val fade = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)
    Row(
        modifier
            .fillMaxWidth()
            .background(LocalRowSurface.current)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = role, onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            RowIcon(icon, fade)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f).then(fade)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            when {
                notice != null -> Notice(notice)
                detail != null -> Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            footer?.invoke()
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            Box(fade) { trailing() }
        }
    }
}

/** A trailing chevron for a row that opens another screen. */
@Composable
fun OpensChevron() {
    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A row that comes and goes with another setting (the vibration level under Vibration): it grows
 * and fades so the card doesn't jump; under reduced motion it only fades (Iris, 2026-10-23).
 */
@Composable
fun ColumnScope.RevealRow(visible: Boolean, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) fadeIn(Motion.fade()) else expandVertically(Motion.enter()) + fadeIn(Motion.enter()),
        exit = if (reduced) fadeOut(Motion.fade()) else shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
    ) { content() }
}

@Composable
private fun RowIcon(@DrawableRes icon: Int, modifier: Modifier) {
    Box(
        modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Notice(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val GroupShape = RoundedCornerShape(20.dp)
private val RowGap = 2.dp
internal const val DISABLED_ALPHA = 0.6f
