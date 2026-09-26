package app.kaeru.player

import app.kaeru.domain.connectivity.FakeConnectivity
import app.kaeru.domain.download.DeferredDownloadRemoval
import app.kaeru.domain.download.FakeDeferredRemovals
import app.kaeru.domain.download.FakeDownloadRepository
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.playback.AddStartedTitleToList
import app.kaeru.domain.playback.FakePlaybackPreferences
import app.kaeru.domain.playback.FakePlaybackSampleRepository
import app.kaeru.domain.playback.FakeSkipMarks
import app.kaeru.domain.playback.FakeWatchStateRepository
import app.kaeru.domain.playback.MarkEpisodeWatched
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.StreamPrefetchCache
import app.kaeru.domain.playback.SuppressedMarks
import app.kaeru.domain.playback.WatchProgress
import app.kaeru.domain.settings.FakeSettingsStore
import app.kaeru.domain.viewsync.SyncReason
import app.kaeru.domain.viewsync.ViewingSyncEvents
import app.kaeru.test.MutableClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * When the player asks viewing sync to send now rather than within the minute: a pause the viewer
 * made, another episode, the player going away — and never for a stall.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerSyncTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val clock = MutableClock(now)
    private val engine = FakePlaybackEngine()
    private val watchStates = FakeWatchStateRepository()
    private val library = FakeLibraryRepository()
    private val prefs = FakePlaybackPreferences()
    private val downloads = FakeDownloadRepository()
    private val events = ViewingSyncEvents()
    private val pushes = mutableListOf<SyncReason>()
    private lateinit var controller: DefaultPlaybackController

    @Before
    fun setUp() {
        library.put(
            LibraryEntry(
                Anime(
                    100, "Фрирен", "Frieren", null, emptyList(), AnimeStatus.RELEASED,
                    episodes = 12, episodesAired = 12, nextEpisodeAt = null,
                    score = null, year = null, studio = null, description = null,
                ),
                UserRate(1, 100, ListStatus.WATCHING, episodes = 3, updatedAt = now),
                null,
            ),
        )
        val deleteWatched = DeferredDownloadRemoval(downloads, FakeSettingsStore(), FakeDeferredRemovals())
        controller = DefaultPlaybackController(
            localEngine = engine,
            resolve = ResolveEpisodeStream(FakeEpisodeSource(), watchStates, prefs, clock, StreamPrefetchCache(clock)),
            progress = WatchProgress(watchStates, FakePlaybackSampleRepository(watchStates), clock),
            markWatched = MarkEpisodeWatched(library, watchStates, clock, deleteWatched),
            addToList = AddStartedTitleToList(library),
            suppressedMarks = SuppressedMarks(),
            deleteWatchedDownloads = deleteWatched,
            library = library,
            prefs = prefs,
            headers = StreamHeaders("Chrome/128.0", "https://kodikplayer.com/"),
            downloads = downloads,
            connectivity = FakeConnectivity(),
            skipMarks = FakeSkipMarks(),
            scope = scope,
            io = dispatcher,
            sync = events,
        )
        scope.launch {
            events.events.collect { event -> if (event is ViewingSyncEvents.Event.Push) pushes += event.reason }
        }
    }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun start(episode: Int = 4) {
        controller.play(PlaybackTarget(animeId = 100, episode = episode, startPositionMs = 0, translation = null))
        engine.ready(1_440_000)
    }

    @Test
    fun `the first episode of a session asks for nothing`() = runTest(dispatcher) {
        start()
        engine.moveTo(30_000)
        advanceUntilIdle()

        assertEquals(emptyList<SyncReason>(), pushes)
    }

    @Test
    fun `a pause asks for the batch now`() = runTest(dispatcher) {
        start()
        engine.moveTo(30_000)
        advanceUntilIdle()

        controller.setPlaying(false)
        advanceUntilIdle()

        assertEquals(listOf(SyncReason.PAUSE), pushes)
    }

    @Test
    fun `a stall is not a pause`() = runTest(dispatcher) {
        start()
        engine.moveTo(30_000)
        advanceUntilIdle()

        engine.stall()
        advanceUntilIdle()

        assertEquals(emptyList<SyncReason>(), pushes)
    }

    @Test
    fun `another episode asks for the batch now, by choice or by moving on`() = runTest(dispatcher) {
        start(episode = 4)
        engine.moveTo(30_000)
        advanceUntilIdle()

        start(episode = 6)
        advanceUntilIdle()
        controller.playNext()
        advanceUntilIdle()

        assertEquals(listOf(SyncReason.EPISODE_CHANGE, SyncReason.EPISODE_CHANGE), pushes)
    }

    @Test
    fun `leaving the player asks for the batch now`() = runTest(dispatcher) {
        start()
        engine.moveTo(30_000)
        advanceUntilIdle()

        controller.reportProgress()
        advanceUntilIdle()
        controller.release()
        advanceUntilIdle()

        assertEquals(listOf(SyncReason.LEAVING, SyncReason.LEAVING), pushes)
    }
}
