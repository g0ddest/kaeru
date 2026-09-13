package app.kaeru.ui.mobile.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import app.kaeru.domain.discover.Season
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.PosterCard
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.SkeletonRow
import app.kaeru.ui.common.design.StatusPill
import app.kaeru.ui.common.design.seasonTitle
import app.kaeru.ui.common.home.DiscoverUiState

/**
 * How far the catalogue sits below the viewer's own rows.
 *
 * One step more than the 24dp between personal rows, and that gap is the only thing that says the
 * subject has changed: the row titles say the rest. A rule, a label or a card around the section
 * would be chrome announcing a change the content already announces.
 */
private val DiscoverGap = KaeruTokens.Space8

/**
 * A row of titles from the catalogue: heading, an optional switcher, and the cards.
 *
 * Deliberately the same shape as a personal row so the page keeps one rhythm all the way down —
 * the difference is in the cards, which carry no badge and no progress strip because there is no
 * position in a title nobody has started.
 */
@Composable
internal fun DiscoverSection(
    row: DiscoverRow,
    onAnime: (Int) -> Unit,
    modifier: Modifier = Modifier,
    top: Dp = KaeruTokens.Space6,
    switcher: (@Composable () -> Unit)? = null,
) {
    Column(modifier.padding(top = top)) {
        RowHeader(row.title)
        // Between the heading it belongs to and the cards it changes.
        switcher?.invoke()
        if (row.loading) {
            SkeletonRow(Modifier.padding(top = KaeruTokens.Space3))
        } else {
            LazyRow(
                modifier = Modifier.padding(top = KaeruTokens.Space3),
                contentPadding = PaddingValues(horizontal = KaeruTokens.GutterPhone),
                horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
            ) {
                items(row.cards, key = { it.animeId }) { card ->
                    PosterCard(
                        posterUrl = card.posterUrl,
                        title = card.title,
                        onClick = { onAnime(card.animeId) },
                        subtitle = card.subtitle,
                    )
                }
            }
        }
    }
}

/**
 * The season switcher: three chips reading left to right as a timeline.
 *
 * [StatusPill] with nothing trailing — the chevron it draws by default says «a menu opens here»,
 * and these do not open anything, they switch the row underneath. Selected is amber, which is the
 * same thing the accent says on the bottom bar: this is the one you are looking at.
 *
 * It scrolls because three seasons with four-digit years do not fit across a 360dp phone, and a
 * switcher that silently clipped its last option would hide the season most likely to be pressed.
 */
@Composable
internal fun SeasonChips(seasons: List<Season>, selected: Season, onSelect: (Season) -> Unit) {
    LazyRow(
        modifier = Modifier.padding(top = KaeruTokens.Space2),
        contentPadding = PaddingValues(horizontal = KaeruTokens.GutterPhone),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
    ) {
        items(seasons, key = { it.apiValue }) { season ->
            StatusPill(
                text = seasonTitle(season),
                selected = season == selected,
                onClick = { onSelect(season) },
                trailing = {},
            )
        }
    }
}

/**
 * Both catalogue rows, or fewer, or none.
 *
 * Placed in the same list as the feed rather than under it, so a viewer keeps scrolling into them
 * instead of hitting the end of one region and starting another. Whichever of the two comes first
 * takes the wider gap; the second sits at the ordinary row pitch, because the two of them are one
 * section and only its start is worth marking.
 */
internal fun LazyListScope.discoverSections(
    discover: DiscoverUiState,
    onAnime: (Int) -> Unit,
    onSeason: (Season) -> Unit,
) {
    val popular = popularNowRow(discover.popularNow, discover.loadingNow)
    val seasonal = seasonalRow(discover.seasonal, discover.loadingSeasonal)
    popular?.let { row ->
        item(key = "discover-now", contentType = DISCOVER_ROW) {
            DiscoverSection(row, onAnime, top = DiscoverGap)
        }
    }
    seasonal?.let { row ->
        item(key = "discover-season", contentType = DISCOVER_SEASON) {
            DiscoverSection(
                row = row,
                onAnime = onAnime,
                top = if (popular == null) DiscoverGap else KaeruTokens.Space6,
                switcher = { SeasonChips(discover.seasons, discover.season, onSeason) },
            )
        }
    }
}

private const val DISCOVER_ROW = "discover"
private const val DISCOVER_SEASON = "discover-season"
