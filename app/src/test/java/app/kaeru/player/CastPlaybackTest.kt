package app.kaeru.player

import app.kaeru.domain.connectivity.FakeConnectivity
import app.kaeru.domain.download.DeferredDownloadRemoval
import app.kaeru.domain.download.FakeDeferredRemovals
import app.kaeru.domain.download.FakeDownloadRepository
import app.kaeru.domain.error.CastLoadFailed
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.playback.FakePlaybackSampleRepository
import app.kaeru.domain.playback.FakePlaybackPreferences
import app.kaeru.domain.playback.FakeWatchStateRepository
import app.kaeru.domain.playback.AddStartedTitleToList
import app.kaeru.domain.playback.MarkEpisodeWatched
import app.kaeru.domain.playback.SuppressedMarks
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.StreamPrefetchCache
import app.kaeru.domain.playback.WatchProgress
import app.kaeru.domain.settings.FakeSettingsStore
import app.kaeru.test.MutableClock
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Playback moving between the phone and a Chromecast.
 *
 * The controller does not know what a Chromecast is: casting is one more [PlaybackEngine], and
 * everything the controller decides — saving the position, counting an episode as watched,
 * starting the next one — has to keep working whichever engine is reporting. That is exactly
 * what two fake engines can prove, so none of this needs a receiver in the room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastPlaybackTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val clock = MutableClock(now)
    private val headers = StreamHeaders("Chrome/128.0", "https://kodikplayer.com/")

    /** The two engines, named after where the picture is rather than after their classes. */
    private val phone = FakePlaybackEngine()
    private val receiver = FakePlaybackEngine()

    private val watchStates = FakeWatchStateRepository()
    private val source = FakeEpisodeSource()
    private val library = FakeLibraryRepository()
    private val prefs = FakePlaybackPreferences()
    private val downloads = FakeDownloadRepository()
    private val settings = FakeSettingsStore()
    private lateinit var deleteWatched: DeferredDownloadRemoval
    private val suppressedMarks = SuppressedMarks()
    private lateinit var controller: DefaultPlaybackController

    /** Twenty-four minutes, so the threshold lands at 21:36 and the last 30 s are easy to hit. */
    private val episodeLength = 1_440_000L

    @Before
    fun setUp() {
        library.put(
            LibraryEntry(
                Anime(
                    100, "Фрирен", "Frieren", "https://img/100.jpg", emptyList(), AnimeStatus.RELEASED,
                    episodes = 12, episodesAired = 12, nextEpisodeAt = null,
                    score = null, year = null, studio = null, description = null,
                ),
                UserRate(1, 100, ListStatus.WATCHING, episodes = 3, updatedAt = now),
                null,
            ),
        )
        deleteWatched = DeferredDownloadRemoval(downloads, settings, FakeDeferredRemovals())
        controller = DefaultPlaybackController(
            localEngine = phone,
            resolve = ResolveEpisodeStream(source, watchStates, prefs, clock, StreamPrefetchCache(clock)),
            progress = WatchProgress(watchStates, FakePlaybackSampleRepository(watchStates), clock),
            markWatched = MarkEpisodeWatched(library, watchStates, clock, deleteWatched),
            addToList = AddStartedTitleToList(library),
            suppressedMarks = suppressedMarks,
            deleteWatchedDownloads = deleteWatched,
            library = library,
            prefs = prefs,
            headers = headers,
            downloads = downloads,
            connectivity = FakeConnectivity(),
            scope = scope,
            io = dispatcher,
        )
    }

    @After
    fun tearDown() = scope.cancel()

    private fun target(episode: Int = 4, startPositionMs: Long = 0) =
        PlaybackTarget(animeId = 100, episode = episode, startPositionMs = startPositionMs, translation = null)

    /** Plays [episode] on the phone and reports a manifest, the way a real start goes. */
    private suspend fun playOnPhone(episode: Int = 4) {
        controller.play(target(episode))
        phone.ready(episodeLength)
    }

    /** Hands playback over the way the session bridge does: at the position the phone reached. */
    private suspend fun castNow() = controller.switchEngine(receiver, controller.state.value.positionMs)

    @Test
    fun `casting hands the receiver the same episode at the position the phone reached`() = runTest(dispatcher) {
        playOnPhone()
        phone.moveTo(300_000)
        advanceUntilIdle()

        castNow()
        advanceUntilIdle()

        val handed = receiver.prepared.single()
        assertEquals("https://cdn/100/4/11/720", handed.url)
        assertEquals(300_000L, handed.startPositionMs)
        assertEquals(4, controller.state.value.target?.episode)
        assertEquals(11, controller.state.value.stream?.translation?.id)
        assertEquals(Quality.P720, controller.state.value.quality)
        assertTrue(receiver.state.value.isPlaying)
    }

    @Test
    fun `the state says whether the picture is on this phone or on a receiver`() = runTest(dispatcher) {
        playOnPhone()
        advanceUntilIdle()
        assertFalse(controller.state.value.isCasting)

        castNow()
        advanceUntilIdle()
        assertTrue(controller.state.value.isCasting)

        controller.switchEngine(phone, controller.state.value.positionMs)
        advanceUntilIdle()
        assertFalse(controller.state.value.isCasting)
    }

    @Test
    fun `the phone stops playing when the receiver takes over`() = runTest(dispatcher) {
        playOnPhone()
        advanceUntilIdle()

        castNow()
        advanceUntilIdle()

        assertEquals(1, phone.releases)
        assertFalse(phone.state.value.isPlaying)
    }

    @Test
    fun `the receiver is told the anime, the episode, the track and the poster`() = runTest(dispatcher) {
        playOnPhone()
        advanceUntilIdle()

        castNow()
        advanceUntilIdle()

        val announced = receiver.prepared.single().metadata
        assertEquals("Фрирен", announced?.title)
        assertEquals("4 серия   AniLibria.TV", announced?.subtitle)
        assertEquals("https://img/100.jpg", announced?.artworkUrl)
    }

    @Test
    fun `the position the phone reached is on disk before the receiver starts`() = runTest(dispatcher) {
        playOnPhone()
        phone.moveTo(300_000)
        advanceUntilIdle()
        watchStates.saved.clear()

        castNow()
        advanceUntilIdle()

        assertEquals(300_000L, watchStates.saved.first().positionMs)
        assertEquals(4, watchStates.saved.first().episode)
    }

    @Test
    fun `progress after the switch is the receiver's, and the phone's ticks are ignored`() = runTest(dispatcher) {
        playOnPhone()
        advanceUntilIdle()
        castNow()
        receiver.ready(episodeLength)
        advanceUntilIdle()

        receiver.moveTo(310_000)
        advanceUntilIdle()
        assertEquals(310_000L, watchStates.saved.last().positionMs)

        // The phone is no longer the one playing; whatever it still says goes nowhere.
        phone.ready(episodeLength)
        phone.moveTo(900_000)
        advanceUntilIdle()
        assertEquals(310_000L, watchStates.saved.last().positionMs)
        assertEquals(310_000L, controller.state.value.positionMs)
    }

    @Test
    fun `an episode watched on the receiver is counted, exactly once`() = runTest(dispatcher) {
        playOnPhone()
        advanceUntilIdle()
        castNow()
        receiver.ready(episodeLength)
        advanceUntilIdle()

        receiver.moveTo(1_300_000)
        advanceUntilIdle()
        receiver.moveTo(1_320_000)
        advanceUntilIdle()

        assertEquals(listOf(100 to 4), library.episodeWrites)
    }

    @Test
    fun `an episode already counted on the phone is not counted again on the receiver`() = runTest(dispatcher) {
        playOnPhone()
        phone.moveTo(1_300_000)
        advanceUntilIdle()
        assertEquals(1, library.episodeWrites.size)

        castNow()
        receiver.ready(episodeLength)
        advanceUntilIdle()
        receiver.moveTo(1_320_000)
        advanceUntilIdle()

        assertEquals(1, library.episodeWrites.size)
    }

    @Test
    fun `ending the session carries on locally where the receiver stopped`() = runTest(dispatcher) {
        playOnPhone()
        advanceUntilIdle()
        castNow()
        receiver.ready(episodeLength)
        receiver.moveTo(700_000)
        advanceUntilIdle()

        controller.switchEngine(phone, controller.state.value.positionMs)
        advanceUntilIdle()

        val resumed = phone.prepared.last()
        assertEquals(700_000L, resumed.startPositionMs)
        assertEquals("https://cdn/100/4/11/720", resumed.url)
        assertEquals(headers, resumed.headers)
        assertFalse(controller.state.value.isCasting)
        assertEquals(1, receiver.releases)
        assertTrue(phone.state.value.isPlaying)
    }

    @Test
    fun `the next episode starts by itself on the receiver`() = runTest(dispatcher) {
        playOnPhone()
        advanceUntilIdle()
        castNow()
        receiver.ready(episodeLength)
        advanceUntilIdle()

        receiver.moveTo(1_415_000)
        advanceUntilIdle()
        assertTrue(controller.state.value.nextEpisodeDue)

        receiver.moveTo(1_437_000)
        advanceUntilIdle()
        assertEquals(3, controller.state.value.autoplayCountdownSec)

        receiver.end()
        advanceUntilIdle()

        assertEquals(5, controller.state.value.target?.episode)
        assertEquals("https://cdn/100/5/11/720", receiver.prepared.last().url)
        assertTrue(controller.state.value.isCasting)
        assertEquals(1, phone.prepared.size)
    }

    @Test
    fun `a session that starts before anything plays sends the first episode to the receiver`() = runTest(dispatcher) {
        controller.switchEngine(receiver, 0)
        advanceUntilIdle()

        assertTrue(controller.state.value.isCasting)
        assertTrue(receiver.prepared.isEmpty())
        assertNull(controller.state.value.target)

        controller.play(target(episode = 2))
        advanceUntilIdle()

        assertEquals("https://cdn/100/2/11/720", receiver.prepared.single().url)
        assertTrue(phone.prepared.isEmpty())
        assertTrue(controller.state.value.isCasting)
    }

    @Test
    fun `changing the quality while casting reloads on the receiver from the same second`() = runTest(dispatcher) {
        playOnPhone()
        advanceUntilIdle()
        castNow()
        receiver.ready(episodeLength)
        receiver.moveTo(250_000)
        advanceUntilIdle()

        controller.changeQuality(Quality.P480)
        advanceUntilIdle()

        val reloaded = receiver.prepared.last()
        assertEquals("https://cdn/100/4/11/480", reloaded.url)
        assertEquals(250_000L, reloaded.startPositionMs)
        assertEquals(Quality.P480, controller.state.value.quality)
    }

    @Test
    fun `switching to the engine that is already playing changes nothing`() = runTest(dispatcher) {
        playOnPhone()
        advanceUntilIdle()

        controller.switchEngine(phone, 100_000)
        advanceUntilIdle()

        assertEquals(1, phone.prepared.size)
        assertEquals(0, phone.releases)
        assertFalse(controller.state.value.isCasting)
    }

    @Test
    fun `a receiver that connects while the episode is still resolving still gets it`() = runTest(dispatcher) {
        source.gate = CompletableDeferred()
        val starting = launch { controller.play(target(episode = 4, startPositionMs = 90_000)) }
        advanceUntilIdle()
        assertTrue(phone.prepared.isEmpty())

        // The resolve the phone started is cut short by the switch; the receiver has to finish
        // the job, or the screen spins forever with no error to retry from.
        source.gate = null
        controller.switchEngine(receiver, controller.state.value.positionMs)
        advanceUntilIdle()
        starting.join()

        val handed = receiver.prepared.single()
        assertEquals("https://cdn/100/4/11/720", handed.url)
        assertEquals(90_000L, handed.startPositionMs)
        assertTrue(phone.prepared.isEmpty())
        assertTrue(controller.state.value.isCasting)
        assertNull(controller.state.value.error)
        assertNotNull(controller.state.value.stream)
    }

    @Test
    fun `a session that ends while the next episode is resolving starts it on the phone`() = runTest(dispatcher) {
        playOnPhone()
        advanceUntilIdle()
        castNow()
        receiver.ready(episodeLength)
        advanceUntilIdle()

        // Autoplay is several seconds of Kodik round trip, and a viewer who disconnects inside
        // that window must still get the next episode — the brief's own acceptance criterion.
        source.gate = CompletableDeferred()
        receiver.end()
        advanceUntilIdle()
        assertEquals(listOf(4, 5), source.resolves)

        source.gate = null
        controller.switchEngine(phone, controller.state.value.positionMs)
        advanceUntilIdle()

        val started = phone.prepared.last()
        assertEquals("https://cdn/100/5/11/720", started.url)
        assertEquals(0L, started.startPositionMs)
        assertEquals(5, controller.state.value.target?.episode)
        assertFalse(controller.state.value.isCasting)
        assertNull(controller.state.value.error)
    }

    @Test
    fun `an episode resolved again after an interrupted start can still be counted as watched`() =
        runTest(dispatcher) {
            playOnPhone()
            phone.moveTo(1_300_000)
            advanceUntilIdle()
            assertEquals(1, library.episodeWrites.size)

            // Episode 5 is a new episode: the counting the interrupted start never got to reset
            // has to be reset by whoever finishes it.
            source.gate = CompletableDeferred()
            phone.end()
            advanceUntilIdle()
            source.gate = null
            castNow()
            receiver.ready(episodeLength)
            advanceUntilIdle()

            assertEquals(5, controller.state.value.target?.episode)
            receiver.moveTo(1_300_000)
            advanceUntilIdle()

            assertEquals(listOf(100 to 4, 100 to 5), library.episodeWrites)
        }

    @Test
    fun `leaving the screen while casting stops nothing in the other room`() = runTest(dispatcher) {
        playOnPhone()
        advanceUntilIdle()
        castNow()
        receiver.ready(episodeLength)
        receiver.moveTo(400_000)
        advanceUntilIdle()

        controller.release()
        advanceUntilIdle()

        assertEquals(0, receiver.releases)
        assertTrue(receiver.state.value.isPlaying)
        assertTrue(controller.state.value.isCasting)
        assertEquals(4, controller.state.value.target?.episode)
        assertEquals(400_000L, watchStates.saved.last().positionMs)
    }

    @Test
    fun `leaving the screen while the phone is playing still stops it`() = runTest(dispatcher) {
        playOnPhone()
        phone.moveTo(400_000)
        advanceUntilIdle()

        controller.release()
        advanceUntilIdle()

        assertEquals(1, phone.releases)
        assertNull(controller.state.value.target)
        assertFalse(controller.state.value.isCasting)
        assertEquals(400_000L, watchStates.saved.last().positionMs)
    }

    @Test
    fun `a receiver that never loads the episode says so, after one try in silence`() = runTest(dispatcher) {
        playOnPhone()
        advanceUntilIdle()
        castNow()
        receiver.ready(episodeLength)
        advanceUntilIdle()

        receiver.fail(CastLoadFailed())
        advanceUntilIdle()
        // The first one is answered by resolving again, on the chance the link simply expired.
        assertNull(controller.state.value.error)
        assertEquals(2, source.resolves.size)

        receiver.fail(CastLoadFailed())
        advanceUntilIdle()

        assertTrue(controller.state.value.error is CastLoadFailed)
    }

    @Test
    fun `a session that ends after the screen is gone does not start playing on the phone`() = runTest(dispatcher) {
        playOnPhone()
        advanceUntilIdle()
        castNow()
        receiver.ready(episodeLength)
        receiver.moveTo(640_000)
        advanceUntilIdle()
        val preparedBefore = phone.prepared.size

        // The player screen closes; the television plays on. Then somebody turns it off.
        controller.release()
        advanceUntilIdle()
        controller.switchEngine(phone, controller.state.value.positionMs)
        advanceUntilIdle()

        // Nothing holds playback, so nothing starts: a phone in a pocket must not begin playing
        // an episode aloud with no player, no notification and no session to stop it with.
        assertEquals(preparedBefore, phone.prepared.size)
        assertFalse(phone.state.value.isPlaying)
        assertNull(controller.state.value.target)
        assertNull(controller.state.value.stream)
        assertFalse(controller.state.value.isCasting)
        // The position is on disk, so «Продолжить» picks the episode up later.
        assertEquals(4, watchStates.saved.last().episode)
        assertEquals(640_000L, watchStates.saved.last().positionMs)
    }

    @Test
    fun `a screen that comes back before the session ends still continues locally`() = runTest(dispatcher) {
        playOnPhone()
        advanceUntilIdle()
        castNow()
        receiver.ready(episodeLength)
        receiver.moveTo(640_000)
        advanceUntilIdle()
        controller.release()
        advanceUntilIdle()

        // Someone opened the player again while the television was still playing.
        controller.attachScreen()
        controller.switchEngine(phone, controller.state.value.positionMs)
        advanceUntilIdle()

        assertEquals(640_000L, phone.prepared.last().startPositionMs)
        assertTrue(phone.state.value.isPlaying)
        assertEquals(4, controller.state.value.target?.episode)
    }

    @Test
    fun `a switch while the next episode is only intended still starts the next episode`() = runTest(dispatcher) {
        playOnPhone()
        advanceUntilIdle()

        // Every transition that resolves writes the position down first. A switch landing in
        // that window must resume the episode being moved to, not the one it supersedes.
        watchStates.block()
        val advancing = launch { controller.playNext() }
        advanceUntilIdle()
        assertEquals(listOf(4), source.resolves)

        val switching = launch { controller.switchEngine(receiver, controller.state.value.positionMs) }
        advanceUntilIdle()
        watchStates.release()
        advanceUntilIdle()
        advancing.join()
        switching.join()

        assertEquals("https://cdn/100/5/11/720", receiver.prepared.last().url)
        assertEquals(5, controller.state.value.target?.episode)
        assertTrue(controller.state.value.isCasting)
    }

    @Test
    fun `a next episode that will not resolve after a disconnect is a message, not an error screen`() =
        runTest(dispatcher) {
            val announced = mutableListOf<PlaybackEvent>()
            val collector = launch { controller.events.collect { announced += it } }

            playOnPhone()
            advanceUntilIdle()
            castNow()
            receiver.ready(episodeLength)
            advanceUntilIdle()

            source.gate = CompletableDeferred()
            receiver.end()
            advanceUntilIdle()

            source.gate = null
            source.rejects = setOf(5)
            controller.switchEngine(phone, controller.state.value.positionMs)
            advanceUntilIdle()

            // The episode that just finished is still what the screen is showing; a red error
            // line about it would be about the wrong episode.
            assertNull(controller.state.value.error)
            assertTrue(announced.any { it is PlaybackEvent.NextEpisodeUnavailable })
            assertNull(controller.state.value.autoplayCountdownSec)
            assertEquals(4, controller.state.value.target?.episode)
            collector.cancel()
        }
}
