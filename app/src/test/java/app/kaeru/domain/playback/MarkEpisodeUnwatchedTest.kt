package app.kaeru.domain.playback

import app.kaeru.domain.download.DeferredDownloadRemoval
import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.download.DownloadedEpisode
import app.kaeru.domain.download.FakeDeferredRemovals
import app.kaeru.domain.download.FakeDownloadRepository
import app.kaeru.domain.error.HttpError
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.settings.FakeSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val suppressed = SuppressedMarks()
    private val promises = FakeDeferredRemovals()
    private val unmark = MarkEpisodeUnwatched(library, samples.episodes, samples, suppressed, clock, promises)

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

    /**
     * Nothing about the episode's watched state changed, so nothing about where the viewer got to
     * in it should either: this branch is «Shikimori already says what you are asking me to say».
     */
    @Test
    fun `an episode the count is already below keeps its position`() = runTest {
        seed(episodes = 3)
        samples.episodes.seed(stopped(5, 600_000))

        assertTrue(unmark(animeId = 100, episode = 5).isSuccess)

        assertEquals(listOf(5), progress().map { it.episode })
        assertTrue(samples.forgotten.isEmpty())
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

    // --- what an undo needs to know ----------------------------------------------------------

    @Test
    fun `the outcome carries the count that stood before, and every position it took`() = runTest {
        seed(episodes = 7)
        listOf(stopped(4, 600_000), stopped(5, 1_180_000), stopped(6, 300_000))
            .forEach { samples.episodes.seed(it) }

        val outcome = unmark(animeId = 100, episode = 5).getOrThrow()

        assertEquals(5, outcome.episode)
        assertEquals(7, outcome.previousCount)
        assertEquals(listOf(5, 6), outcome.forgotten.map { it.episode })
    }

    /**
     * The whole point of carrying the count. Un-marking the fifth of seven watched episodes takes
     * the sixth and seventh with it — that is what a counter means — and an undo that re-marked the
     * episode the viewer tapped would hand back five, quietly abandoning the other two.
     */
    @Test
    fun `undo restores the count that stood before, not the episode that was tapped`() = runTest {
        seed(episodes = 7)

        val outcome = unmark(animeId = 100, episode = 5).getOrThrow()
        assertEquals(4, library.entry(100)!!.rate.episodes)

        assertTrue(unmark.restore(animeId = 100, outcome = outcome).isSuccess)

        assertEquals(listOf("episodes:100:4", "episodes:100:7"), library.calls)
        assertEquals(7, library.entry(100)!!.rate.episodes)
    }

    @Test
    fun `undo puts the forgotten positions back as they were`() = runTest {
        seed(episodes = 7)
        val row = stopped(5, 1_180_000)
        samples.episodes.seed(row)
        samples.episodes.seed(stopped(6, 300_000))

        val outcome = unmark(animeId = 100, episode = 5).getOrThrow()
        assertTrue(progress().isEmpty())

        assertTrue(unmark.restore(animeId = 100, outcome = outcome).isSuccess)

        assertEquals(listOf(5, 6), progress().map { it.episode })
        assertEquals(row, progress().first())
    }

    @Test
    fun `an undo Shikimori refuses is reported and nothing is put back`() = runTest {
        seed(episodes = 7)
        samples.episodes.seed(stopped(5, 1_180_000))
        val outcome = unmark(animeId = 100, episode = 5).getOrThrow()
        library.episodesResult = Result.failure(HttpError(500))

        assertTrue(unmark.restore(animeId = 100, outcome = outcome).isFailure)

        assertTrue(progress().isEmpty())
    }

    @Test
    fun `an undo that has already happened writes nothing again`() = runTest {
        seed(episodes = 7)
        val outcome = unmark(animeId = 100, episode = 5).getOrThrow()
        assertTrue(unmark.restore(animeId = 100, outcome = outcome).isSuccess)
        library.calls.clear()

        assertTrue(unmark.restore(animeId = 100, outcome = outcome).isSuccess)

        assertEquals(emptyList<String>(), library.calls)
    }

    // --- and what the player must not do about it ------------------------------------------------

    /**
     * A cast session and picture-in-picture both outlive the player screen, so the episode can
     * still be playing behind the title screen this was pressed on.
     */
    @Test
    fun `an un-marked episode is taken off the automatic mark's list`() = runTest {
        seed(episodes = 7)

        val outcome = unmark(animeId = 100, episode = 5).getOrThrow()

        assertTrue(suppressed.isSuppressed(100, 5))

        assertTrue(unmark.restore(animeId = 100, outcome = outcome).isSuccess)

        assertFalse(suppressed.isSuppressed(100, 5))
    }

    @Test
    fun `an episode nothing was written about is not suppressed either`() = runTest {
        seed(episodes = 3)

        assertTrue(unmark(animeId = 100, episode = 5).isSuccess)

        assertFalse(suppressed.isSuppressed(100, 5))
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

    // --- what «Удалять просмотренные» may have promised -------------------------------------

    /**
     * The regression this guards against: episode 5 is downloaded and playing; at the threshold
     * the mark records a deletion for it and [DeferredDownloadRemoval] defers it because the
     * episode is being read; the viewer un-marks episode 5 from the title screen. Without this,
     * playback moving on afterwards deletes a download the viewer just said they have not
     * watched.
     */
    @Test
    fun `un-marking an episode revokes a deletion promised for it`() = runTest {
        seed(episodes = 7)
        promises.seed(DownloadedEpisode(100, 5))

        assertTrue(unmark(animeId = 100, episode = 5).isSuccess)

        assertTrue(promises.pending().isEmpty())
    }

    @Test
    fun `a promise for an earlier episode is left alone`() = runTest {
        seed(episodes = 7)
        promises.seed(DownloadedEpisode(100, 4))

        assertTrue(unmark(animeId = 100, episode = 5).isSuccess)

        assertEquals(setOf(DownloadedEpisode(100, 4)), promises.pending())
    }

    /**
     * M-1: Shikimori holds a count, so un-marking episode 5 un-watches 5, 6 and 7 together — the
     * same reading [forgetPositions] already gives the positions this device remembers. A standing
     * promise for any of those episodes assumed the opposite of what the un-mark now says, so it
     * goes too; a promise for an episode still genuinely watched (below the one tapped) does not.
     */
    @Test
    fun `un-marking an episode revokes every promise the count also un-watches`() = runTest {
        seed(episodes = 7)
        promises.seed(DownloadedEpisode(100, 4), DownloadedEpisode(100, 5), DownloadedEpisode(100, 6), DownloadedEpisode(100, 7))

        assertTrue(unmark(animeId = 100, episode = 5).isSuccess)

        assertEquals(setOf(DownloadedEpisode(100, 4)), promises.pending())
    }

    /** The no-op branch — a count already below the tapped episode — is covered too. */
    @Test
    fun `an already-below un-mark still revokes the standing promises at or above it`() = runTest {
        seed(episodes = 3)
        promises.seed(DownloadedEpisode(100, 4), DownloadedEpisode(100, 5), DownloadedEpisode(100, 6))

        assertTrue(unmark(animeId = 100, episode = 5).isSuccess)

        assertEquals(setOf(DownloadedEpisode(100, 4)), promises.pending())
    }

    /** The end-to-end shape of the regression: the revoked promise never reaches a deletion. */
    @Test
    fun `revoking the promise means playback moving off the episode deletes nothing`() = runTest {
        seed(episodes = 7)
        val downloads = FakeDownloadRepository()
        downloads.downloaded(100, 5, Translation(3, "AniLibria", TranslationKind.VOICE, 12), "file:///5.m3u8")
        val settings = FakeSettingsStore()
        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(deleteWatched = true)
        val deferred = DeferredDownloadRemoval(downloads, settings, promises)
        // The mark at the watched threshold, while episode 5 is still the one playing: the
        // deletion is promised and deferred rather than acted on at once.
        deferred.nowPlaying(100, 5)
        deferred.onWatched(100, 5)

        assertTrue(unmark(animeId = 100, episode = 5).isSuccess)
        deferred.nowPlaying(100, 6)

        assertTrue(downloads.removed.isEmpty())
    }
}
