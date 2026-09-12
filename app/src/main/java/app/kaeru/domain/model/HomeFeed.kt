package app.kaeru.domain.model

enum class FeedKind { CONTINUE, NEW_EPISODE, NEXT_UP, UPCOMING, PLANNED }

data class FeedItem(
    val entry: LibraryEntry,
    val episode: Int,
    val kind: FeedKind,
)

data class HomeFeed(
    val top: FeedItem?,
    val continueWatching: List<FeedItem>,
    val newEpisodes: List<FeedItem>,
    val nextUp: List<FeedItem>,
    val upcoming: List<FeedItem>,
    val planned: List<FeedItem>,
) {
    val isEmpty: Boolean get() = top == null && planned.isEmpty() && upcoming.isEmpty()

    companion object {
        val EMPTY = HomeFeed(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}
