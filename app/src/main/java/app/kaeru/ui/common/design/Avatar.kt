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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruSecondary
import coil3.compose.SubcomposeAsyncImage

/** Big enough to be a face rather than a bullet, and the height of the two lines beside it. */
val AvatarSize: Dp = 56.dp

/**
 * The one round image in the app: whoever is signed in.
 *
 * Everything else here is a rectangle with the app's 12dp corner, which is what makes a circle
 * read as a person rather than as another card. With no picture — none stored, or one that fails
 * to load — it falls back to the first letter of the nickname, the same way a poster with no
 * artwork falls back to the first letter of a title, so the shape of the screen does not change
 * with the network. A 404 and an offline phone are the common cases, and an empty grey disc is
 * exactly the state the fallback exists to prevent.
 *
 * While the picture is on its way the disc stays the app's own grey: a letter that appeared and
 * then swapped to a face would be two changes where the viewer expects one.
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
            Initial(name)
        } else {
            SubcomposeAsyncImage(
                model = kaeruImage(url),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                loading = {},
                error = { Initial(name) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The letter that stands in for a face. Same idea as the poster placeholder, same colour. */
@Composable
private fun Initial(name: String) {
    Text(
        name.take(1).uppercase(),
        style = MaterialTheme.typography.headlineMedium,
        color = KaeruSecondary,
    )
}
