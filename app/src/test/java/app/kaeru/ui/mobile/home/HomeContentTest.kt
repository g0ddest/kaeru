package app.kaeru.ui.mobile.home

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.HomeFeed
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.ui.common.home.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

private const val OFFLINE = "Нет соединения. Проверьте интернет"

class HomeContentTest {
    private val now: Instant = Instant.parse("2026-09-13T20:00:00Z")

    private val item = FeedItem(
        LibraryEntry(
            Anime(1, "Дандадан", "Dandadan", null, emptyList(), AnimeStatus.ONGOING, 12, 9, null, 8.5, 2024, null, null),
            UserRate(1, 1, ListStatus.WATCHING, 8, now),
            null,
        ),
        episode = 9,
        kind = FeedKind.NEW_EPISODE,
    )

    private val watchable = HomeFeed(item, emptyList(), listOf(item), emptyList(), emptyList(), emptyList())

    @Test
    fun `nothing read yet is the loading state`() {
        assertEquals(HomeContent.Loading, homeContentState(HomeUiState(isLoading = true)))
    }

    @Test
    fun `a feed with titles in it is the feed`() {
        assertEquals(HomeContent.Feed, homeContentState(HomeUiState(feed = watchable, isLoading = false)))
    }

    @Test
    fun `a failed refresh over a feed with titles stays the feed`() {
        val state = HomeUiState(feed = watchable, isLoading = false, errorMessage = OFFLINE)
        assertEquals(HomeContent.Feed, homeContentState(state))
    }

    @Test
    fun `an empty feed after a failed refresh explains itself instead of inviting`() {
        val state = HomeUiState(isLoading = false, errorMessage = OFFLINE)
        assertEquals(HomeContent.Error(OFFLINE), homeContentState(state))
    }

    @Test
    fun `an empty feed with nothing wrong is the invitation`() {
        assertEquals(HomeContent.Empty, homeContentState(HomeUiState(isLoading = false)))
    }
}
