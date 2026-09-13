package app.kaeru.ui.common.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruSecondary
import coil3.compose.AsyncImage

/** Big enough to be a face rather than a bullet, and the height of the two lines beside it. */
val AvatarSize: Dp = 56.dp

/**
 * The one round image in the app: whoever is signed in.
 *
 * Everything else here is a rectangle with the app's 12dp corner, which is what makes a circle
 * read as a person rather than as another card. With no picture it falls back to the first letter
 * of the nickname, the same way a poster with no artwork falls back to the first letter of a
 * title, so the shape of the screen does not change with the network.
 *
 * It carries no description: the nickname is right beside it, and describing the picture as well
 * would make a screen reader say the same thing twice.
 */
@Composable
fun Avatar(url: String?, name: String, modifier: Modifier = Modifier, size: Dp = AvatarSize) {
    Box(
        modifier.size(size).clip(CircleShape).background(KaeruElevated),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Text(
                name.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = KaeruSecondary,
            )
        } else {
            AsyncImage(
                model = kaeruImage(url),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(KaeruElevated),
                error = ColorPainter(KaeruElevated),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
