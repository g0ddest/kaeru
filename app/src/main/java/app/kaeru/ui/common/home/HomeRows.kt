package app.kaeru.ui.common.home

import app.kaeru.domain.discover.Season
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.HomeFeed
import app.kaeru.ui.common.design.pluralEpisodes
import app.kaeru.ui.common.design.relativeDay
import app.kaeru.ui.common.design.remainingLine
import app.kaeru.ui.common.home.DiscoverUiState
import java.time.Instant
import java.time.ZoneId

/** The rows of the phone's home screen, in the order they are read. */
private const val NEW_EPISODES = "Новые серии"
private const val CONTINUE = "Продолжить"
private const val NEXT_UP = "Дальше по списку"
private const val UPCOMING = "Скоро"
private const val PLANNED = "В планах"

/** The two rows about the catalogue rather than about the viewer, in the order they are read. */
private const val POPULAR_NOW = "Популярно сейчас"
private const val POPULAR_IN_SEASON = "Популярное в сезоне"

/** What an upcoming card says when the catalogue has no date for the next episode. */
private const val SOON = "скоро"

/**
 * One card of a home row, with every word on it already decided.
 *
 * The screen draws this and nothing else: no formatting happens during composition, so what a card
 * says can be read in a test instead of on a device.
 */
data class HomeCard(
    val animeId: Int,
    val title: String,
    val posterUrl: String?,
    /** Which episode this card is about, stuck to the artwork. Null where an episode means nothing. */
    val badge: String? = null,
    /** One short fact under the title — time left, release day, season length. Never two joined. */
    val subtitle: String? = null,
    /** How far into the badged episode the viewer is, or null when nothing honest can be shown. */
    val progress: Float? = null,
)

/** A titled row of cards. A row with nothing in it is never built, so the title always has content. */
data class HomeRow(val title: String, val items: List<HomeCard>)

/**
 * The home screen's rows, built from the feed and the clock rather than from the composition.
 *
 * Two rules live here rather than in the screen:
 *
 * 1. **A card says only what its row cannot.** The row already says why these titles are here, so
 *    a card adds the one fact the row leaves open: which episode arrived, how much of it is left,
 *    which day the next one comes, how long the season runs.
 * 2. **The strip and the badge talk about the same episode.** Progress is shown only in
 *    «Продолжить», where the badged episode is the one being watched; on an upcoming card the
 *    badge names an episode that has not aired, so a strip there would describe a different one.
 */
fun homeRows(
    feed: HomeFeed,
    threshold: Float,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): List<HomeRow> = buildList {
    row(NEW_EPISODES, feed.newEpisodes) { card(it, badge = episodeBadge(it.episode)) }
    row(CONTINUE, feed.continueWatching) {
        card(
            it,
            badge = episodeBadge(it.episode),
            subtitle = it.remaining(),
            progress = it.entry.progressFraction(threshold),
        )
    }
    row(NEXT_UP, feed.nextUp) { card(it, badge = episodeBadge(it.episode)) }
    row(UPCOMING, feed.upcoming) {
        val day = it.entry.anime.nextEpisodeAt?.let { at -> relativeDay(at, now, zone) } ?: SOON
        card(it, badge = episodeBadge(it.episode), subtitle = day)
    }
    row(PLANNED, feed.planned) { card(it, subtitle = it.seasonLength()) }
}

/** Adds a row, or nothing at all: an empty row is a heading with a gap under it. */
private fun MutableList<HomeRow>.row(
    title: String,
    items: List<FeedItem>,
    card: (FeedItem) -> HomeCard,
) {
    if (items.isNotEmpty()) add(HomeRow(title, items.map(card)))
}

private fun card(
    item: FeedItem,
    badge: String? = null,
    subtitle: String? = null,
    progress: Float? = null,
): HomeCard = HomeCard(
    animeId = item.entry.anime.id,
    title = item.entry.anime.title,
    posterUrl = item.entry.anime.posterUrl,
    badge = badge,
    subtitle = subtitle,
    progress = progress,
)

/** «7 серия» — the episode this card is about, as an ordinal rather than a count. */
private fun episodeBadge(episode: Int): String = "$episode серия"

/** How much of the badged episode is left, and nothing at all about any other episode. */
private fun FeedItem.remaining(): String? = entry.watch
    ?.takeIf { it.episode == episode }
    ?.let { remainingLine(it.positionMs, it.durationMs) }

/** «24 серии» for a planned title, or nothing when the catalogue does not know the length yet. */
private fun FeedItem.seasonLength(): String? {
    val season = entry.anime.episodes.takeIf { it > 0 } ?: entry.anime.availableEpisodes
    return season.takeIf { it > 0 }?.let(::pluralEpisodes)
}

/**
 * What sits under a discovery row's heading.
 *
 * Four states rather than a list and a flag, because the seasonal row genuinely has four and the
 * invariant «cards are empty exactly when loading» stopped being true the moment a season could
 * answer with nothing. Exhaustive `when`s in the screen are the point.
 */
sealed interface DiscoverContent {
    /** Titles to draw. Never empty: an empty answer is [Empty]. */
    data class Titles(val cards: List<HomeCard>) : DiscoverContent

    /** A skeleton under a heading that is already readable. */
    data object Loading : DiscoverContent

    /** The catalogue answered and had nothing. Said out loud only where a switcher can act on it. */
    data object Empty : DiscoverContent

    /**
     * There is nothing and nothing is coming.
     *
     * Almost always a read that failed, which is what the seasonal row says out loud. It also
     * covers the frame before anything has been asked for at all — a state both rows hide, so the
     * word «failed» never reaches a screen while it is true.
     */
    data object Failed : DiscoverContent
}

/**
 * A row of titles the viewer has not added to anything.
 *
 * Kept apart from [HomeRow] because the two answer different questions and are drawn differently.
 * A personal row is built from a list already on the device, so it either exists or does not; a
 * discovery row arrives from the network after the screen is up, which is why this one carries
 * [DiscoverContent] and [HomeRow] does not.
 */
data class DiscoverRow(val title: String, val content: DiscoverContent)

/**
 * Everything the catalogue part of the screen draws: two rows, each null when it has nothing at
 * all to stand on, and the switcher that belongs to the second one.
 *
 * The chips travel with the rows rather than being read off the state again at draw time, so the
 * screen has one thing to remember and one thing to check for null.
 */
data class DiscoverRows(
    val popularNow: DiscoverRow?,
    val seasonal: DiscoverRow?,
    val season: Season,
    val seasons: List<Season>,
)

/**
 * The catalogue part of the home screen, decided where it can be read in a test.
 *
 * The two rows are quiet in different amounts, and that is deliberate. «Популярно сейчас» has no
 * controls, so it has nothing to say about its own failure and simply goes away. The seasonal row
 * owns the season switcher, so once that switcher has been useful it stays: a chip press that
 * comes back empty, or fails, leaves the viewer able to press their way somewhere else instead of
 * deleting the thing they pressed.
 */
fun discoverRows(discover: DiscoverUiState): DiscoverRows = DiscoverRows(
    popularNow = popularNowRow(discover.popularNow, discover.loadingNow),
    seasonal = seasonalRow(discover.seasonal, discover.loadingSeasonal, discover.anySeasonLoaded),
    season = discover.season,
    seasons = discover.seasons,
)

/** «Популярно сейчас» — what is airing, most watched first. It has no controls, so it says nothing. */
private fun popularNowRow(titles: List<Anime>?, loading: Boolean): DiscoverRow? {
    val content = content(titles, loading)
    if (content == DiscoverContent.Empty || content == DiscoverContent.Failed) return null
    return DiscoverRow(POPULAR_NOW, content)
}

/**
 * «Популярное в сезоне» — the chips say which season.
 *
 * [anySeasonLoaded] is what keeps the switcher alive. The very first read failing means there has
 * never been anything here, so the whole block goes; a later one failing means the viewer has a
 * switcher on screen, and pressing it is the way out — taking it away would be a dead end.
 */
private fun seasonalRow(titles: List<Anime>?, loading: Boolean, anySeasonLoaded: Boolean): DiscoverRow? {
    val content = content(titles, loading)
    if (content == DiscoverContent.Failed && !anySeasonLoaded) return null
    return DiscoverRow(POPULAR_IN_SEASON, content)
}

/**
 * Titles, a skeleton, an empty answer, or nothing.
 *
 * Titles win over [loading]: a pull-to-refresh reloads a row that is already on screen, and
 * blanking it to a skeleton would take away something readable in exchange for nothing. A non-null
 * empty list is an answer and never reads as a failure; a null with nothing on its way is the one
 * that does.
 */
private fun content(titles: List<Anime>?, loading: Boolean): DiscoverContent = when {
    !titles.isNullOrEmpty() -> DiscoverContent.Titles(titles.map(::discoverCard))
    loading -> DiscoverContent.Loading
    titles != null -> DiscoverContent.Empty
    else -> DiscoverContent.Failed
}

/**
 * A catalogue card: artwork, name, and one fact about the size of the thing.
 *
 * No badge and no progress strip, and not only because there is no progress to show — the clean
 * artwork is what separates «titles you are in the middle of» from «titles you have never opened»
 * as the eye goes down the screen, with no heading needed to say so.
 */
private fun discoverCard(anime: Anime): HomeCard = HomeCard(
    animeId = anime.id,
    title = anime.title,
    posterUrl = anime.posterUrl,
    subtitle = anime.catalogueLine(),
)

/**
 * How much show there is: episodes there are to watch, or who is making it when none have aired.
 *
 * Shikimori's short serialisation carries no studio, so in practice a title that has not started
 * shows its name alone — which is the whole truth about an announcement, and better than a `0`.
 */
private fun Anime.catalogueLine(): String? =
    availableEpisodes.takeIf { it > 0 }?.let(::pluralEpisodes) ?: studio
