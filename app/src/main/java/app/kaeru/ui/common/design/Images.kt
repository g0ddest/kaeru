package app.kaeru.ui.common.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.crossfade

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
