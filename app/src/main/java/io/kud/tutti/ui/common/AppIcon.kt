package io.kud.tutti.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kud.tutti.platform.data.IconBitmaps
import io.kud.tutti.platform.data.PhoneIconBitmaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An app's face, best first: the TV's own launcher art from the cache ([iconPath]); else the
 * icon of the same app installed on this phone ([PhoneIconBitmaps], matched by package only),
 * full bleed in the tile's shape (Iris, 2026-10-25); else the brand's official mark ([brandMark], Simple Icons) in the theme's on-surface tone on the tile;
 * else the app's first letter. One tile for all four, so a row mixing them stays calm, and the
 * mark is monochrome: it reads by shape, never by colour, in light and dark alike. Decorative:
 * the app's name is always spoken by the button around it.
 */
@Composable
fun AppIcon(
    name: String,
    iconPath: String?,
    modifier: Modifier = Modifier,
    packageName: String? = null,
    size: Dp = 28.dp,
    corner: Dp = 8.dp,
) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<ImageBitmap?>(null, iconPath, packageName, sizePx) {
        value = withContext(Dispatchers.IO) {
            val tv = iconPath?.let { path -> runCatching { IconBitmaps.load(path, sizePx) }.getOrNull() }
            val phone = if (tv != null || packageName == null) null else {
                runCatching { PhoneIconBitmaps.load(context, packageName, sizePx) }.getOrNull()
            }
            (tv ?: phone)?.asImageBitmap()
        }
    }
    val shape = RoundedCornerShape(corner)
    val image = bitmap
    val mark = brandMark(packageName)
    Box(
        modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        } else if (mark != null) {
            Icon(
                painterResource(mark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(size * 0.6f),
            )
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
