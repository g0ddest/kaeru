package app.kaeru.domain.notify

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.repository.PairingAuthorization
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The check as the worker runs it: sign-in, refresh, compare, remember, publish — and what each
 * of those failing means for whether the work is worth trying again.
 */
class CheckNewEpisodesTest {
    private val now: Instant = Instant.parse("2026-09-18T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val auth = FakeAuth()
    private val library = FakeLibrary()
    private val remembered = FakeNotifiedEpisodes()
    private val notifier = RecordingNotifier()

    private val check = CheckNewEpisodes(auth, library, remembered, notifier, clock)

    private fun anime(id: Int, aired: Int) = Anime(
        id = id, nameRu = "Аниме $id", nameRomaji = "Anime $id", posterUrl = "poster-$id",
        screenshotUrls = emptyList(), status = AnimeStatus.ONGOING, episodes = 24, episodesAired = aired,
        nextEpisodeAt = null, score = null, year = 2026, studio = null, description = null,
    )

    private fun watching(id: Int, aired: Int, watched: Int) = LibraryEntry(
        anime(id, aired),
        UserRate(id.toLong(), id, ListStatus.WATCHING, watched, now),
        watch = null,
    )

    @Test
    fun `nobody signed in is no work, and nothing is asked of the network`() = runTest {
        auth.loggedIn.value = false

        assertEquals(NewEpisodeOutcome.NO_ACCOUNT, check.run())
        assertEquals(0, library.refreshes)
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `a refresh that could not reach Shikimori is worth trying again`() = runTest {
        library.refreshResult = Result.failure(IOException("no network"))

        assertEquals(NewEpisodeOutcome.UNREACHABLE, check.run())
        assertTrue(notifier.posted.isEmpty())
        assertTrue(remembered.rows.isEmpty())
    }

    @Test
    fun `the check reads the list Shikimori has just been asked for`() = runTest {
        library.entries.value = listOf(watching(1, aired = 8, watched = 7))
        remembered.rows += NotifiedEpisode(1, 7)

        assertEquals(NewEpisodeOutcome.CHECKED, check.run())

        assertEquals(1, library.refreshes)
        assertEquals(listOf(listOf(NewEpisode(1, "Аниме 1", "poster-1", 8))), notifier.posted)
    }

    @Test
    fun `what was said is written down before it is said`() = runTest {
        library.entries.value = listOf(watching(1, aired = 8, watched = 7))
        remembered.rows += NotifiedEpisode(1, 7)
        notifier.recordedWhenPosting = { remembered.rows.toList() }

        check.run()

        assertTrue(NotifiedEpisode(1, 8) in notifier.rowsAtPost)
        assertEquals(now, remembered.writtenAt)
    }

    @Test
    fun `a check with nothing to say publishes nothing at all`() = runTest {
        library.entries.value = listOf(watching(1, aired = 7, watched = 7))
        remembered.rows += NotifiedEpisode(1, 7)

        assertEquals(NewEpisodeOutcome.CHECKED, check.run())

        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `the first check fills the table and says nothing`() = runTest {
        library.entries.value = listOf(watching(1, aired = 8, watched = 3))

        assertEquals(NewEpisodeOutcome.CHECKED, check.run())

        assertTrue(notifier.posted.isEmpty())
        assertEquals(listOf(NotifiedEpisode(1, 8)), remembered.rows)
    }

    private class FakeAuth : AuthRepository {
        val loggedIn = MutableStateFlow(true)
        override val isLoggedIn: Flow<Boolean> = loggedIn
        override fun authorizeUrl(redirectUri: String) = "https://auth.test/"
        override fun pairingAuthorization() = PairingAuthorization("https://auth.test/", "state")
        override suspend fun exchangeRedirectCode(code: String, state: String?) = Result.success(Unit)
        override suspend fun exchangeTypedCode(code: String) = Result.success(Unit)
        override suspend fun exchangePairedCode(code: String, redirectUri: String) = Result.success(Unit)
        override suspend fun logout() = Unit
    }

    private class FakeLibrary : LibraryRepository {
        val entries = MutableStateFlow<List<LibraryEntry>>(emptyList())
        var refreshResult: Result<Unit> = Result.success(Unit)
        var refreshes = 0

        override fun observeLibrary(): Flow<List<LibraryEntry>> = entries
        override fun observeAnime(id: Int): Flow<LibraryEntry?> =
            entries.map { list -> list.firstOrNull { it.anime.id == id } }

        override fun observeAnimeDetails(id: Int): Flow<Anime?> =
            entries.map { list -> list.firstOrNull { it.anime.id == id }?.anime }

        override suspend fun refresh(): Result<Unit> {
            refreshes++
            return refreshResult
        }

        override suspend fun refreshAnime(id: Int): Result<Unit> = Result.success(Unit)
        override suspend fun search(query: String): Result<List<Anime>> = Result.success(emptyList())
        override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> = Result.success(Unit)
        override suspend fun setEpisodes(animeId: Int, episodes: Int): Result<Unit> = Result.success(Unit)
    }

    private class FakeNotifiedEpisodes : NotifiedEpisodes {
        val rows = mutableListOf<NotifiedEpisode>()
        var writtenAt: Instant? = null

        override suspend fun all(): List<NotifiedEpisode> = rows.toList()

        override suspend fun record(episodes: List<NotifiedEpisode>, at: Instant) {
            writtenAt = at
            episodes.forEach { if (it !in rows) rows += it }
        }
    }

    private class RecordingNotifier : NewEpisodeNotifier {
        val posted = mutableListOf<List<NewEpisode>>()
        var recordedWhenPosting: (() -> List<NotifiedEpisode>)? = null
        var rowsAtPost: List<NotifiedEpisode> = emptyList()

        override suspend fun post(news: List<NewEpisode>) {
            rowsAtPost = recordedWhenPosting?.invoke().orEmpty()
            posted += news
        }
    }
}
