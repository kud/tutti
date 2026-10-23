package io.kud.tutti.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The TV's own icon for an app, from the cache ([iconPath]); until one was ever fetched, the
 * app's first letter on a neutral tile. No bundled third-party logos.
 */
@Composable
fun AppIcon(
    name: String,
    iconPath: String?,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    corner: Dp = 8.dp,
) {
    val bitmap by produceState<ImageBitmap?>(null, iconPath) {
        value = iconPath?.let { path ->
            withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
        }
    }
    val shape = RoundedCornerShape(corner)
    val image = bitmap
    Box(
        modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        } else {
            Text(
                text = name.trim().take(1).uppercase().ifEmpty { "·" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
                fontSize = (size.value * 0.45f).sp,
            )
        }
    }
}
