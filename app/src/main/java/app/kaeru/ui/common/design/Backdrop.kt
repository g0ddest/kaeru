package app.kaeru.ui.common.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruSurface
import coil3.compose.AsyncImage

/**
 * A screenshot behind text, with the only two gradients in the app.
 *
 * Both are scrims, not decoration: each exists so that white text and controls stay legible over a
 * picture nobody chose for contrast, and each is drawn only on the edge the text actually sits on.
 * A backdrop with nothing over it asks for neither.
 *
 * The image is decorative — the title beside it carries the meaning — so it is left out of the
 * accessibility tree rather than described twice.
 */
@Composable
fun Backdrop(
    url: String?,
    modifier: Modifier = Modifier,
    scrimBottom: Boolean = true,
    scrimStart: Boolean = false,
) {
    Box(modifier.background(KaeruSurface)) {
        if (url != null) {
            AsyncImage(
                model = kaeruImage(url),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(KaeruSurface),
                error = ColorPainter(KaeruSurface),
                modifier = Modifier.matchParentSize(),
            )
        }
        if (scrimBottom) {
            Box(
                Modifier.matchParentSize().background(
                    // Three stops rather than two: a straight ramp from clear to near-black bands
                    // visibly on a dark panel.
                    Brush.verticalGradient(
                        0.30f to Color.Transparent,
                        0.68f to KaeruBackground.copy(alpha = 0.78f),
                        1f to KaeruBackground,
                    ),
                ),
            )
        }
        if (scrimStart) {
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(
                        0f to KaeruBackground,
                        0.28f to KaeruBackground.copy(alpha = 0.72f),
                        0.65f to Color.Transparent,
                    ),
                ),
            )
        }
    }
}
