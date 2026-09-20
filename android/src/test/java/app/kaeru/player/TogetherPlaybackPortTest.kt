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
import app.kaeru.domain.playback.FakeSkipMarks
import app.kaeru.domain.playback.FakePlaybackPreferences
import app.kaeru.domain.playback.FakePlaybackSampleRepository
import app.kaeru.domain.playback.FakeWatchStateRepository
import app.kaeru.domain.playback.AddStartedTitleToList
import app.kaeru.domain.playback.MarkEpisodeWatched
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.StreamPrefetchCache
import app.kaeru.domain.playback.SuppressedMarks
import app.kaeru.domain.playback.WatchProgress
import app.kaeru.domain.settings.FakeSettingsStore
import app.kaeru.domain.together.LocalAction
import app.kaeru.test.MutableClock
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The seam a shared session drives the player through, and the one rule that matters at it:
 * what the friend did must reach the picture, and must not come back out as something this
 * viewer did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TogetherPlaybackPortTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val now = Instant.parse("2026-09-16T10:00:00Z")
    private val clock = MutableClock(now)

    private val engine = FakePlaybackEngine()
    private val watchStates = FakeWatchStateRepository()
    private val source = FakeEpisodeSource()
    private val library = FakeLibraryRepository()
    private val prefs = FakePlaybackPreferences()
    private val downloads = FakeDownloadRepository()
    private val settings = FakeSettingsStore()
    private val suppressedMarks = SuppressedMarks()
    private lateinit var controller: DefaultPlaybackController
    private lateinit var port: TogetherPlaybackPort

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
        val resolve = ResolveEpisodeStream(source, watchStates, prefs, clock, StreamPrefetchCache(clock))
        controller = DefaultPlaybackController(
            localEngine = engine,
            resolve = resolve,
            progress = WatchProgress(watchStates, FakePlaybackSampleRepository(watchStates), clock),
            markWatched = MarkEpisodeWatched(library, watchStates, clock, deleteWatched),
            suppressedMarks = suppressedMarks,
            deleteWatchedDownloads = deleteWatched,
            library = library,
            prefs = prefs,
            headers = StreamHeaders("Chrome/128.0", "https://kodikplayer.com/"),
            downloads = downloads,
            connectivity = FakeConnectivity(),
            addToList = AddStartedTitleToList(library),
            skipMarks = FakeSkipMarks(),
            scope = scope,
            io = dispatcher,
        )
        port = TogetherPlaybackPort(controller, resolve, scope)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun target(episode: Int = 4, startPositionMs: Long = 0) =
        PlaybackTarget(animeId = 100, episode = episode, startPositionMs = startPositionMs, translation = null)

    @Test
    fun `the port shows the episode, the voice and where it is`() = runTest(dispatcher) {
        controller.play(target(episode = 4, startPositionMs = 65_000))
        engine.ready(durationMs = 1_440_000)
        advanceUntilIdle()

        val state = port.state.value
        assertEquals(100, state.animeId)
        assertEquals(4, state.episode)
        assertEquals(source.anilibria.id, state.translationId)
        assertEquals(65_000L, state.positionMs)
        assertTrue(state.playing)
        assertFalse(state.buffering)
    }

    @Test
    fun `a viewer pressing pause and play is announced once each`() = runTest(dispatcher) {
        controller.play(target())
        engine.ready(durationMs = 1_440_000)
        advanceUntilIdle()
        val seen = mutableListOf<LocalAction>()
        val watching = launch { port.localActions.toList(seen) }
        advanceUntilIdle()

        engine.moveTo(30_000)
        advanceUntilIdle()
        controller.togglePlayPause()
        advanceUntilIdle()
        controller.togglePlayPause()
        advanceUntilIdle()

        assertEquals(
            listOf(LocalAction.Pause(30_000), LocalAction.Play(30_000)),
            seen,
        )
        watching.cancel()
    }

    @Test
    fun `a viewer scrubbing is announced with where they landed`() = runTest(dispatcher) {
        controller.play(target())
        engine.ready(durationMs = 1_440_000)
        advanceUntilIdle()
        val seen = mutableListOf<LocalAction>()
        val watching = launch { port.localActions.toList(seen) }
        advanceUntilIdle()

        controller.seekTo(120_000)
        advanceUntilIdle()

        assertEquals(listOf(LocalAction.Seek(120_000)), seen)
        watching.cancel()
    }

    @Test
    fun `an episode the viewer opened is announced with the voice it actually resolved to`() =
        runTest(dispatcher) {
            val seen = mutableListOf<LocalAction>()
            val watching = launch { port.localActions.toList(seen) }
            advanceUntilIdle()

            controller.play(target(episode = 4))
            advanceUntilIdle()

            assertEquals(listOf(LocalAction.Episode(100, 4, source.anilibria.id)), seen)
            watching.cancel()
        }

    @Test
    fun `the episode autoplay runs into is announced too`() = runTest(dispatcher) {
        controller.play(target(episode = 4))
        engine.ready(durationMs = 1_440_000)
        advanceUntilIdle()
        val seen = mutableListOf<LocalAction>()
        val watching = launch { port.localActions.toList(seen) }
        advanceUntilIdle()

        controller.playNext()
        advanceUntilIdle()

        assertEquals(listOf(LocalAction.Episode(100, 5, source.anilibria.id)), seen)
        watching.cancel()
    }

    @Test
    fun `an expired link resolved again behind the viewer is not an episode change`() =
        runTest(dispatcher) {
            controller.play(target(episode = 4))
            engine.ready(durationMs = 1_440_000)
            advanceUntilIdle()
            val seen = mutableListOf<LocalAction>()
            val watching = launch { port.localActions.toList(seen) }
            advanceUntilIdle()

            engine.fail(app.kaeru.domain.error.NetworkUnavailable(java.io.IOException("403")))
            advanceUntilIdle()

            assertEquals(2, engine.prepared.size)
            assertEquals(emptyList<LocalAction>(), seen)
            watching.cancel()
        }

    @Test
    fun `nothing the session applies comes back as something the viewer did`() = runTest(dispatcher) {
        controller.play(target(episode = 4))
        engine.ready(durationMs = 1_440_000)
        advanceUntilIdle()
        val seen = mutableListOf<LocalAction>()
        val watching = launch { port.localActions.toList(seen) }
        advanceUntilIdle()

        port.pause()
        port.play()
        port.seekTo(240_000)
        port.setRate(0.97f)
        port.duck(true)
        port.duck(false)
        port.openEpisode(animeId = 100, episode = 5, translationId = source.studioBanda.id, positionMs = 0)
        advanceUntilIdle()

        assertEquals(emptyList<LocalAction>(), seen)
        watching.cancel()
    }

    @Test
    fun `the port calls the episode ready only once the engine reports a length`() = runTest(dispatcher) {
        source.gate = CompletableDeferred()
        val starting = launch { controller.play(target(episode = 7, startPositionMs = 300_000)) }
        advanceUntilIdle()

        // Named at once, so a session waiting for it would be satisfied here — with nothing
        // prepared to seek yet.
        assertEquals(7, port.state.value.episode)
        assertFalse(port.state.value.ready)

        source.gate?.complete(Unit)
        advanceUntilIdle()
        starting.join()
        // Prepared, but the manifest is not read: still nothing to seek.
        assertEquals(1, engine.prepared.size)
        assertFalse(port.state.value.ready)

        engine.ready(durationMs = 1_440_000)
        advanceUntilIdle()
        assertTrue(port.state.value.ready)

        // And a seek made now sticks: the start position the episode was prepared at is the
        // viewer's own, the picture is where the friend is.
        port.seekTo(930_000)
        advanceUntilIdle()
        assertEquals(930_000L, engine.state.value.positionMs)
        assertEquals(300_000L, engine.prepared.single().startPositionMs)
    }

    @Test
    fun `the episode stops being ready while another voice is being resolved for it`() = runTest(dispatcher) {
        controller.play(target(episode = 4))
        engine.ready(durationMs = 1_440_000)
        advanceUntilIdle()
        assertTrue(port.state.value.ready)

        source.gate = CompletableDeferred()
        val changing = launch { controller.changeTranslation(source.studioBanda) }
        advanceUntilIdle()
        assertFalse(port.state.value.ready)

        source.gate?.complete(Unit)
        advanceUntilIdle()
        changing.join()
        assertFalse(port.state.value.ready)

        engine.ready(durationMs = 1_440_000)
        advanceUntilIdle()
        assertTrue(port.state.value.ready)
    }

    @Test
    fun `turning the picture down for a voice reaches the engine`() = runTest(dispatcher) {
        controller.play(target(episode = 4))
        engine.ready(durationMs = 1_440_000)
        advanceUntilIdle()

        port.duck(true)
        advanceUntilIdle()
        assertEquals(listOf(true), engine.ducks)

        port.duck(false)
        advanceUntilIdle()
        assertEquals(listOf(true, false), engine.ducks)
    }

    @Test
    fun `what the session asks for reaches the engine`() = runTest(dispatcher) {
        controller.play(target(episode = 4))
        engine.ready(durationMs = 1_440_000)
        advanceUntilIdle()

        port.pause()
        advanceUntilIdle()
        assertFalse(engine.state.value.isPlaying)

        port.play()
        advanceUntilIdle()
        assertTrue(engine.state.value.isPlaying)

        port.seekTo(240_000)
        advanceUntilIdle()
        assertEquals(240_000L, engine.state.value.positionMs)

        port.setRate(0.97f)
        advanceUntilIdle()
        assertEquals(0.97f, engine.rate, 0.0001f)
    }

    @Test
    fun `a friend's play does not pause a phone that is already playing`() = runTest(dispatcher) {
        controller.play(target(episode = 4))
        engine.ready(durationMs = 1_440_000)
        advanceUntilIdle()
        assertTrue(engine.state.value.isPlaying)

        port.play()
        advanceUntilIdle()

        assertTrue(engine.state.value.isPlaying)
    }

    @Test
    fun `an episode the friend opened plays in their voice when this device has it`() =
        runTest(dispatcher) {
            controller.play(target(episode = 4))
            engine.ready(durationMs = 1_440_000)
            advanceUntilIdle()

            port.openEpisode(animeId = 100, episode = 5, translationId = source.studioBanda.id, positionMs = 90_000)
            advanceUntilIdle()

            assertEquals(5, port.state.value.episode)
            assertEquals(source.studioBanda.id, port.state.value.translationId)
            assertEquals(90_000L, engine.prepared.last().startPositionMs)
        }

    @Test
    fun `a voice this device cannot get is not an error, it plays its own`() = runTest(dispatcher) {
        controller.play(target(episode = 4))
        engine.ready(durationMs = 1_440_000)
        advanceUntilIdle()

        port.openEpisode(animeId = 100, episode = 5, translationId = 999, positionMs = 0)
        advanceUntilIdle()

        assertEquals(5, port.state.value.episode)
        assertEquals(source.anilibria.id, port.state.value.translationId)
    }
}
