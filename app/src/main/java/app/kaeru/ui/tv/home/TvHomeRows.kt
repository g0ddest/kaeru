package app.kaeru.ui.tv.home

import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.HomeFeed

data class TvHomeRow(val title: String, val items: List<FeedItem>)

fun tvHomeRows(feed: HomeFeed): List<TvHomeRow> = buildList {
    val watchNow = (feed.continueWatching + feed.newEpisodes).distinctBy { it.entry.anime.id }
    if (watchNow.isNotEmpty()) add(TvHomeRow("Смотреть сейчас", watchNow))
    if (feed.nextUp.isNotEmpty()) add(TvHomeRow("Следующая серия", feed.nextUp))
    if (feed.upcoming.isNotEmpty()) add(TvHomeRow("Скоро", feed.upcoming))
    if (feed.planned.isNotEmpty()) add(TvHomeRow("В планах", feed.planned))
}
