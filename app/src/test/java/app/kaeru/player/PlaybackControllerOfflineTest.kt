package app.kaeru.player

import app.kaeru.domain.connectivity.FakeConnectivity
import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.download.EpisodeDownload
import app.kaeru.domain.download.DeferredDownloadRemoval
import app.kaeru.domain.download.FakeDeferredRemovals
import app.kaeru.domain.download.FakeDownloadRepository
import app.kaeru.domain.error.NetworkUnavailable
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
import app.kaeru.domain.playback.SuppressedMarks
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
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
    private val settings = FakeSettingsStore()
    private lateinit var deleteWatched: DeferredDownloadRemoval
    private val suppressedMarks = SuppressedMarks()
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
        deleteWatched = DeferredDownloadRemoval(downloads, settings, FakeDeferredRemovals())
        controller = DefaultPlaybackController(
            localEngine = engine,
            resolve = ResolveEpisodeStream(source, watchStates, prefs, clock, StreamPrefetchCache(clock)),
            progress = WatchProgress(watchStates, FakePlaybackSampleRepository(watchStates), clock),
            markWatched = MarkEpisodeWatched(library, watchStates, clock, deleteWatched),
            suppressedMarks = suppressedMarks,
            deleteWatchedDownloads = deleteWatched,
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

    /**
     * A device with no network, modelled the way one actually behaves: the flag is down *and* the
     * source cannot be reached. The controller no longer refuses a resolve on the flag alone — a
     * network the platform will not validate is still a network — so a test that only lowered the
     * flag would be testing a phone whose Kodik answers from a tunnel.
     */
    private fun goOffline() {
        connectivity.goOffline()
        source.resolveFailure = NetworkUnavailable(IOException("нет маршрута"))
    }

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
        goOffline()

        controller.play(target(episode = 4))
        advanceUntilIdle()

        // Asked anyway, and the failure is what decides the wording.
        assertEquals(listOf(4), source.resolves)
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
        goOffline()

        controller.changeTranslation(studioBanda)
        advanceUntilIdle()

        // The other voice is asked for — it might be there — and when it is not, the voice already
        // on the device beats a red line over an episode that would play.
        assertEquals(listOf(4), source.resolves)
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
        goOffline()
        val receiver = FakePlaybackEngine()

        controller.switchEngine(receiver, carryPositionMs = 120_000)
        advanceUntilIdle()

        assertTrue(receiver.prepared.isEmpty())
        val failure = controller.state.value.error
        assertTrue(failure is SourceUnavailable)
        assertEquals(SourceUnavailableReason.OFFLINE, (failure as SourceUnavailable).reason)
    }

    @Test
    fun `coming back from a receiver plays the copy on this phone again`() = runTest(dispatcher) {
        downloaded(episode = 4)
        controller.play(target(episode = 4))
        engine.ready(1_440_000)
        advanceUntilIdle()
        val receiver = FakePlaybackEngine()
        controller.switchEngine(receiver, carryPositionMs = 120_000)
        advanceUntilIdle()

        controller.switchEngine(engine, carryPositionMs = 180_000)
        advanceUntilIdle()

        // One resolve in the whole session — the one the receiver needed — and the phone lands
        // back on the file it already has rather than on the television's link, which it would
        // have re-streamed and, with the network gone, not played at all.
        assertEquals(listOf(4), source.resolves)
        val back = engine.prepared.last()
        assertEquals("https://cdn/100/4/11/720?sign=expired", back.url)
        assertEquals(180_000L, back.startPositionMs)
    }

    @Test
    fun `coming back from a receiver with nothing on the device costs no second resolve`() =
        runTest(dispatcher) {
            controller.play(target(episode = 4))
            engine.ready(1_440_000)
            advanceUntilIdle()
            val receiver = FakePlaybackEngine()
            controller.switchEngine(receiver, carryPositionMs = 120_000)
            advanceUntilIdle()

            controller.switchEngine(engine, carryPositionMs = 180_000)
            advanceUntilIdle()

            assertEquals(listOf(4), source.resolves)
            assertEquals("https://cdn/100/4/11/720", engine.prepared.last().url)
        }

    @Test
    fun `an episode picked off the list plays from the device whatever voice it is in`() =
        runTest(dispatcher) {
            // The voice on screen rides along on every episode the viewer picks from the list.
            // That is not a request to reconsider it, so it must not turn a file already on the
            // phone into a stream from Kodik.
            downloaded(episode = 7, track = studioBanda)

            controller.play(target(episode = 7, translation = anilibria))
            advanceUntilIdle()

            assertTrue(source.resolves.isEmpty())
            assertEquals("https://cdn/100/7/22/720?sign=expired", engine.prepared.single().url)
        }

    @Test
    fun `autoplay into an episode downloaded in another voice plays it from the device`() =
        runTest(dispatcher) {
            downloaded(episode = 5, track = studioBanda)
            controller.play(target(episode = 4))
            engine.ready(1_440_000)
            advanceUntilIdle()

            controller.playNext()
            advanceUntilIdle()

            // Only the fourth was ever asked for; the fifth was on the device.
            assertEquals(listOf(4), source.resolves)
            assertEquals("https://cdn/100/5/22/720?sign=expired", engine.prepared.last().url)
        }

    @Test
    fun `a downloaded episode that stops for good says what a viewer can do about it`() =
        runTest(dispatcher) {
            downloaded(episode = 4)
            controller.play(target(episode = 4))
            engine.ready(1_440_000)
            advanceUntilIdle()

            // The bytes went away — «удалить» pressed while it played. media3 calls that a source
            // that would not answer, which is a sentence about a network the file never needed.
            engine.fail(NetworkUnavailable(IOException("gone")))
            advanceUntilIdle()
            engine.fail(NetworkUnavailable(IOException("gone")))
            advanceUntilIdle()

            val failure = controller.state.value.error
            assertTrue(failure is SourceUnavailable)
            assertEquals(SourceUnavailableReason.OFFLINE, (failure as SourceUnavailable).reason)
            // And the screen is told *what* failed, which is the only thing that lets it offer to
            // delete the file rather than to change the voice.
            assertTrue(controller.state.value.failedReadingDownload)
        }

    /**
     * The same downloaded episode, failing on the source instead: the viewer asked for a voice it
     * was not fetched in, so this is Kodik's failure with the file untouched beside it.
     */
    @Test
    fun `a resolve that failed is never blamed on the file, downloaded or not`() = runTest(dispatcher) {
        downloaded(episode = 4, track = anilibria)
        controller.play(target(episode = 4))
        engine.ready(1_440_000)
        advanceUntilIdle()
        source.resolveFailure = NetworkUnavailable(IOException("kodik"))

        controller.changeTranslation(studioBanda)
        advanceUntilIdle()

        assertFalse(controller.state.value.failedReadingDownload)
    }

    @Test
    fun `a cast that failed says nothing about the file either`() = runTest(dispatcher) {
        downloaded(episode = 4)
        controller.play(target(episode = 4))
        engine.ready(1_440_000)
        advanceUntilIdle()
        source.resolveFailure = NetworkUnavailable(IOException("kodik"))

        controller.switchEngine(FakePlaybackEngine(), carryPositionMs = 120_000)
        advanceUntilIdle()

        assertFalse(controller.state.value.failedReadingDownload)
    }

    @Test
    fun `a streamed episode that stops for good still reports what the engine said`() =
        runTest(dispatcher) {
            // The same two failures on an episode that was never on the device: nothing about
            // downloads is said, and the flag that decides it is off after an ordinary open.
            controller.play(target(episode = 4))
            engine.ready(1_440_000)
            advanceUntilIdle()

            engine.fail(NetworkUnavailable(IOException("down")))
            advanceUntilIdle()
            engine.fail(NetworkUnavailable(IOException("down")))
            advanceUntilIdle()

            assertTrue(controller.state.value.error is NetworkUnavailable)
            assertFalse(controller.state.value.failedReadingDownload)
        }

    /**
     * A network the platform will not validate — a portal it cannot probe, a filtered uplink — is
     * still a network. The app finds out by asking rather than by believing the flag, because
     * believing it meant playing nothing at all on a connection where everything worked.
     */
    @Test
    fun `a network the device doubts is still asked, and what answers plays`() = runTest(dispatcher) {
        connectivity.goOffline()

        controller.play(target(episode = 4))
        advanceUntilIdle()

        assertEquals(listOf(4), source.resolves)
        assertEquals("https://cdn/100/4/11/720", engine.prepared.single().url)
        assertNull(controller.state.value.error)
    }

    @Test
    fun `a source that answers with a refusal says so, not «нет сети»`() = runTest(dispatcher) {
        connectivity.goOffline()
        source.rejects = setOf(4)

        controller.play(target(episode = 4))
        advanceUntilIdle()

        val failure = controller.state.value.error
        assertTrue(failure.toString(), failure !is SourceUnavailable || failure.reason != SourceUnavailableReason.OFFLINE)
    }

    @Test
    fun `offline, an episode that is not on the device announces the reason and keeps the one playing`() =
        runTest(dispatcher) {
            downloaded(episode = 4)
            controller.play(target(episode = 4))
            engine.ready(1_440_000)
            advanceUntilIdle()
            goOffline()

            controller.playNext()
            advanceUntilIdle()

            assertEquals(4, controller.state.value.target?.episode)
            assertNull(controller.state.value.error)
        }

    /**
     * «Удалять просмотренные» meets the episode it is about to delete: the mark is raised at nine
     * tenths, and the last tenth is still being read out of the cache. Offline there is no second
     * chance — the address behind the file died weeks ago — so the deletion waits.
     */
    @Test
    fun `an episode being watched offline survives its own watched mark`() = runTest(dispatcher) {
        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(deleteWatched = true)
        downloaded(episode = 4)
        goOffline()
        controller.play(target(episode = 4))
        engine.ready(1_440_000)
        advanceUntilIdle()

        engine.moveTo(1_400_000)
        advanceUntilIdle()

        assertTrue(downloads.removed.isEmpty())
        assertNotNull(downloads.completed(100, 4))
        assertNull(controller.state.value.error)
    }

    @Test
    fun `and gives its space back once the next episode is on`() = runTest(dispatcher) {
        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(deleteWatched = true)
        downloaded(episode = 4)
        downloaded(episode = 5)
        goOffline()
        controller.play(target(episode = 4))
        engine.ready(1_440_000)
        advanceUntilIdle()
        engine.moveTo(1_400_000)
        advanceUntilIdle()

        controller.playNext()
        advanceUntilIdle()

        assertEquals(listOf(100 to 4), downloads.removed)
    }
}
