package app.kaeru.ui.tv

import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.HomeFeed
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.ui.common.details.episodeCells

/**
 * The share of an episode that counts as watched, when nothing on this screen has read the
 * preference. The television's title card has no view model of its own; the number that
 * actually governs progress lives in `AppPreferences.watchedThreshold` and is applied by the
 * player and the feed builder, which is where it matters.
 */
const val TV_WATCHED_THRESHOLD = 0.9f

/**
 * What the watch control on a home card or a title card does, and what it says.
 *
 * The episode is the feed's own — the same number the card shows — so the button, the card and
 * the player can never disagree about which episode "one press" starts.
 */
sealed interface TvWatchAction {
    data class Play(val episode: Int, val label: String) : TvWatchAction

    /** The episode the feed points at is still to come, so there is nothing to start. */
    data object NotAired : TvWatchAction
}

fun tvWatchAction(item: FeedItem): TvWatchAction {
    if (item.episode > item.entry.anime.availableEpisodes) return TvWatchAction.NotAired
    val verb = if (item.kind == FeedKind.CONTINUE) "Продолжить" else "Смотреть"
    return TvWatchAction.Play(item.episode, "$verb ${item.episode} серию")
}

/** One cell of the title card's episode grid. */
data class TvEpisodeCell(
    val episode: Int,
    /** Out already, so there is something to play. Anything watched counts, whatever the catalogue says. */
    val aired: Boolean,
    val watched: Boolean,
    /** How far into this episode the viewer got, or null when they have not really started it. */
    val progress: Float?,
)

/**
 * The season as a grid, as the television draws it.
 *
 * The rule itself lives in `ui.common.details`, shared with the phone: this is only the shape
 * change between the two cell types. Two implementations of «which episodes exist, which are
 * behind the viewer, which one is in progress» would drift the day one of them is fixed.
 */
fun tvEpisodeGrid(entry: LibraryEntry, watchedThreshold: Float = TV_WATCHED_THRESHOLD): List<TvEpisodeCell> =
    episodeCells(entry.anime, entry.rate, entry.watch, entry.progress, watchedThreshold).map { cell ->
        TvEpisodeCell(
            episode = cell.number,
            aired = cell.aired,
            watched = cell.watched,
            progress = cell.progress,
        )
    }

/**
 * The feed entry for one anime, wherever it sits. The television remembers which title card was
 * open as an id, so after the process is killed the card can be found again in the feed Room
 * hands back rather than being lost with the composition.
 */
fun tvFeedItem(feed: HomeFeed, animeId: Int): FeedItem? =
    (feed.continueWatching + feed.newEpisodes + feed.nextUp + feed.upcoming + feed.planned)
        .firstOrNull { it.entry.anime.id == animeId }
