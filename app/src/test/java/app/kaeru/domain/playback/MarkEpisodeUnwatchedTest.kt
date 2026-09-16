package app.kaeru.domain.playback

import app.kaeru.domain.error.HttpError
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val WATCHED_THRESHOLD = 0.9f

class MarkEpisodeUnwatchedTest {
    private val now = Instant.parse("2026-09-16T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val library = FakeLibraryRepository()
    private val samples = FakePlaybackSampleRepository()
    private val unmark = MarkEpisodeUnwatched(library, samples, clock)

    private fun anime(id: Int = 100, episodes: Int = 12) = Anime(
        id = id, nameRu = "Имя", nameRomaji = "Name", posterUrl = null, screenshotUrls = emptyList(),
        status = AnimeStatus.RELEASED, episodes = episodes, episodesAired = episodes, nextEpisodeAt = null,
        score = null, year = null, studio = null, description = null,
    )

    private fun seed(episodes: Int = 7, anime: Anime = anime()) =
        library.put(LibraryEntry(anime, UserRate(1, anime.id, ListStatus.WATCHING, episodes, now), null))

    private fun stopped(episode: Int, positionMs: Long, durationMs: Long = 1_200_000) =
        EpisodeProgress(100, episode, positionMs, durationMs, Instant.EPOCH)

    private suspend fun progress(): List<EpisodeProgress> = samples.episodes.observe(100).first()

    private suspend fun watch(): WatchState? = samples.watchStates.observe(100).first()

    private class FakeLibraryRepository : LibraryRepository {
        private val entries = MutableStateFlow<Map<Int, LibraryEntry>>(emptyMap())
        val calls = mutableListOf<String>()
        var episodesResult: Result<Unit> = Result.success(Unit)

        fun put(entry: LibraryEntry) = entries.update { it + (entry.anime.id to entry) }

        fun entry(animeId: Int): LibraryEntry? = entries.value[animeId]

        override fun observeAnime(id: Int): Flow<LibraryEntry?> = entries.map { it[id] }

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
        override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> = throw NotImplementedError()
    }

    // --- the count on Shikimori ------------------------------------------------------------

    @Test
    fun `the count drops to the episode before the one unmarked`() = runTest {
        seed(episodes = 7)

        assertTrue(unmark(animeId = 100, episode = 5).isSuccess)

        assertEquals(listOf("episodes:100:4"), library.calls)
        assertEquals(4, library.entry(100)!!.rate.episodes)
    }

    @Test
    fun `unmarking the first episode leaves nothing counted`() = runTest {
        seed(episodes = 3)

        assertTrue(unmark(animeId = 100, episode = 1).isSuccess)

        assertEquals(listOf("episodes:100:0"), library.calls)
        assertEquals(0, library.entry(100)!!.rate.episodes)
    }

    @Test
    fun `an episode the count is already below is not sent again`() = runTest {
        seed(episodes = 3)

        assertTrue(unmark(animeId = 100, episode = 5).isSuccess)

        assertEquals(emptyList<String>(), library.calls)
        assertEquals(3, library.entry(100)!!.rate.episodes)
    }

    @Test
    fun `a title in no list has no count to lower`() = runTest {
        assertTrue(unmark(animeId = 100, episode = 5).isSuccess)

        assertEquals(emptyList<String>(), library.calls)
    }

    @Test
    fun `an episode below the first is a no-op`() = runTest {
        seed(episodes = 7)
        samples.episodes.seed(stopped(1, 600_000))

        assertTrue(unmark(animeId = 100, episode = 0).isSuccess)

        assertEquals(emptyList<String>(), library.calls)
        assertEquals(listOf(1), progress().map { it.episode })
    }

    @Test
    fun `a refused write is reported and nothing is forgotten`() = runTest {
        seed(episodes = 7)
        samples.episodes.seed(stopped(5, 1_180_000))
        library.episodesResult = Result.failure(HttpError(422))

        val error = unmark(animeId = 100, episode = 5).exceptionOrNull()

        assertTrue(error is HttpError)
        assertEquals(7, library.entry(100)!!.rate.episodes)
        assertEquals(listOf(5), progress().map { it.episode })
    }

    // --- what this device remembers ---------------------------------------------------------

    @Test
    fun `positions from the episode on are forgotten and earlier ones kept`() = runTest {
        seed(episodes = 7)
        listOf(stopped(3, 600_000), stopped(4, 1_180_000), stopped(5, 1_180_000), stopped(6, 300_000))
            .forEach { samples.episodes.seed(it) }

        assertTrue(unmark(animeId = 100, episode = 5).isSuccess)

        assertEquals(listOf(3, 4), progress().map { it.episode })
    }

    @Test
    fun `the pointer standing on the episode is rewound, keeping the track it plays in`() = runTest {
        seed(episodes = 7)
        samples.watchStates.seed(
            WatchState(100, episode = 5, positionMs = 1_180_000, durationMs = 1_200_000, 42, 1, Instant.EPOCH, "AniLibria"),
        )

        assertTrue(unmark(animeId = 100, episode = 5).isSuccess)

        val pointer = watch()!!
        assertEquals(0, pointer.positionMs)
        assertEquals(0, pointer.durationMs)
        assertEquals(42, pointer.translationId)
        assertEquals("AniLibria", pointer.translationTitle)
        assertEquals(now, pointer.updatedAt)
    }

    @Test
    fun `a pointer on an earlier episode is left where it is`() = runTest {
        seed(episodes = 7)
        samples.watchStates.seed(
            WatchState(100, episode = 3, positionMs = 600_000, durationMs = 1_200_000, null, null, Instant.EPOCH),
        )

        assertTrue(unmark(animeId = 100, episode = 5).isSuccess)

        assertEquals(600_000, watch()!!.positionMs)
    }

    /** The whole point of forgetting those rows: «продолжить» offers the episode again, from the top. */
    @Test
    fun `continue comes back to the episode that was unmarked`() = runTest {
        seed(episodes = 5)
        samples.episodes.seed(stopped(5, 1_180_000))
        samples.watchStates.seed(
            WatchState(100, episode = 5, positionMs = 1_180_000, durationMs = 1_200_000, null, null, Instant.EPOCH),
        )

        assertTrue(unmark(animeId = 100, episode = 5).isSuccess)

        val entry = LibraryEntry(anime(), library.entry(100)!!.rate, watch(), progress())
        assertEquals(ContinueTarget(5, 0), entry.continueTarget(WATCHED_THRESHOLD))
    }

    /** A logout mid-screen cannot undo the mark that has already reached Shikimori. */
    @Test
    fun `a local write that fails does not fail the unmark`() = runTest {
        seed(episodes = 7)
        samples.failForgetWith = IllegalStateException("account changed")

        assertTrue(unmark(animeId = 100, episode = 5).isSuccess)

        assertEquals(listOf("episodes:100:4"), library.calls)
        assertNull(watch())
    }
}
