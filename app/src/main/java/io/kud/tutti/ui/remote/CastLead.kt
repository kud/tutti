package io.kud.tutti.ui.remote

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import io.kud.tutti.platform.net.NonFreeGate
import io.kud.tutti.protocol.privacy.NonFreeFeature
import androidx.compose.runtime.collectAsState
import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.quick.notification.MediaArt
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.motion.Motion

/**
 * The card's leading tile (Erwann, 2026-10-28: « Moi je suis chaud d'afficher la thumbnail »; and
 * 2026-11-06: the content's own picture wins whenever one exists): the app's icon, which gives way
 * to the content artwork ([leadArtwork]) once it has loaded, at the icon's height, with the icon's
 * corners. Its shape follows the picture ([MediaArt.leadAspect]): a landscape one is the same
 * middle 16:9 band the lock-screen card keeps ([MediaArt.contentCrop]; YouTube's 4:3 `hqdefault`
 * loses its bars), and the tile widens on the card's spring; a tall one (Nuvio's poster) shows
 * whole at its own shape inside the icon's square, so nothing beside it moves. Until it loads, and
 * whenever there is none or it fails, the icon stays as it was, so nothing ever shows an empty
 * frame. The two cross-fade by opacity; with reduced motion the width snaps and only the fade
 * runs. [description] is what TalkBack hears for the picture, the title and never its address;
 * null where the title is already read beside it.
 */
@Composable
internal fun CastLead(
    showing: CardShowing,
    size: Dp,
    appIcons: Map<String, String>,
    cast: CastNowPlaying?,
    description: String? = null,
) {
    // Off unless "Cast artwork" is on and "Only free services" off; flipping it re-asks at once.
    val artworkOn = NonFreeFeature.CAST_ARTWORK in NonFreeGate.allowed.collectAsState().value
    val url = leadArtwork(showing, cast)?.takeIf { artworkOn }
    val widthPx = with(LocalDensity.current) { (size * MediaArt.WIDE_ASPECT).roundToPx() }
    val art by produceState(url?.let { CastArtworkLoader.cached(it) }, url, widthPx) {
        value = url?.let { CastArtworkLoader.load(it, widthPx) }
    }
    val reduced = LocalReducedMotion.current
    val tile = art?.let { size * MediaArt.leadAspect(it.width, it.height) } ?: size
    val shown by animateFloatAsState(if (art != null) 1f else 0f, tween(Motion.MEDIUM), label = "castArt")
    val width by animateDpAsState(maxOf(size, tile), if (reduced) snap<Dp>() else Motion.snappy<Dp>(), label = "castLead")
    Box(Modifier.size(width, size)) {
        if (shown < 1f) {
            Box(Modifier.align(Alignment.CenterStart).alpha(1f - shown)) { CardIcon(showing, size, appIcons) }
        }
        art?.let { bitmap ->
            val shape = RoundedCornerShape(size / 4)
            Image(
                bitmap.asImageBitmap(),
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(minOf(tile, width), size)
                    .alpha(shown)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape),
            )
        }
    }
}
