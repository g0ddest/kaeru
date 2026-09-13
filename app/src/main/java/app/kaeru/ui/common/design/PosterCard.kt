package app.kaeru.ui.common.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import coil3.compose.AsyncImage

private val BadgePaddingH = KaeruTokens.Space2
private val BadgePaddingV = 3.dp

/**
 * Poster artwork with the app's card shape, and a letter in its place while there is no image.
 *
 * Shared by the phone card, the television card and `ui.common.Poster`, so a poster is drawn one
 * way everywhere. [contentDescription] is null wherever a title sits next to the image, which is
 * almost everywhere: describing the artwork as well makes a screen reader say the name twice.
 */
@Composable
internal fun PosterImage(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(
        modifier.clip(KaeruTokens.CardShape).background(KaeruElevated),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Text(
                title.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = KaeruSecondary,
            )
        } else {
            AsyncImage(
                model = kaeruImage(url),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(KaeruElevated),
                error = ColorPainter(KaeruElevated),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * A short fact stuck to the artwork: which episode is waiting, what the score is.
 *
 * Deliberately not amber. The accent belongs to the thing the viewer presses and to progress;
 * a badge is information, so it takes the background colour and stays out of the way.
 */
@Composable
internal fun CardBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = KaeruText,
        maxLines = 1,
        modifier = modifier
            .clip(KaeruTokens.ChipShape)
            .background(KaeruBackground.copy(alpha = 0.82f))
            .padding(horizontal = BadgePaddingH, vertical = BadgePaddingV),
    )
}

/** The overlays every poster card shares: the badge, then the progress strip along the bottom edge. */
@Composable
internal fun BoxScope.PosterOverlays(badge: String?, progress: Float?) {
    if (badge != null) {
        CardBadge(
            badge,
            Modifier.align(Alignment.BottomStart).padding(
                start = KaeruTokens.Space2,
                bottom = if (progress != null) KaeruTokens.Space2 + KaeruTokens.ProgressHeight else KaeruTokens.Space2,
            ),
        )
    }
    if (progress != null) {
        ProgressStrip(progress, Modifier.align(Alignment.BottomStart))
    }
}

/**
 * One of the two card shapes in the app: 2:3 artwork with its name under it.
 *
 * Everything optional is an overlay on the artwork rather than another line of text, so a row of
 * these stays the same height whether or not the titles in it have badges or progress. The title
 * sits a step below a row header in size, which is what keeps a row reading as one section with
 * several titles in it rather than several headings.
 *
 * [width] is the row pitch the design system fixes, which is what a horizontally scrolling row
 * wants: every card the same width whatever is beside it. A grid wants the opposite — the cell
 * decides — so pass `Dp.Unspecified` there along with `Modifier.fillMaxWidth()`, and the card
 * takes whatever width it is given.
 *
 * [titleMinLines] reserves that many lines for the name whether or not it needs them. A row leaves
 * it at one, because a row is read one card at a time and blank space under a short name would be
 * space for nothing. A grid passes two: there the cards sit side by side, and a neighbour whose
 * name runs to a second line drags everything under it — a subtitle, a card action — out of line
 * with the rest of the row.
 */
@Composable
fun PosterCard(
    posterUrl: String?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    progress: Float? = null,
    width: Dp = KaeruTokens.PosterWidthPhone,
    titleMinLines: Int = 1,
) {
    Column(
        modifier
            .then(if (width.isSpecified) Modifier.width(width) else Modifier)
            .clickable(onClick = onClick, onClickLabel = "Открыть", role = Role.Button),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(KaeruTokens.PosterAspect)
                .clip(KaeruTokens.CardShape),
        ) {
            PosterImage(posterUrl, title, Modifier.fillMaxSize())
            PosterOverlays(badge, progress)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = KaeruText,
            minLines = titleMinLines,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = KaeruTokens.Space2),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = KaeruSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
