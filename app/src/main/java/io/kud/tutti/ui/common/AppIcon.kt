package io.kud.tutti.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kud.tutti.platform.data.IconBitmaps
import io.kud.tutti.platform.data.PhoneIconBitmaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A raster face: the picture, how it sits in the tile, and what is behind a banner: its corners'
 * flat colour, or a blurred copy of itself ([backdrop]).
 */
private class Raster(val image: ImageBitmap, val crop: Boolean, val fill: Int?, val backdrop: ImageBitmap? = null)

/**
 * An app's face, in colour and edge to edge on every surface (Erwann, 2026-10-25: "tout en couleur
 * partout, mais faut que ça prenne toute la place du bouton"), best first:
 * 1. the same app's icon on this phone ([PhoneIconBitmaps], matched by package only), both
 *    adaptive layers full bleed;
 * 2. the TV's own launcher art ([iconPath]): a square icon cropped edge to edge, a banner fitted to
 *    the width on its corners' shared colour or, when they disagree, over a blurred copy of itself
 *    ([BrandColours.TvLook.Backdrop]; Iris, 2026-10-26), unless the app has a brand face below;
 * 3. the brand's official logo on its brand colour, or the app's own Play Store icon cropped edge to
 *    edge ([brandFace]);
 * 4. the app's first letter.
 * The picture fills whatever the tile measures, never a fixed size inside it: a caller stretching
 * the tile (the Apps grid's aspectRatio) once left an inset square with the tile showing round it.
 * One shape, clip and corner for every source. Decorative: the button around it says the name.
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
    val density = LocalDensity.current
    var measured by remember { mutableIntStateOf(with(density) { size.roundToPx() }) }
    val context = LocalContext.current.applicationContext
    val raster by produceState<Raster?>(null, iconPath, packageName, measured) {
        value = withContext(Dispatchers.IO) {
            val phone = packageName?.let { pkg -> runCatching { PhoneIconBitmaps.load(context, pkg, measured) }.getOrNull() }
            if (phone != null) return@withContext Raster(phone.asImageBitmap(), crop = true, fill = null)
            val tv = iconPath?.let { path -> runCatching { IconBitmaps.load(path, measured) }.getOrNull() }
                ?: return@withContext null
            val w = tv.width - 1
            val h = tv.height - 1
            when (val look = BrandColours.tvLook(tv.width, tv.height) { intArrayOf(tv.getPixel(0, 0), tv.getPixel(w, 0), tv.getPixel(0, h), tv.getPixel(w, h)) }) {
                null -> null
                BrandColours.TvLook.Crop -> Raster(tv.asImageBitmap(), crop = true, fill = null)
                is BrandColours.TvLook.Fill -> Raster(tv.asImageBitmap(), crop = false, fill = look.colour)
                BrandColours.TvLook.Backdrop -> {
                    // A full-bleed brand face beats a banner on its own blur; the backdrop replaces the letter only.
                    if (brandFace(packageName) != null) return@withContext null
                    val soft = runCatching { IconBitmaps.backdrop(tv) }.getOrNull() ?: return@withContext null
                    Raster(tv.asImageBitmap(), crop = false, fill = null, backdrop = soft.asImageBitmap())
                }
            }
        }
    }
    val face = raster
    val brand = if (face == null) brandFace(packageName) else null
    val background = when {
        face?.fill != null -> Color(face.fill)
        brand != null -> Color(brand.fill)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Box(
        modifier
            .size(size)
            .onSizeChanged { if (it.width > 0) measured = maxOf(it.width, it.height) }
            .clip(RoundedCornerShape(corner))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        val behind = face?.backdrop
        if (behind != null) {
            // Blurred by bilinear stretching of an 8 px copy, then dimmed a little toward the tile's
            // own colour so the fitted banner reads as the subject; no edge or shadow on it (Iris).
            Image(behind, contentDescription = null, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = BACKDROP_DIM)))
        }
        when {
            face != null -> Image(
                face.image,
                contentDescription = null,
                contentScale = if (face.crop) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            brand != null -> Image(
                painterResource(brand.drawable),
                contentDescription = null,
                contentScale = if (brand.crop) ContentScale.Crop else ContentScale.Fit,
                colorFilter = brand.glyph?.let { ColorFilter.tint(Color(it)) },
                modifier = Modifier.fillMaxSize(if (brand.crop) 1f else minOf(brand.scale, 1f)),
            )
            else -> Text(
                text = name.trim().take(1).uppercase().ifEmpty { "·" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
                fontSize = with(density) { (measured * 0.45f).toSp() },
            )
        }
    }
}

/** How far a banner's blurred backdrop leans toward the tile's colour (Iris, 2026-10-26: 15 to 20%). */
private const val BACKDROP_DIM = 0.18f
