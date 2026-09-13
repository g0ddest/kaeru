package app.kaeru.ui.tv.home

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.HomeFeed
import app.kaeru.ui.common.design.PrimaryAction
import app.kaeru.ui.common.design.episodeLine
import app.kaeru.ui.common.design.primaryAction
import app.kaeru.ui.common.home.HomeCard
import app.kaeru.ui.common.home.feedRows
import app.kaeru.ui.common.home.homeCard
import java.time.Instant
import java.time.ZoneId

/**
 * What the top of the television's home screen says about the card that has focus.
 *
 * Every word is decided here rather than during composition, because on a television this text
 * changes on every press of the D-pad: a hero that formatted itself would re-format five times
 * while the viewer scrubs along a row.
 *
 * [meta] and [action] are deliberately never both about the same thing. When something can be
 * started, the amber line says what starting it does and the grey one says where the viewer is in
 * it; when nothing can be started there is no amber line at all and the grey one carries the
 * reason — «9 серия выйдет завтра» — instead of shrugging twice.
 */
data class TvHero(
    val title: String,
    /** One short phrase under the title: which episode, how much is left, when the next lands. */
    val meta: String?,
    /** What one press of OK does, or null when there is nothing to start. */
    val action: String?,
    /** The picture behind it all. */
    val backdropUrl: String?,
)

/**
 * One card of a television row.
 *
 * It carries its own [hero] because the hero is a property of whichever card has focus, and
 * deriving it while focus moves would put four string builders on the D-pad's critical path.
 */
data class TvHomeCard(
    val animeId: Int,
    val title: String,
    val posterUrl: String?,
    /** «7 серия», stuck to the artwork. Null where an episode number means nothing. */
    val badge: String?,
    /** How far into the badged episode the viewer got, or null where there is nothing honest. */
    val progress: Float?,
    /**
     * The episode one press of OK starts, or null when there is nothing to start — an episode that
     * has not aired, or a title from the catalogue the viewer has never opened. Those open the
     * title card instead, which is the only thing a press could usefully do.
     */
    val playEpisode: Int?,
    val hero: TvHero,
)

/** A titled row. Never built empty, so the heading always has cards under it. */
data class TvHomeRow(val title: String, val items: List<TvHomeCard>)

/**
 * The picture behind the hero: a screenshot of the show, or its poster when there is none.
 *
 * A poster is the wrong shape for a backdrop and is cropped hard by it, which is exactly why it is
 * the fallback rather than the first choice — but a cropped poster still carries the colour and
 * the mood of the show, and a flat near-black panel carries neither.
 */
fun tvBackdrop(anime: Anime): String? = anime.screenshotUrls.firstOrNull() ?: anime.posterUrl

/** The hero for a title the viewer already has in their list. */
fun tvHero(
    item: FeedItem,
    threshold: Float,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): TvHero {
    val anime = item.entry.anime
    val action = primaryAction(item.entry, threshold, now, zone)
    val playable = tvPlayableEpisode(item, action) != null
    return TvHero(
        title = anime.title,
        // A disabled action is already a sentence about why nothing can start — «9 серия выйдет
        // завтра» — so the episode line beside it would say the same thing in other words. Where
        // the action is merely about a different episode than this card, the card's own line wins.
        meta = if (!playable && !action.enabled) action.label else episodeLine(item, now, zone),
        action = action.label.takeIf { playable },
        backdropUrl = tvBackdrop(anime),
    )
}

/**
 * The episode one press of OK on this card starts, or null.
 *
 * The card, the hero and the player have to agree about which episode «one press» means, so the
 * answer is the feed's own episode and it counts only when the watch control offers that same
 * episode. The two come apart on exactly one row: a card in «Скоро» is about the episode that has
 * not aired yet, while the title behind it may still have four aired episodes nobody has watched.
 * Playing those from a card that says «9 серия завтра» would start something the card never
 * offered, so that press opens the title card instead.
 */
private fun tvPlayableEpisode(item: FeedItem, action: PrimaryAction): Int? =
    item.episode.takeIf { action.enabled && action.episode == it }

/**
 * The hero for a title out of the catalogue, which the viewer has never opened.
 *
 * No amber line: pressing OK here opens the title card, and promising «Смотреть» for something
 * with no episode chosen and no dub picked would be a promise made a screen too early. The card's
 * own quiet line — how long the season is, or who made it — is the whole of what is known.
 */
fun tvHero(card: HomeCard): TvHero = TvHero(
    title = card.title,
    meta = card.subtitle,
    action = null,
    backdropUrl = card.posterUrl,
)

/**
 * The television's own rows, out of the same feed under the same headings as the phone's.
 *
 * The headings, their order and what each card says come from `ui.common.home`, so the two screens
 * cannot disagree about which titles are «Новые серии» or which episode a badge names. What the
 * television adds is the hero each card will show and the episode one press would start.
 */
fun tvHomeRows(
    feed: HomeFeed,
    threshold: Float,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): List<TvHomeRow> = feedRows(feed).map { row ->
    TvHomeRow(
        title = row.title,
        items = row.items.map { item -> tvCard(item, threshold, now, zone) },
    )
}

/** One catalogue row's cards, which carry artwork and a name and nothing the viewer owns. */
fun tvDiscoverCards(cards: List<HomeCard>): List<TvHomeCard> = cards.map { card ->
    TvHomeCard(
        animeId = card.animeId,
        title = card.title,
        posterUrl = card.posterUrl,
        badge = null,
        progress = null,
        playEpisode = null,
        hero = tvHero(card),
    )
}

private fun tvCard(item: FeedItem, threshold: Float, now: Instant, zone: ZoneId): TvHomeCard {
    val card = homeCard(item, threshold, now, zone)
    val action = primaryAction(item.entry, threshold, now, zone)
    return TvHomeCard(
        animeId = card.animeId,
        title = card.title,
        posterUrl = card.posterUrl,
        badge = card.badge,
        progress = card.progress,
        playEpisode = tvPlayableEpisode(item, action),
        hero = tvHero(item, threshold, now, zone),
    )
}
