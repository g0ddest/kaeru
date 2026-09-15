package app.kaeru.player

import app.kaeru.domain.connectivity.FakeConnectivity
import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.download.FakeDownloadRepository
import app.kaeru.domain.error.SourceUnavailable
import app.kaeru.domain.error.SourceUnavailableReason
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.playback.FakePlaybackPreferences
import app.kaeru.domain.playback.FakePlaybackSampleRepository
import app.kaeru.domain.playback.FakeWatchStateRepository
import app.kaeru.domain.playback.MarkEpisodeWatched
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.StreamPrefetchCache
import app.kaeru.domain.playback.WatchProgress
import app.kaeru.domain.settings.FakeSettingsStore
import app.kaeru.test.MutableClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Playing what is already on the device, and saying so plainly when there is nothing on it.
 *
 * A finished download is a resolve that has already happened: the link is written down, the
 * bytes are in the cache, and asking Kodik again would only fail — that is what «офлайн» means.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerOfflineTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val clock = MutableClock(now)

    private val anilibria = Translation(11, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12)
    private val studioBanda = Translation(22, "Студийная банда", TranslationKind.VOICE, episodesCount = 12)
    private val headers = StreamHeaders("Chrome/128.0", "https://kodikplayer.com/")

    private val engine = FakePlaybackEngine()
    private val watchStates = FakeWatchStateRepository()
    private val source = FakeEpisodeSource()
    private val library = FakeLibraryRepository()
    private val prefs = FakePlaybackPreferences()
    private val downloads = FakeDownloadRepository()
    private val connectivity = FakeConnectivity()
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
        controller = DefaultPlaybackController(
            localEngine = engine,
            resolve = ResolveEpisodeStream(source, watchStates, prefs, clock, StreamPrefetchCache(clock)),
            progress = WatchProgress(watchStates, FakePlaybackSampleRepository(watchStates), clock),
            markWatched = MarkEpisodeWatched(library, watchStates, clock, FakeDownloadRepository(), FakeSettingsStore()),
            library = library,
            prefs = prefs,
            headers = headers,
            downloads = downloads,
            connectivity = connectivity,
            scope = scope,
            io = dispatcher,
        )
    }

    @After
    fun tearDown() = scope.cancel()

    private fun target(episode: Int = 4, startPositionMs: Long = 0, translation: Translation? = null) =
        PlaybackTarget(animeId = 100, episode = episode, startPositionMs = startPositionMs, translation = translation)

    private fun downloaded(episode: Int = 4, track: Translation = anilibria, quality: Quality = Quality.P720) =
        downloads.downloaded(
            animeId = 100,
            episode = episode,
            translation = track,
            url = "https://cdn/100/$episode/${track.id}/${quality.height}?sign=expired",
            quality = quality,
        )

    @Test
    fun `a downloaded episode plays without asking the source for anything`() = runTest(dispatcher) {
        downloaded(episode = 4)

        controller.play(target(episode = 4, startPositionMs = 65_000))
        advanceUntilIdle()

        assertTrue(source.resolves.isEmpty())
        val prepared = engine.prepared.single()
        assertEquals("https://cdn/100/4/11/720?sign=expired", prepared.url)
        assertEquals(65_000L, prepared.startPositionMs)
        assertEquals(Quality.P720, controller.state.value.quality)
    }

    @Test
    fun `a downloaded episode carries the track it was downloaded in`() = runTest(dispatcher) {
        downloaded(episode = 4, track = studioBanda)

        controller.play(target(episode = 4))
        advanceUntilIdle()

        assertEquals(22, controller.state.value.stream?.translation?.id)
        assertEquals("4 серия   Студийная банда", engine.prepared.single().metadata?.subtitle)
    }

    @Test
    fun `the track a downloaded episode played in is remembered like any other`() = runTest(dispatcher) {
        downloaded(episode = 4, track = studioBanda)

        controller.play(target(episode = 4))
        engine.ready(1_440_000)
        engine.moveTo(120_000)
        advanceUntilIdle()

        assertEquals(22, watchStates.saved.last().translationId)
    }

    @Test
    fun `no network and nothing on the device is a failure that names the reason`() = runTest(dispatcher) {
        connectivity.goOffline()

        controller.play(target(episode = 4))
        advanceUntilIdle()

        assertTrue(source.resolves.isEmpty())
        assertTrue(engine.prepared.isEmpty())
        val failure = controller.state.value.error
        assertTrue(failure is SourceUnavailable)
        assertEquals(SourceUnavailableReason.OFFLINE, (failure as SourceUnavailable).reason)
    }

    @Test
    fun `with a network and nothing on the device the episode is resolved as before`() = runTest(dispatcher) {
        controller.play(target(episode = 4))
        advanceUntilIdle()

        assertEquals(listOf(4), source.resolves)
        assertEquals("https://cdn/100/4/11/720", engine.prepared.single().url)
        assertNull(controller.state.value.error)
    }

    @Test
    fun `a half-finished download is not something to play from`() = runTest(dispatcher) {
        // Only a finished download counts: an episode still arriving goes down the resolve path
        // like any other, because half its segments are not in the cache yet.
        downloads.put(
            EpisodeDownload(
                key = DownloadKey(100, 4, anilibria.id, Quality.P720),
                state = DownloadState.DOWNLOADING,
                bytes = 12_000_000,
                progress = 0.4f,
                failure = null,
                updatedAt = now,
            ),
        )

        controller.play(target(episode = 4))
        advanceUntilIdle()

        assertEquals(listOf(4), source.resolves)
        assertEquals("https://cdn/100/4/11/720", engine.prepared.single().url)
    }

    @Test
    fun `a voice the viewer picked is resolved rather than answered with the download`() =
        runTest(dispatcher) {
            downloaded(episode = 4, track = anilibria)
            controller.play(target(episode = 4))
            advanceUntilIdle()

            controller.changeTranslation(studioBanda)
            advanceUntilIdle()

            assertEquals(listOf(4), source.resolves)
            assertEquals("https://cdn/100/4/22/720", engine.prepared.last().url)
        }

    @Test
    fun `offline, the download plays even when another voice was asked for`() = runTest(dispatcher) {
        downloaded(episode = 4, track = anilibria)
        controller.play(target(episode = 4))
        advanceUntilIdle()
        connectivity.goOffline()

        controller.changeTranslation(studioBanda)
        advanceUntilIdle()

        assertTrue(source.resolves.isEmpty())
        assertEquals("https://cdn/100/4/11/720?sign=expired", engine.prepared.last().url)
        assertNull(controller.state.value.error)
    }

    @Test
    fun `the next episode plays from the device when that is where it is`() = runTest(dispatcher) {
        downloaded(episode = 4)
        downloaded(episode = 5)

        controller.play(target(episode = 4))
        engine.ready(1_440_000)
        advanceUntilIdle()
        controller.playNext()
        advanceUntilIdle()

        assertTrue(source.resolves.isEmpty())
        assertEquals("https://cdn/100/5/11/720?sign=expired", engine.prepared.last().url)
        assertEquals(5, controller.state.value.target?.episode)
    }

    @Test
    fun `a receiver is given a fresh link rather than the copy on this phone`() = runTest(dispatcher) {
        // The television fetches from the CDN itself and cannot read this phone's cache, so the
        // expired address a download carries would leave it on a black screen.
        downloaded(episode = 4)
        controller.play(target(episode = 4))
        engine.ready(1_440_000)
        advanceUntilIdle()
        val receiver = FakePlaybackEngine()

        controller.switchEngine(receiver, carryPositionMs = 120_000)
        advanceUntilIdle()

        assertEquals(listOf(4), source.resolves)
        val handed = receiver.prepared.single()
        assertEquals("https://cdn/100/4/11/720", handed.url)
        assertEquals(120_000L, handed.startPositionMs)
    }

    @Test
    fun `offline, a receiver cannot be given a downloaded episode at all`() = runTest(dispatcher) {
        downloaded(episode = 4)
        controller.play(target(episode = 4))
        engine.ready(1_440_000)
        advanceUntilIdle()
        connectivity.goOffline()
        val receiver = FakePlaybackEngine()

        controller.switchEngine(receiver, carryPositionMs = 120_000)
        advanceUntilIdle()

        assertTrue(receiver.prepared.isEmpty())
        val failure = controller.state.value.error
        assertTrue(failure is SourceUnavailable)
        assertEquals(SourceUnavailableReason.OFFLINE, (failure as SourceUnavailable).reason)
    }

    @Test
    fun `an episode resolved for a receiver plays on this phone again without another resolve`() =
        runTest(dispatcher) {
            downloaded(episode = 4)
            controller.play(target(episode = 4))
            engine.ready(1_440_000)
            advanceUntilIdle()
            val receiver = FakePlaybackEngine()
            controller.switchEngine(receiver, carryPositionMs = 120_000)
            advanceUntilIdle()

            controller.switchEngine(engine, carryPositionMs = 180_000)
            advanceUntilIdle()

            // One resolve in the whole session: the one the receiver needed.
            assertEquals(listOf(4), source.resolves)
            assertEquals("https://cdn/100/4/11/720", engine.prepared.last().url)
        }

    @Test
    fun `offline, an episode that is not on the device announces the reason and keeps the one playing`() =
        runTest(dispatcher) {
            downloaded(episode = 4)
            controller.play(target(episode = 4))
            engine.ready(1_440_000)
            advanceUntilIdle()
            connectivity.goOffline()

            controller.playNext()
            advanceUntilIdle()

            assertEquals(4, controller.state.value.target?.episode)
            assertNull(controller.state.value.error)
        }
}
