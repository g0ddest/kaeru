package app.kaeru.ui.tv

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TvActionsTest {

    private fun item(
        kind: FeedKind,
        episode: Int,
        status: AnimeStatus = AnimeStatus.ONGOING,
        episodes: Int = 12,
        aired: Int = 8,
    ) = FeedItem(
        LibraryEntry(
            Anime(1, "Тайтл", "Title", null, emptyList(), status, episodes, aired, null, null, 2026, null, null),
            UserRate(1L, 1, ListStatus.WATCHING, 4, Instant.EPOCH),
            null,
        ),
        episode = episode,
        kind = kind,
    )

    @Test
    fun `an episode already started is continued, not started again`() {
        val action = tvWatchAction(item(FeedKind.CONTINUE, episode = 5))
        assertEquals(TvWatchAction.Play(5, "Продолжить 5 серию"), action)
    }

    @Test
    fun `an episode not yet started is watched`() {
        assertEquals(
            TvWatchAction.Play(6, "Смотреть 6 серию"),
            tvWatchAction(item(FeedKind.NEW_EPISODE, episode = 6)),
        )
        assertEquals(
            TvWatchAction.Play(1, "Смотреть 1 серию"),
            tvWatchAction(item(FeedKind.PLANNED, episode = 1, status = AnimeStatus.RELEASED)),
        )
    }

    @Test
    fun `an episode that has not aired is not offered`() {
        assertEquals(TvWatchAction.NotAired, tvWatchAction(item(FeedKind.UPCOMING, episode = 9)))
    }

    @Test
    fun `a show with nothing aired at all is not offered either`() {
        val announced = item(FeedKind.PLANNED, episode = 1, status = AnimeStatus.ANONS, episodes = 0, aired = 0)
        assertEquals(TvWatchAction.NotAired, tvWatchAction(announced))
    }

    @Test
    fun `a finished show counts its whole run as available`() {
        val last = item(FeedKind.NEXT_UP, episode = 12, status = AnimeStatus.RELEASED, episodes = 12, aired = 0)
        assertEquals(TvWatchAction.Play(12, "Смотреть 12 серию"), tvWatchAction(last))
    }
}
