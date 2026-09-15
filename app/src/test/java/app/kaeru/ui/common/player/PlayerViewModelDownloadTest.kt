package app.kaeru.ui.common.player

import app.kaeru.domain.connectivity.FakeConnectivity
import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.download.FakeDownloadRepository
import app.kaeru.domain.error.DownloadLimitReached
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.playback.FakeEpisodeProgressRepository
import app.kaeru.domain.playback.FakePlaybackPreferences
import app.kaeru.domain.playback.FakeWatchStateRepository
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.StreamPrefetchCache
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.source.EpisodeSourceProvider
import app.kaeru.player.CastSessionBridge
import app.kaeru.player.FakeCastFramework
import app.kaeru.player.FakePlaybackEngine
import app.kaeru.player.PlaybackState
import app.kaeru.test.MainDispatcherRule
import app.kaeru.test.MutableClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * What the player screen knows about this episode being on the device, and the two things it
 * can do about it. The screen itself is Task 4's; this is the state and the actions behind it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelDownloadTest {
    @get:Rule val main = MainDispatcherRule()

    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val clock = MutableClock(now)
    private val anilibria = Translation(11, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12)

    private val controller = FakePlaybackController()
    private val watchStates = FakeWatchStateRepository()
    private val episodes = FakeEpisodeProgressRepository()
    private val library = FakeLibrary()
    private val source = FakeSource()
    private val prefs = FakePlaybackPreferences()
    private val downloads = FakeDownloadRepository()
    private val connectivity = FakeConnectivity()
    private lateinit var viewModel: PlayerViewModel

    private val anime = Anime(
        100, "Фрирен", "Frieren", "https://poster", emptyList(), AnimeStatus.RELEASED,
        episodes = 12, episodesAired = 12, nextEpisodeAt = null,
        score = 9.1, year = 2023, studio = "Madhouse", description = null,
    )

    private val cast = CastSessionBridge(
        framework = FakeCastFramework(),
        controller = controller,
        local = FakePlaybackEngine(),
        scope = CoroutineScope(main.dispatcher + SupervisorJob()),
    )

    @Before
    fun setUp() {
        library.put(LibraryEntry(anime, UserRate(1, 100, ListStatus.WATCHING, 3, now), null))
        viewModel = PlayerViewModel(
            controller = controller,
            cast = cast,
            resolve = ResolveEpisodeStream(source, watchStates, prefs, clock, StreamPrefetchCache(clock)),
            library = library,
            watchStates = watchStates,
            episodeProgress = episodes,
            prefs = prefs,
            downloads = downloads,
            connectivity = connectivity,
            io = main.dispatcher,
        )
    }

    private fun row(episode: Int, state: DownloadState, progress: Float = 0f) = EpisodeDownload(
        key = DownloadKey(100, episode, anilibria.id, Quality.P720),
        state = state,
        bytes = 320L * 1024 * 1024,
        progress = progress,
        failure = null,
        updatedAt = now,
    )

    @Test
    fun `the screen is told about the download of the episode that is playing`() = runTest(main.dispatcher) {
        downloads.put(row(episode = 4, state = DownloadState.COMPLETED, progress = 1f))
        downloads.put(row(episode = 9, state = DownloadState.DOWNLOADING, progress = 0.3f))

        viewModel.start(animeId = 100, episode = 4)
        advanceUntilIdle()

        assertEquals(DownloadState.COMPLETED, viewModel.uiState.value.download?.state)
        assertEquals(4, viewModel.uiState.value.download?.episode)
    }

    @Test
    fun `an episode with nothing on the device has no download to show`() = runTest(main.dispatcher) {
        downloads.put(row(episode = 9, state = DownloadState.COMPLETED, progress = 1f))

        viewModel.start(animeId = 100, episode = 4)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.download)
    }

    @Test
    fun `a download that finishes while the episode plays reaches the screen`() = runTest(main.dispatcher) {
        viewModel.start(animeId = 100, episode = 4)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.download)

        downloads.put(row(episode = 4, state = DownloadState.DOWNLOADING, progress = 0.5f))
        advanceUntilIdle()

        assertEquals(DownloadState.DOWNLOADING, viewModel.uiState.value.download?.state)
        assertEquals(0.5f, viewModel.uiState.value.download?.progress)
    }

    @Test
    fun `downloading takes the episode that is playing at the height the settings ask for`() =
        runTest(main.dispatcher) {
            viewModel.start(animeId = 100, episode = 4)
            advanceUntilIdle()

            viewModel.download()
            advanceUntilIdle()

            // Null, not the rung on screen: the download settings own the height, so a viewer who
            // asked for 480p downloads never gets 1080p for having watched one episode at it.
            assertEquals(Triple(100, 4, null), downloads.enqueued.single())
        }

    @Test
    fun `a download that will not fit says so and nothing else happens`() = runTest(main.dispatcher) {
        downloads.enqueueFailure = DownloadLimitReached(limitBytes = 5L * 1024 * 1024 * 1024, usedBytes = 5L)
        viewModel.start(animeId = 100, episode = 4)
        advanceUntilIdle()

        viewModel.download()
        advanceUntilIdle()

        assertEquals(
            "Лимит места исчерпан. Удалите загрузки или увеличьте лимит в настройках",
            viewModel.uiState.value.toast,
        )
    }

    @Test
    fun `removing takes away the download of the episode that is playing`() = runTest(main.dispatcher) {
        downloads.put(row(episode = 4, state = DownloadState.COMPLETED, progress = 1f))
        viewModel.start(animeId = 100, episode = 4)
        advanceUntilIdle()

        viewModel.removeDownload()
        advanceUntilIdle()

        assertEquals(100 to 4, downloads.removed.single())
        assertNull(viewModel.uiState.value.download)
    }

    @Test
    fun `nothing is downloaded while there is no episode to download`() = runTest(main.dispatcher) {
        viewModel.download()
        viewModel.removeDownload()
        viewModel.removeDownloadAndRetry()
        advanceUntilIdle()

        assertTrue(downloads.enqueued.isEmpty())
        assertTrue(downloads.removed.isEmpty())
        assertEquals(0, controller.retries)
    }

    /**
     * «Удалить загрузку» on a failed episode: the copy goes, and the episode starts again from the
     * source. Both, in that order and in one coroutine — opening an episode prefers a finished
     * download, so a retry that ran first would pick the same unplayable file up again.
     */
    @Test
    fun `removing a broken download starts the episode again from the source`() = runTest(main.dispatcher) {
        downloads.put(row(episode = 4, state = DownloadState.COMPLETED, progress = 1f))
        viewModel.start(animeId = 100, episode = 4)
        advanceUntilIdle()

        viewModel.removeDownloadAndRetry()
        advanceUntilIdle()

        assertEquals(100 to 4, downloads.removed.single())
        assertNull(downloads.completed(100, 4))
        assertNull(viewModel.uiState.value.download)
        assertEquals(1, controller.retries)
    }

    /**
     * Removing only *sends* the request; the engine's index is what opening reads. So the retry
     * waits for the row to actually be gone — otherwise it re-opens the very file the viewer asked
     * to be rid of and fails in the same way.
     */
    @Test
    fun `the retry waits for the engine to let go of the file`() = runTest(main.dispatcher) {
        downloads.put(row(episode = 4, state = DownloadState.COMPLETED, progress = 1f))
        downloads.holdRemovals = true
        viewModel.start(animeId = 100, episode = 4)
        advanceUntilIdle()

        viewModel.removeDownloadAndRetry()
        // `runCurrent`, not `advanceUntilIdle`: the latter runs the virtual clock past the timeout
        // below, which is exactly the thing this test is trying not to reach.
        runCurrent()

        assertEquals(100 to 4, downloads.removed.single())
        assertEquals(0, controller.retries)

        downloads.releaseRemovals()
        runCurrent()

        assertEquals(1, controller.retries)
    }

    @Test
    fun `and gives up waiting rather than never retrying at all`() = runTest(main.dispatcher) {
        downloads.put(row(episode = 4, state = DownloadState.COMPLETED, progress = 1f))
        downloads.holdRemovals = true
        viewModel.start(animeId = 100, episode = 4)
        advanceUntilIdle()

        viewModel.removeDownloadAndRetry()
        runCurrent()
        assertEquals(0, controller.retries)

        advanceTimeBy(6_000)
        runCurrent()

        assertEquals(1, controller.retries)
    }

    @Test
    fun `nothing is downloaded for a title the controller has not reached yet`() = runTest(main.dispatcher) {
        // The controller serves the whole process. Between this screen naming its title and
        // playback reaching it, what the controller still holds is the title before it — and
        // pairing the two would download episode seven of a show nobody opened.
        controller.playback.value = PlaybackState(target = PlaybackTarget(100, 7, 0, null))

        viewModel.start(animeId = 200, episode = 1)
        viewModel.download()
        viewModel.removeDownload()
        advanceUntilIdle()

        assertTrue(downloads.enqueued.isEmpty())
        assertTrue(downloads.removed.isEmpty())

        // Once playback lands on this title it is the ordinary case again.
        viewModel.download()
        advanceUntilIdle()

        assertEquals(Triple(200, 1, null), downloads.enqueued.single())
    }

    @Test
    fun `no download row is shown for an episode of another title`() = runTest(main.dispatcher) {
        downloads.put(row(episode = 4, state = DownloadState.COMPLETED, progress = 1f))
        viewModel.start(animeId = 100, episode = 4)
        advanceUntilIdle()
        assertEquals(4, viewModel.uiState.value.download?.episode)

        // Playback moved to another title while this screen is still showing anime 100.
        controller.playback.value = PlaybackState(target = PlaybackTarget(999, 4, 0, null))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.download)
    }

    @Test
    fun `the screen knows there is no network`() = runTest(main.dispatcher) {
        viewModel.start(animeId = 100, episode = 4)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.offline)

        connectivity.goOffline()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.offline)
    }

    private inner class FakeSource : EpisodeSourceProvider {
        override suspend fun translations(shikimoriId: Int) = Result.success(listOf(anilibria))

        override suspend fun resolve(shikimoriId: Int, episode: Int, translation: Translation?) =
            Result.success(
                EpisodeStream(
                    animeId = shikimoriId,
                    episode = episode,
                    translation = translation ?: anilibria,
                    urls = mapOf(Quality.P720 to "https://cdn/720"),
                    resolvedAt = now,
                ),
            )
    }

    private class FakeLibrary : LibraryRepository {
        private val entries = MutableStateFlow<Map<Int, LibraryEntry>>(emptyMap())

        fun put(entry: LibraryEntry) = entries.update { it + (entry.anime.id to entry) }

        override fun observeLibrary(): Flow<List<LibraryEntry>> = entries.map { it.values.toList() }
        override fun observeAnime(id: Int): Flow<LibraryEntry?> = entries.map { it[id] }
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = entries.map { it[id]?.anime }
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun refreshAnime(id: Int) = Result.success(Unit)
        override suspend fun search(query: String) = Result.success(emptyList<Anime>())
        override suspend fun setEpisodes(animeId: Int, episodes: Int) = Result.success(Unit)
        override suspend fun setStatus(animeId: Int, status: ListStatus) = Result.success(Unit)
    }
}
