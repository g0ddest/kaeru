package app.kaeru.ui.common.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Scale
import coil3.size.Size

/**
 * How every image in the app is loaded.
 *
 * Posters and screenshots arrive over the network onto a dark screen, and a hard cut from the
 * placeholder to the artwork reads as a glitch on a feed that is mostly artwork. A fade of the
 * standard duration makes an image arriving look like the same thing changing rather than a new
 * thing appearing, which matters most under the hero, where the crossfade above and the load
 * below would otherwise fight.
 */
@Composable
internal fun kaeruImage(url: String?): ImageRequest {
    val context = LocalContext.current
    return remember(context, url) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(KaeruTokens.DurationNormal)
            .build()
    }
}

/**
 * The widest a backdrop is ever decoded at, whatever the panel says it is.
 *
 * A backdrop is the one image in the app that fills the screen, so on a television it is asked for
 * at 1920×1080 — about eight megabytes of bitmap, fetched and decoded on the D-pad's critical path
 * and held in pairs while the crossfade runs. Under two gradient scrims, with text over it, 720p
 * upscaled is not distinguishable from 1080p native at three metres, and it costs 2.25 times less
 * memory and a good deal less of a set-top box's network.
 */
private val BackdropSize = Size(1280, 720)

/**
 * The same loader, capped, for the images that cover a screen.
 *
 * It keeps [kaeruImage]'s fade. `Backdrop` is shared — the phone's details header and the
 * television's title card draw one with nothing fading around them — and taking the fade away left
 * those two hard-cutting from the placeholder to the artwork, which is the glitch the doc above
 * argues against by name.
 *
 * It does not double up on the television's home screen either. That screen cross-fades the whole
 * backdrop when the focused card changes, and the two only overlap if the new picture arrives
 * inside that 400ms — which means it came from the memory cache, and Coil skips this transition
 * for a memory-cache hit. An image off the network arrives after the outer fade has finished, so
 * what this adds there is a fade in place of a pop.
 */
@Composable
internal fun kaeruBackdropImage(url: String?): ImageRequest {
    val context = LocalContext.current
    return remember(context, url) {
        ImageRequest.Builder(context)
            .data(url)
            .size(BackdropSize)
            .scale(Scale.FILL)
            .crossfade(KaeruTokens.DurationNormal)
            .build()
    }
}
