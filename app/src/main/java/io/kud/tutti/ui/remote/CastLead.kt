package io.kud.tutti.ui.remote

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import io.kud.tutti.platform.net.CastArtworkLoader
import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.motion.Motion

/**
 * The card's leading tile (Erwann, 2026-10-28: « Moi je suis chaud d'afficher la thumbnail »): the
 * app's icon, which gives way to Cast's artwork once it has loaded, at the icon's height, 16:9,
 * cropped (YouTube's `hqdefault` is 4:3 with bars, which the crop drops), with the icon's corners.
 * Until then, and whenever there is none or it fails, the icon stays as it was, so nothing ever
 * shows an empty frame. The tile widens on the card's spring while the two cross-fade by opacity;
 * with reduced motion it snaps and only the fade runs. Decorative: TalkBack hears nothing new.
 */
@Composable
internal fun CastLead(showing: CardShowing, size: Dp, appIcons: Map<String, String>, cast: CastNowPlaying?) {
    val url = cast?.imageUrl
    val wide = size * 16f / 9f
    val widthPx = with(LocalDensity.current) { wide.roundToPx() }
    val art by produceState(url?.let { CastArtworkLoader.cached(it) }, url, widthPx) {
        value = url?.let { CastArtworkLoader.load(it, widthPx) }
    }
    val reduced = LocalReducedMotion.current
    val shown by animateFloatAsState(if (art != null) 1f else 0f, tween(Motion.MEDIUM), label = "castArt")
    val width by animateDpAsState(if (art != null) wide else size, if (reduced) snap<Dp>() else Motion.snappy<Dp>(), label = "castLead")
    Box(Modifier.size(width, size)) {
        if (shown < 1f) {
            Box(Modifier.align(Alignment.CenterStart).alpha(1f - shown)) { CardIcon(showing, size, appIcons) }
        }
        art?.let { bitmap ->
            val shape = RoundedCornerShape(size / 4)
            Image(
                bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(shown)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape),
            )
        }
    }
}
