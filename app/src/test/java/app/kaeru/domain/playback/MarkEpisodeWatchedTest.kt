package app.kaeru.domain.playback

import app.kaeru.domain.error.AccountSessionChanged
import app.kaeru.domain.error.HttpError
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.test.MutableClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class MarkEpisodeWatchedTest {
    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val clock = MutableClock(now)
    private val library = FakeLibraryRepository()
    private val watchStates = FakeWatchStateRepository()
    private val mark = MarkEpisodeWatched(library, watchStates, clock)

    private fun anime(id: Int = 100, episodes: Int = 12, status: AnimeStatus = AnimeStatus.RELEASED) = Anime(
        id = id, nameRu = "Имя", nameRomaji = "Name", posterUrl = null, screenshotUrls = emptyList(),
        status = status, episodes = episodes, episodesAired = episodes, nextEpisodeAt = null,
        score = null, year = null, studio = null, description = null,
    )

    private fun seed(
        status: ListStatus = ListStatus.WATCHING,
        episodes: Int = 3,
        anime: Anime = anime(),
        watch: WatchState? = null,
    ) = library.put(LibraryEntry(anime, UserRate(1, anime.id, status, episodes, now), watch))

    private class FakeLibraryRepository : LibraryRepository {
        private val entries = MutableStateFlow<Map<Int, LibraryEntry>>(emptyMap())
        val calls = mutableListOf<String>()
        var statusResult: Result<Unit> = Result.success(Unit)
        var episodesResult: Result<Unit> = Result.success(Unit)

        /** The card Shikimori returns for an anime that was not in the list yet. */
        var createdAnime: Anime? = null

        fun put(entry: LibraryEntry) = entries.update { it + (entry.anime.id to entry) }

        fun entry(animeId: Int): LibraryEntry? = entries.value[animeId]

        override fun observeAnime(id: Int): Flow<LibraryEntry?> = entries.map { it[id] }

        override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> {
            calls += "status:$animeId:${status.apiValue}"
            if (statusResult.isFailure) return statusResult
            entries.update { current ->
                val existing = current[animeId]
                val updated = when {
                    existing != null -> existing.copy(rate = existing.rate.copy(status = status))
                    // Shikimori creates the rate and the app caches the card it answered with.
                    else -> LibraryEntry(
                        anime = createdAnime ?: error("No anime $animeId"),
                        rate = UserRate(99, animeId, status, 0, Instant.EPOCH),
                        watch = null,
                    )
                }
                current + (animeId to updated)
            }
            return statusResult
        }

        override suspend fun setEpisodes(animeId: Int, episodes: Int): Result<Unit> {
            calls += "episodes:$animeId:$episodes"
            if (episodesResult.isFailure) return episodesResult
            val existing = entries.value[animeId] ?: return Result.failure(IllegalStateException("No user_rate"))
            entries.update { it + (animeId to existing.copy(rate = existing.rate.copy(episodes = episodes))) }
            return episodesResult
        }

        override fun observeLibrary(): Flow<List<LibraryEntry>> = throw NotImplementedError()
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = throw NotImplementedError()
        override suspend fun refresh(): Result<Unit> = throw NotImplementedError()
        override suspend fun refreshAnime(id: Int): Result<Unit> = throw NotImplementedError()
        override suspend fun search(query: String): Result<List<Anime>> = throw NotImplementedError()
    }

    @Test
    fun `a watched episode is counted in shikimori`() = runTest {
        seed(episodes = 3)

        val outcome = mark(animeId = 100, episode = 4).getOrThrow()

        assertEquals(listOf("episodes:100:4"), library.calls)
        assertEquals(WatchedOutcome(4, movedToWatching = false, suggestCompleted = false), outcome)
        assertEquals(4, library.entry(100)!!.rate.episodes)
    }

    @Test
    fun `an episode shikimori already counted is not sent again`() = runTest {
        seed(episodes = 5)

        val outcome = mark(animeId = 100, episode = 4).getOrThrow()

        assertEquals(emptyList<String>(), library.calls)
        assertEquals(WatchedOutcome(4, movedToWatching = false, suggestCompleted = false), outcome)
    }

    @Test
    fun `marking the same episode twice sends one request`() = runTest {
        seed(episodes = 3)

        mark(animeId = 100, episode = 4).getOrThrow()
        val second = mark(animeId = 100, episode = 4).getOrThrow()

        assertEquals(listOf("episodes:100:4"), library.calls)
        assertFalse(second.suggestCompleted)
    }

    @Test
    fun `the first episode of a planned anime moves it to watching`() = runTest {
        seed(status = ListStatus.PLANNED, episodes = 0)

        val outcome = mark(animeId = 100, episode = 1).getOrThrow()

        assertEquals(listOf("status:100:watching", "episodes:100:1"), library.calls)
        assertTrue(outcome.movedToWatching)
    }

    @Test
    fun `an on hold anime is picked back up before marking`() = runTest {
        seed(status = ListStatus.ON_HOLD, episodes = 3)

        val outcome = mark(animeId = 100, episode = 4).getOrThrow()

        assertEquals(listOf("status:100:watching", "episodes:100:4"), library.calls)
        assertTrue(outcome.movedToWatching)
    }

    @Test
    fun `a planned anime whose count already covers the episode is still picked back up`() = runTest {
        seed(status = ListStatus.PLANNED, episodes = 5)

        val outcome = mark(animeId = 100, episode = 4).getOrThrow()

        assertEquals(listOf("status:100:watching"), library.calls)
        assertTrue(outcome.movedToWatching)
        assertFalse(outcome.suggestCompleted)
    }

    @Test
    fun `a rewatch is left in its own status`() = runTest {
        seed(status = ListStatus.REWATCHING, episodes = 3)

        val outcome = mark(animeId = 100, episode = 4).getOrThrow()

        assertEquals(listOf("episodes:100:4"), library.calls)
        assertFalse(outcome.movedToWatching)
    }

    @Test
    fun `an anime that was never in the list is added as watching first`() = runTest {
        library.createdAnime = anime(episodes = 12)

        val outcome = mark(animeId = 100, episode = 1).getOrThrow()

        assertEquals(listOf("status:100:watching", "episodes:100:1"), library.calls)
        assertTrue(outcome.movedToWatching)
        assertFalse(outcome.suggestCompleted)
        assertEquals(1, library.entry(100)!!.rate.episodes)
    }

    @Test
    fun `the last episode only suggests completing, it never completes by itself`() = runTest {
        seed(episodes = 11, anime = anime(episodes = 12))

        val outcome = mark(animeId = 100, episode = 12).getOrThrow()

        assertEquals(listOf("episodes:100:12"), library.calls)
        assertTrue(outcome.suggestCompleted)
        assertEquals(ListStatus.WATCHING, library.entry(100)!!.rate.status)
    }

    @Test
    fun `a finale shikimori already counted does not offer the dialog a second time`() = runTest {
        seed(episodes = 12, anime = anime(episodes = 12))

        val outcome = mark(animeId = 100, episode = 12).getOrThrow()

        assertEquals(emptyList<String>(), library.calls)
        assertEquals(WatchedOutcome(12, movedToWatching = false, suggestCompleted = false), outcome)
    }

    @Test
    fun `an episode past the announced count also suggests completing`() = runTest {
        seed(episodes = 12, anime = anime(episodes = 12))

        val outcome = mark(animeId = 100, episode = 13).getOrThrow()

        assertTrue(outcome.suggestCompleted)
    }

    @Test
    fun `an ongoing anime with no announced count never suggests completing`() = runTest {
        seed(episodes = 3, anime = anime(episodes = 0, status = AnimeStatus.ONGOING))

        val outcome = mark(animeId = 100, episode = 4).getOrThrow()

        assertFalse(outcome.suggestCompleted)
    }

    @Test
    fun `a failed status change stops before anything is counted`() = runTest {
        seed(status = ListStatus.PLANNED, episodes = 0)
        val failure = HttpError(422)
        library.statusResult = Result.failure(failure)

        val result = mark(animeId = 100, episode = 1)

        assertSame(failure, result.exceptionOrNull())
        assertEquals(listOf("status:100:watching"), library.calls)
    }

    @Test
    fun `a failed count is handed back as the failure it was`() = runTest {
        seed(episodes = 3)
        val failure = HttpError(500)
        library.episodesResult = Result.failure(failure)

        val result = mark(animeId = 100, episode = 4)

        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun `a position left in an earlier episode is rewound but its track is remembered`() = runTest {
        val stale = WatchState(100, 3, 600_000, 1_400_000, translationId = 7, kodikSeason = 2, updatedAt = now)
        seed(episodes = 4, watch = stale)
        watchStates.seed(stale)
        clock.advance(Duration.ofMinutes(5))

        mark(animeId = 100, episode = 5).getOrThrow()

        assertEquals(
            WatchState(100, 3, 0, 0, translationId = 7, kodikSeason = 2, updatedAt = clock.now),
            watchStates.saved.single(),
        )
        assertEquals(emptyList<Int>(), watchStates.cleared)
    }

    @Test
    fun `the position of the episode being marked is left for the player`() = runTest {
        val current = WatchState(100, 5, 1_300_000, 1_400_000, 7, 1, now)
        seed(episodes = 4, watch = current)
        watchStates.seed(current)

        mark(animeId = 100, episode = 5).getOrThrow()

        assertEquals(emptyList<WatchState>(), watchStates.started)
        assertEquals(emptyList<Int>(), watchStates.cleared)
    }

    @Test
    fun `a rewind that cannot be written does not undo the marking`() = runTest {
        val stale = WatchState(100, 3, 600_000, 1_400_000, 7, 2, now)
        seed(episodes = 4, watch = stale)
        watchStates.seed(stale)
        watchStates.failSaveWith = AccountSessionChanged("signed out")

        val outcome = mark(animeId = 100, episode = 5).getOrThrow()

        assertEquals(5, outcome.markedEpisode)
        assertEquals(listOf("episodes:100:5"), library.calls)
    }
}
