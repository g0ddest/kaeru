package app.kaeru.domain.playback

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.repository.LibraryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** What starting an episode does to a title that is in no list at all. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddStartedTitleToListTest {
    private val now: Instant = Instant.parse("2026-09-16T09:00:00Z")
    private val library = FakeLibrary()

    private val add = AddStartedTitleToList(library)

    private fun anime(id: Int) = Anime(
        id = id, nameRu = "Имя $id", nameRomaji = "Name $id", posterUrl = null, screenshotUrls = emptyList(),
        status = AnimeStatus.RELEASED, episodes = 12, episodesAired = 12, nextEpisodeAt = null,
        score = null, year = null, studio = null, description = null,
    )

    private fun seed(id: Int, status: ListStatus, episodes: Int = 0) = library.put(
        LibraryEntry(anime(id), UserRate(id.toLong(), id, status, episodes, now), watch = null),
    )

    @Test
    fun `a title in no list is added as watching`() = runTest {
        add(animeId = 7)

        assertEquals(listOf(7 to ListStatus.WATCHING), library.statusWrites)
        assertEquals(ListStatus.WATCHING, library.entry(7)?.rate?.status)
        assertEquals(0, library.entry(7)?.rate?.episodes)
    }

    @Test
    fun `a title already in the list is left exactly where the viewer put it`() = runTest {
        seed(7, ListStatus.PLANNED)

        add(animeId = 7)

        assertTrue(library.statusWrites.isEmpty())
        assertEquals(ListStatus.PLANNED, library.entry(7)?.rate?.status)
    }

    /** «Пересматриваю», «Отложено», «Брошено» — a status the viewer chose is never overwritten. */
    @Test
    fun `no status the viewer chose is overwritten by starting an episode`() = runTest {
        listOf(ListStatus.REWATCHING, ListStatus.ON_HOLD, ListStatus.DROPPED, ListStatus.COMPLETED)
            .forEachIndexed { index, status ->
                val id = 10 + index
                seed(id, status)
                add(animeId = id)
                assertEquals(status.name, status, library.entry(id)?.rate?.status)
            }

        assertTrue(library.statusWrites.isEmpty())
    }

    @Test
    fun `starting the same title twice creates one rate`() = runTest {
        add(animeId = 7)
        add(animeId = 7)

        assertEquals(listOf(7 to ListStatus.WATCHING), library.statusWrites)
    }

    /**
     * Two starts inside the seconds a create takes.
     *
     * A track change or a retry re-opens the episode while the first create is still on the
     * network, and a second create there is a second rate Shikimori has to be told to forget.
     */
    @Test
    fun `two starts racing each other still create one rate`() = runTest {
        library.holdStatusWrite()

        val first = launch { add(animeId = 7) }
        advanceUntilIdle()
        val second = launch { add(animeId = 7) }
        advanceUntilIdle()

        library.releaseStatusWrite()
        first.join()
        second.join()

        assertEquals(listOf(7 to ListStatus.WATCHING), library.statusWrites)
    }

    /** No network, no account, no card: playback carries on and the next start tries again. */
    @Test
    fun `a write that fails is not allowed to break playback`() = runTest {
        library.statusResult = Result.failure(IllegalStateException("no account"))

        add(animeId = 7)

        assertEquals(listOf(7 to ListStatus.WATCHING), library.statusWrites)
    }

    private class FakeLibrary : LibraryRepository {
        private val entries = MutableStateFlow<Map<Int, LibraryEntry>>(emptyMap())
        val statusWrites = mutableListOf<Pair<Int, ListStatus>>()
        var statusResult: Result<Unit> = Result.success(Unit)
        private var gate: CompletableDeferred<Unit>? = null

        fun put(entry: LibraryEntry) = entries.update { it + (entry.anime.id to entry) }

        fun entry(animeId: Int): LibraryEntry? = entries.value[animeId]

        fun holdStatusWrite() {
            gate = CompletableDeferred()
        }

        fun releaseStatusWrite() {
            gate?.complete(Unit)
        }

        override fun observeAnime(id: Int): Flow<LibraryEntry?> = entries.map { it[id] }

        override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> {
            statusWrites += animeId to status
            gate?.await()
            if (statusResult.isFailure) return statusResult
            entries.update { rows ->
                val existing = rows[animeId]
                val anime = existing?.anime ?: Anime(
                    id = animeId, nameRu = "Имя $animeId", nameRomaji = "Name $animeId", posterUrl = null,
                    screenshotUrls = emptyList(), status = AnimeStatus.RELEASED, episodes = 12,
                    episodesAired = 12, nextEpisodeAt = null, score = null, year = null,
                    studio = null, description = null,
                )
                val rate = existing?.rate?.copy(status = status)
                    ?: UserRate(animeId.toLong(), animeId, status, 0, Instant.EPOCH)
                rows + (animeId to LibraryEntry(anime, rate, existing?.watch))
            }
            return statusResult
        }

        override fun observeLibrary(): Flow<List<LibraryEntry>> = entries.map { it.values.toList() }
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = entries.map { it[id]?.anime }
        override suspend fun refresh(): Result<Unit> = throw NotImplementedError()
        override suspend fun refreshAnime(id: Int): Result<Unit> = throw NotImplementedError()
        override suspend fun search(query: String): Result<List<Anime>> = throw NotImplementedError()
        override suspend fun setEpisodes(animeId: Int, episodes: Int): Result<Unit> = throw NotImplementedError()
    }
}
