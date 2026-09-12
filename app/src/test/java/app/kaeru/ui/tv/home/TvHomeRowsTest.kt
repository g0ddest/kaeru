package app.kaeru.ui.tv.home

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.HomeFeed
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TvHomeRowsTest {
    private fun item(id: Int, kind: FeedKind) = FeedItem(
        LibraryEntry(
            Anime(id, "Аниме $id", "Anime $id", null, emptyList(), AnimeStatus.ONGOING, 12, 8, null, null, 2026, null, null),
            UserRate(id.toLong(), id, ListStatus.WATCHING, 4, Instant.EPOCH),
            null,
        ),
        episode = 5,
        kind = kind,
    )

    @Test
    fun `first row merges continue and new episodes without duplicates`() {
        val one = item(1, FeedKind.CONTINUE)
        val duplicate = item(1, FeedKind.NEW_EPISODE)
        val two = item(2, FeedKind.NEW_EPISODE)
        val feed = HomeFeed(one, listOf(one), listOf(duplicate, two), emptyList(), emptyList(), emptyList())
        val rows = tvHomeRows(feed)
        assertEquals("Смотреть сейчас", rows.first().title)
        assertEquals(listOf(1, 2), rows.first().items.map { it.entry.anime.id })
    }

    @Test
    fun `empty rows are omitted in stable order`() {
        val next = item(3, FeedKind.NEXT_UP)
        val planned = item(4, FeedKind.PLANNED)
        val rows = tvHomeRows(HomeFeed(next, emptyList(), emptyList(), listOf(next), emptyList(), listOf(planned)))
        assertEquals(listOf("Следующая серия", "В планах"), rows.map { it.title })
    }
}
