package app.kaeru.ui.common.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText

/**
 * The poster card as a television sees it.
 *
 * Focus is shown twice over, because from three metres away one signal is not enough: the artwork
 * grows by six per cent and takes a 3dp amber border. No glow — a bloom around every card in a row
 * turns the row into a smear, and the border is already unambiguous.
 *
 * Only the artwork is inside the focusable surface, so the name under it stays put while the
 * picture lifts. [onLongClick] is the quick menu the spec asks for on a long press of OK.
 *
 * [width] is the row pitch the design system fixes, which is what a horizontally scrolling row
 * wants: every card the same width whatever is beside it. A grid wants the opposite — the cell
 * decides — so pass `Dp.Unspecified` there along with `Modifier.fillMaxWidth()`.
 *
 * [titleMaxLines] is one on a screen where the row's height has to be known in advance — the
 * immersive home, where the hero above the rows is already showing the focused title in full and a
 * card that ran to a second line would push its own caption off the bottom of the panel.
 */
@Composable
fun TvPosterCard(
    posterUrl: String?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    subtitle: String? = null,
    badge: String? = null,
    progress: Float? = null,
    width: Dp = KaeruTokens.PosterWidthTv,
    titleMaxLines: Int = 2,
) {
    Column(modifier.then(if (width.isSpecified) Modifier.width(width) else Modifier)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(KaeruTokens.PosterAspect),
            shape = ClickableSurfaceDefaults.shape(shape = KaeruTokens.CardShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = KaeruElevated,
                contentColor = KaeruText,
                focusedContainerColor = KaeruElevated,
                focusedContentColor = KaeruText,
                pressedContainerColor = KaeruElevated,
                pressedContentColor = KaeruText,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = KaeruTokens.FocusScale),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(KaeruTokens.FocusBorder, KaeruAccent),
                    shape = KaeruTokens.CardShape,
                ),
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                PosterImage(posterUrl, title, Modifier.fillMaxSize())
                PosterOverlays(badge, progress)
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = KaeruText,
            maxLines = titleMaxLines,
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
