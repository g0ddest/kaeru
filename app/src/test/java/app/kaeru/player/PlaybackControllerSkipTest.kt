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
import app.kaeru.domain.playback.SkipInterval
import app.kaeru.domain.playback.SkipKind
import app.kaeru.domain.playback.SkipMarks
import app.kaeru.domain.playback.StreamPrefetchCache
import app.kaeru.domain.playback.SuppressedMarks
import app.kaeru.domain.playback.WatchProgress
import app.kaeru.domain.settings.FakeSettingsStore
import app.kaeru.domain.together.LocalAction
import app.kaeru.test.MutableClock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The opening and the ending as the controller sees them: one question per episode, and an offer
 * that lives on played seconds rather than on a timer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerSkipTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val now = Instant.parse("2026-09-19T10:00:00Z")
    private val clock = MutableClock(now)

    private val engine = FakePlaybackEngine()
    private val watchStates = FakeWatchStateRepository()
    private val source = FakeEpisodeSource()
    private val library = FakeLibraryRepository()
    private val prefs = FakePlaybackPreferences()
    private val downloads = FakeDownloadRepository()
    private val settings = FakeSettingsStore()
    private val skipMarks = FakeSkipMarks()
    private lateinit var controller: DefaultPlaybackController

    /** Frieren episode 1 as AniSkip has it: a 1560-second file, opening 3–93, ending 1460–1560. */
    private val episodeMs = 1_560_000L
    private val opening = SkipInterval(3_000, 93_000)
    private val ending = SkipInterval(1_460_000, 1_560_000)

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
        val deleteWatched = DeferredDownloadRemoval(downloads, settings, FakeDeferredRemovals())
        controller = DefaultPlaybackController(
            localEngine = engine,
            resolve = ResolveEpisodeStream(source, watchStates, prefs, clock, StreamPrefetchCache(clock)),
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
            skipMarks = skipMarks,
            scope = scope,
            io = dispatcher,
        )
    }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun start(episode: Int = 4, durationMs: Long = episodeMs) {
        controller.play(PlaybackTarget(100, episode, 0, translation = null))
        engine.ready(durationMs)
    }

    @Test
    fun `the marks are asked for once the engine knows how long the episode is`() = runTest(dispatcher) {
        skipMarks.answer = SkipMarks(opening, ending)

        start()
        advanceUntilIdle()

        assertEquals(listOf("100/4@1560000"), skipMarks.asked)
    }

    @Test
    fun `an episode asks once, however many positions it reports`() = runTest(dispatcher) {
        start()
        advanceUntilIdle()
        repeat(5) { engine.moveTo(10_000L * it) }
        advanceUntilIdle()

        assertEquals(1, skipMarks.asked.size)
    }

    @Test
    fun `the next episode is a question of its own`() = runTest(dispatcher) {
        start(episode = 4)
        advanceUntilIdle()

        controller.playNext()
        engine.ready(1_470_000)
        advanceUntilIdle()

        assertEquals(listOf("100/4@1560000", "100/5@1470000"), skipMarks.asked)
    }

    @Test
    fun `nothing is asked while the length is unknown`() = runTest(dispatcher) {
        controller.play(PlaybackTarget(100, 4, 0, translation = null))
        advanceUntilIdle()

        assertTrue(skipMarks.asked.isEmpty())
    }

    // --- the offer ------------------------------------------------------------------------------

    @Test
    fun `walking into the opening puts the offer on the state for ten seconds`() = runTest(dispatcher) {
        skipMarks.answer = SkipMarks(opening, ending)
        start()
        advanceUntilIdle()

        engine.moveTo(2_000)
        advanceUntilIdle()
        assertNull(controller.state.value.skip)

        engine.moveTo(4_000)
        advanceUntilIdle()
        assertEquals(SkipKind.OPENING, controller.state.value.skip?.kind)

        engine.moveTo(14_000)
        advanceUntilIdle()
        assertNull(controller.state.value.skip)
    }

    @Test
    fun `walking into the ending offers the ending instead`() = runTest(dispatcher) {
        skipMarks.answer = SkipMarks(opening, ending)
        start()
        advanceUntilIdle()

        engine.moveTo(1_462_000)
        advanceUntilIdle()

        assertEquals(SkipKind.ENDING, controller.state.value.skip?.kind)
        assertEquals(ending, controller.state.value.skip?.interval)
    }

    @Test
    fun `an ending marked in the first minutes of the episode is never offered`() = runTest(dispatcher) {
        skipMarks.answer = SkipMarks(ending = SkipInterval(5_000, 95_000))
        start()
        advanceUntilIdle()

        engine.moveTo(6_000)
        advanceUntilIdle()

        assertNull(controller.state.value.skip)
    }

    @Test
    fun `an episode nobody marked offers nothing at all`() = runTest(dispatcher) {
        skipMarks.answer = SkipMarks.NONE
        start()
        advanceUntilIdle()

        engine.moveTo(4_000)
        advanceUntilIdle()

        assertNull(controller.state.value.skip)
    }

    @Test
    fun `an offer that arrives after the opening has begun is shown at once`() = runTest(dispatcher) {
        // The request takes a moment; the episode is already four seconds in when it lands.
        skipMarks.answer = SkipMarks(opening, ending)
        controller.play(PlaybackTarget(100, 4, 0, translation = null))
        engine.ready(episodeMs)
        engine.moveTo(4_000)
        advanceUntilIdle()

        assertEquals(SkipKind.OPENING, controller.state.value.skip?.kind)
    }

    // --- pressing it ----------------------------------------------------------------------------

    @Test
    fun `skipping the opening lands on its last second and takes the offer away`() = runTest(dispatcher) {
        skipMarks.answer = SkipMarks(opening, ending)
        start()
        advanceUntilIdle()
        engine.moveTo(4_000)
        advanceUntilIdle()

        controller.skipOpening()
        advanceUntilIdle()

        assertEquals(93_000L, controller.state.value.positionMs)
        assertNull(controller.state.value.skip)
    }

    @Test
    fun `a friend watching along sees it as the seek it is`() = runTest(dispatcher) {
        skipMarks.answer = SkipMarks(opening, ending)
        val announced = mutableListOf<LocalAction>()
        val collecting = launch { controller.localActions.toList(announced) }
        start()
        advanceUntilIdle()
        engine.moveTo(4_000)
        advanceUntilIdle()

        controller.skipOpening()
        advanceUntilIdle()

        assertTrue(announced.contains(LocalAction.Seek(93_000)))
        collecting.cancel()
    }

    @Test
    fun `pressing nothing on offer does nothing`() = runTest(dispatcher) {
        start()
        advanceUntilIdle()
        engine.moveTo(600_000)
        advanceUntilIdle()

        controller.skipOpening()
        advanceUntilIdle()

        assertEquals(600_000L, controller.state.value.positionMs)
    }

    @Test
    fun `the ending is not skipped by the opening's button`() = runTest(dispatcher) {
        skipMarks.answer = SkipMarks(opening, ending)
        start()
        advanceUntilIdle()
        engine.moveTo(1_462_000)
        advanceUntilIdle()

        controller.skipOpening()
        advanceUntilIdle()

        assertEquals(1_462_000L, controller.state.value.positionMs)
    }
}
