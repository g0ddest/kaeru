package app.kaeru.ui.mobile.home

import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.HomeFeed
import app.kaeru.ui.common.design.pluralEpisodes
import app.kaeru.ui.common.design.relativeDay
import app.kaeru.ui.common.design.remainingLine
import java.time.Instant
import java.time.ZoneId

/** The rows of the phone's home screen, in the order they are read. */
private const val NEW_EPISODES = "Новые серии"
private const val CONTINUE = "Продолжить"
private const val NEXT_UP = "Дальше по списку"
private const val UPCOMING = "Скоро"
private const val PLANNED = "В планах"

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
