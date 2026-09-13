package app.kaeru.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.kaeru.ui.common.design.PosterImage

/**
 * Poster artwork, described for a screen reader.
 *
 * The drawing lives in `ui.common.design` with the rest of the card shapes; this is the variant
 * for the few places that show a poster on its own, with no title beside it to read out instead.
 */
@Composable
fun Poster(url: String?, title: String, modifier: Modifier = Modifier) {
    PosterImage(url, title, modifier, contentDescription = "Постер: $title")
}
