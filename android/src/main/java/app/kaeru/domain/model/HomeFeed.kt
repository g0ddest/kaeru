package app.kaeru.domain.model

enum class FeedKind { CONTINUE, NEW_EPISODE, NEXT_UP, UPCOMING, PLANNED, DOWNLOADED }

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
    /**
     * Episodes already on the device and still ahead of the viewer, newest download first.
     *
     * The one row that answers «что я могу начать прямо сейчас без сети», which is why it is not
     * folded into the others: an episode can be downloaded and also next up, and the two rows are
     * saying different things about it. Defaulted empty so a preview, a television or a test that
     * has nothing to do with downloads builds a feed the way it always did.
     */
    val downloaded: List<FeedItem> = emptyList(),
) {
    /**
     * Nothing worth drawing a home screen around.
     *
     * Downloads count, and they are the reason this is not just about [top]: a viewer whose whole
     * list is finished but who has three episodes on the phone for the flight has a home screen,
     * and «Список пуст» over it would be false.
     */
    val isEmpty: Boolean
        get() = top == null && planned.isEmpty() && upcoming.isEmpty() && downloaded.isEmpty()

    companion object {
        val EMPTY = HomeFeed(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}
