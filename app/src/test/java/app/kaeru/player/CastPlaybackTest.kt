package app.kaeru.player

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.playback.FakePlaybackPreferences
import app.kaeru.domain.playback.FakeWatchStateRepository
import app.kaeru.domain.playback.MarkEpisodeWatched
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.WatchProgress
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
        controller = DefaultPlaybackController(
            localEngine = phone,
            resolve = ResolveEpisodeStream(source, watchStates, prefs, clock),
            progress = WatchProgress(watchStates, clock),
            markWatched = MarkEpisodeWatched(library, watchStates, clock),
            library = library,
            prefs = prefs,
            headers = headers,
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
        assertTrue(controller.state.value.nextEpisodeAvailable)

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
}
