package app.kaeru.ui.tv

import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind

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
