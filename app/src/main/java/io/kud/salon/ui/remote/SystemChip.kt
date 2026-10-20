package io.kud.salon.ui.remote

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.salon.R
import io.kud.salon.controller.Link
import io.kud.salon.controller.SalonUiState
import io.kud.salon.ui.common.motion

private val Online = Color(0xFF4CD18A)
private val Pending = Color(0xFFF2B84B)

private fun Link.isDown() = this == Link.UNREACHABLE || this == Link.NEEDS_PAIRING

/**
 * "Salon · BRAVIA 8 + Bar 9" with one status dot. Splits into two lines only when exactly
 * one device drops ("Bar 9 injoignable").
 */
@Composable
fun SystemChip(state: SalonUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tv = state.devices.tv
    val bar = state.devices.bar
    val tvLink = if (tv != null) state.links.tv else Link.ABSENT
    val barLink = if (bar != null) state.links.bar else Link.ABSENT
    val configured = listOfNotNull(tv?.let { it.name to tvLink }, bar?.let { it.name to barLink })
    val down = configured.filter { it.second.isDown() }
    val dotTarget = when {
        configured.isEmpty() -> MaterialTheme.colorScheme.outline
        down.size == configured.size -> MaterialTheme.colorScheme.error
        down.isNotEmpty() || configured.any { it.second == Link.CONNECTING } -> Pending
        else -> Online
    }
    val dot by animateColorAsState(dotTarget, motion(), label = "dot")
    val room = stringResource(R.string.system_room)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column {
                if (configured.isEmpty()) {
                    Text(stringResource(R.string.system_no_device), style = MaterialTheme.typography.titleSmall)
                } else if (down.size == 1 && configured.size == 2) {
                    val healthy = configured.first { !it.second.isDown() }
                    val (name, link) = down.first()
                    Text("$room · ${healthy.first}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = if (link == Link.NEEDS_PAIRING) {
                            stringResource(R.string.system_needs_pairing, name)
                        } else {
                            stringResource(R.string.system_unreachable, name)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                } else {
                    Text(
                        text = "$room · " + configured.joinToString(" + ") { it.first },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
