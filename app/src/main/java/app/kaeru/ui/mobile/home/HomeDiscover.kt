package app.kaeru.ui.mobile.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import app.kaeru.domain.discover.Season
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.PosterCard
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.SkeletonCardsRow
import app.kaeru.ui.common.design.StatusPill
import app.kaeru.ui.common.design.TextAction
import app.kaeru.ui.common.design.seasonTitle
import app.kaeru.ui.common.home.DiscoverContent
import app.kaeru.ui.common.home.DiscoverRow
import app.kaeru.ui.common.home.DiscoverRows
import app.kaeru.ui.common.home.HomeCard
import app.kaeru.ui.common.theme.KaeruSecondary

/** What a season with nothing indexed in it says, which is the plain truth and no apology. */
private const val SEASON_EMPTY = "В этом сезоне пока ничего нет"

/** What a season that would not load says, next to the way to ask again. */
private const val SEASON_FAILED = "Не удалось загрузить сезон"
private const val RETRY = "Повторить"

/**
 * How far the catalogue sits below the viewer's own rows.
 *
 * One step more than the 24dp between personal rows, and that gap is the only thing that says the
 * subject has changed: the row titles say the rest. A rule, a label or a card around the section
 * would be chrome announcing a change the content already announces.
 */
private val DiscoverGap = KaeruTokens.Space8

/**
 * A row of titles from the catalogue: heading, an optional switcher, and whatever is under it.
 *
 * Deliberately the same shape as a personal row so the page keeps one rhythm all the way down —
 * the difference is in the cards, which carry no badge and no progress strip because there is no
 * position in a title nobody has started.
 *
 * The heading is always the real one, including while loading: it is readable a beat before the
 * titles are, and it is what stops the page moving when they land. The skeleton underneath is
 * therefore the header-less [SkeletonCardsRow] — the default [app.kaeru.ui.common.design.SkeletonRow]
 * draws a grey bar standing in for a heading, and two headings is one more than this row has.
 */
@Composable
internal fun DiscoverSection(
    row: DiscoverRow,
    onAnime: (Int) -> Unit,
    modifier: Modifier = Modifier,
    top: Dp = KaeruTokens.Space6,
    onRetry: (() -> Unit)? = null,
    switcher: (@Composable () -> Unit)? = null,
) {
    Column(modifier.padding(top = top)) {
        RowHeader(row.title)
        // Between the heading it belongs to and the content it changes.
        switcher?.invoke()
        when (val content = row.content) {
            is DiscoverContent.Titles -> Cards(content.cards, onAnime)
            DiscoverContent.Loading -> SkeletonCardsRow(Modifier.padding(top = KaeruTokens.Space3))
            DiscoverContent.Empty -> Note(SEASON_EMPTY)
            DiscoverContent.Failed -> {
                Note(SEASON_FAILED)
                // Only a row with a way to ask again offers one. «Популярно сейчас» has no
                // controls of its own, so it never reaches this branch — it is simply absent.
                if (onRetry != null) {
                    TextAction(RETRY, onRetry, Modifier.padding(start = KaeruTokens.GutterPhone - KaeruTokens.Space3))
                }
            }
        }
    }
}

@Composable
private fun Cards(cards: List<HomeCard>, onAnime: (Int) -> Unit) {
    LazyRow(
        modifier = Modifier.padding(top = KaeruTokens.Space3),
        contentPadding = PaddingValues(horizontal = KaeruTokens.GutterPhone),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
    ) {
        items(cards, key = { it.animeId }) { card ->
            PosterCard(
                posterUrl = card.posterUrl,
                title = card.title,
                onClick = { onAnime(card.animeId) },
                subtitle = card.subtitle,
            )
        }
    }
}

/**
 * One quiet line where the cards would be.
 *
 * Secondary text at the row's own gutter rather than a centred [app.kaeru.ui.common.design.EmptyState]:
 * this is a row reporting on itself inside a page that is working, not a screen with nothing on it,
 * and centring it would give it the weight of a screen.
 */
@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = KaeruSecondary,
        modifier = Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space3),
    )
}

/**
 * The season switcher: three chips reading left to right as a timeline.
 *
 * [StatusPill] with nothing trailing — the chevron it draws by default says «a menu opens here»,
 * and these do not open anything, they switch the row underneath. Selected is amber, which is the
 * same thing the accent says on the bottom bar: this is the one you are looking at.
 *
 * `selectableGroup` with [Role.RadioButton] so a screen reader announces «2 of 3» rather than three
 * unrelated controls; one of the three is always chosen, which is what a radio group means.
 *
 * It scrolls because three seasons with four-digit years do not fit across a 360dp phone, and a
 * switcher that silently clipped its last option would hide the season most likely to be pressed.
 */
@Composable
internal fun SeasonChips(seasons: List<Season>, selected: Season, onSelect: (Season) -> Unit) {
    LazyRow(
        modifier = Modifier.padding(top = KaeruTokens.Space2).selectableGroup(),
        contentPadding = PaddingValues(horizontal = KaeruTokens.GutterPhone),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
    ) {
        items(seasons, key = { it.apiValue }) { season ->
            StatusPill(
                text = seasonTitle(season),
                selected = season == selected,
                onClick = { onSelect(season) },
                role = Role.RadioButton,
                trailing = {},
            )
        }
    }
}

/**
 * Both catalogue rows, or fewer, or none.
 *
 * Placed in the same list as whatever is above them rather than under it, so a viewer keeps
 * scrolling into them instead of hitting the end of one region and starting another. Whichever of
 * the two comes first takes the wider gap; the second sits at the ordinary row pitch, because the
 * two of them are one section and only its start is worth marking.
 *
 * [rows] is passed in already built: the mapping allocates a card per title and formats a line per
 * card, and this content lambda runs on every recomposition of the screen.
 */
internal fun LazyListScope.discoverSections(
    rows: DiscoverRows,
    onAnime: (Int) -> Unit,
    onSeason: (Season) -> Unit,
    onRetrySeason: () -> Unit,
) {
    rows.popularNow?.let { row ->
        item(key = "discover-now", contentType = DISCOVER_ROW) {
            DiscoverSection(row, onAnime, top = DiscoverGap)
        }
    }
    rows.seasonal?.let { row ->
        item(key = "discover-season", contentType = DISCOVER_SEASON) {
            DiscoverSection(
                row = row,
                onAnime = onAnime,
                top = if (rows.popularNow == null) DiscoverGap else KaeruTokens.Space6,
                onRetry = onRetrySeason,
                switcher = { SeasonChips(rows.seasons, rows.season, onSeason) },
            )
        }
    }
}

private const val DISCOVER_ROW = "discover"
private const val DISCOVER_SEASON = "discover-season"
