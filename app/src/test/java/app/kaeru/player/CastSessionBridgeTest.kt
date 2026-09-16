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
import app.kaeru.domain.playback.FakePlaybackSampleRepository
import app.kaeru.domain.playback.FakePlaybackPreferences
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The one thing that connects the Cast framework to playback: a receiver appears, the episode
 * goes to it; the receiver goes away, the episode comes back. Everything else about casting is
 * the controller's, and is tested in [CastPlaybackTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastSessionBridgeTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val clock = MutableClock(now)
    private val headers = StreamHeaders("Chrome/128.0", "https://kodikplayer.com/")

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
            localEngine = phone,
            resolve = ResolveEpisodeStream(source, watchStates, prefs, clock, StreamPrefetchCache(clock)),
            progress = WatchProgress(watchStates, FakePlaybackSampleRepository(watchStates), clock),
            markWatched = MarkEpisodeWatched(library, watchStates, clock, deleteWatched),
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

    private fun bridge(framework: CastFramework) = CastSessionBridge(framework, controller, phone, scope)

    /** Episode 4 playing on the phone, 24 minutes long, five minutes in. */
    private suspend fun playing() {
        controller.play(PlaybackTarget(100, 4, startPositionMs = 0, translation = null))
        phone.ready(1_440_000)
        phone.moveTo(300_000)
    }

    @Test
    fun `a receiver that connects is handed what the phone was playing`() = runTest(dispatcher) {
        val framework = FakeCastFramework(castEngine = receiver)
        bridge(framework).start()
        playing()
        advanceUntilIdle()

        framework.connect()
        advanceUntilIdle()

        val handed = receiver.prepared.single()
        assertEquals("https://cdn/100/4/11/720", handed.url)
        assertEquals(300_000L, handed.startPositionMs)
        assertTrue(controller.state.value.isCasting)
    }

    @Test
    fun `a receiver that disconnects gives playback back at the position it reached`() = runTest(dispatcher) {
        val framework = FakeCastFramework(castEngine = receiver)
        bridge(framework).start()
        playing()
        advanceUntilIdle()
        framework.connect()
        advanceUntilIdle()
        // Only now is the receiver the one playing: until the switch runs, anything it says is
        // about the episode it has not been given yet.
        receiver.ready(1_440_000)
        receiver.moveTo(820_000)
        advanceUntilIdle()

        framework.disconnect()
        advanceUntilIdle()

        val resumed = phone.prepared.last()
        assertEquals(820_000L, resumed.startPositionMs)
        assertEquals("https://cdn/100/4/11/720", resumed.url)
        assertFalse(controller.state.value.isCasting)
    }

    @Test
    fun `a framework still starting up is waited for, not given up on`() = runTest(dispatcher) {
        val framework = FakeCastFramework(available = false, castEngine = receiver)
        val bridge = bridge(framework)
        bridge.start()
        playing()
        advanceUntilIdle()

        // Cast init is a Play services round trip off the main thread, so the first screen sees
        // a framework that is not up yet. Deciding "no cast" there would be deciding too early.
        assertEquals(1, framework.initializations)
        assertEquals(0, framework.subscriptions)

        framework.becomeAvailable()
        advanceUntilIdle()
        framework.connect()
        advanceUntilIdle()

        assertEquals(1, framework.subscriptions)
        assertEquals(300_000L, receiver.prepared.single().startPositionMs)
        assertTrue(controller.state.value.isCasting)
    }

    @Test
    fun `a session that ends gives the receiver's player back`() = runTest(dispatcher) {
        val framework = FakeCastFramework(castEngine = receiver)
        bridge(framework).start()
        playing()
        advanceUntilIdle()
        framework.connect()
        advanceUntilIdle()
        assertEquals(0, framework.releasedEngines)

        framework.disconnect()
        advanceUntilIdle()

        assertEquals(1, framework.releasedEngines)
    }

    @Test
    fun `the receiver's name is published while it has the picture`() = runTest(dispatcher) {
        val framework = FakeCastFramework(castEngine = receiver)
        val bridge = bridge(framework)
        bridge.start()
        playing()
        advanceUntilIdle()
        assertNull(bridge.receiverName.value)

        framework.connect()
        advanceUntilIdle()
        assertEquals("Телевизор в гостиной", bridge.receiverName.value)

        framework.disconnect()
        advanceUntilIdle()
        assertNull(bridge.receiverName.value)
    }

    @Test
    fun `disconnecting asks the framework to end the session and nothing else`() = runTest(dispatcher) {
        val framework = FakeCastFramework(castEngine = receiver)
        val bridge = bridge(framework)
        bridge.start()
        playing()
        advanceUntilIdle()
        framework.connect()
        advanceUntilIdle()

        bridge.disconnect()
        advanceUntilIdle()

        assertEquals(1, framework.endedSessions)
        // Switching back is the framework's announcement to make, so the button and the system
        // output switcher behave identically.
        assertTrue(controller.state.value.isCasting)
    }

    @Test
    fun `a phone that cannot cast never listens and never touches playback`() = runTest(dispatcher) {
        val framework = FakeCastFramework(available = false, castEngine = receiver)
        bridge(framework).start()
        playing()
        advanceUntilIdle()

        framework.connect()
        advanceUntilIdle()

        assertEquals(0, framework.subscriptions)
        assertTrue(receiver.prepared.isEmpty())
        assertFalse(controller.state.value.isCasting)
        assertTrue(phone.state.value.isPlaying)
    }

    @Test
    fun `a framework with no engine to offer leaves playback where it is`() = runTest(dispatcher) {
        val framework = FakeCastFramework(castEngine = null)
        bridge(framework).start()
        playing()
        advanceUntilIdle()

        framework.connect()
        advanceUntilIdle()

        assertFalse(controller.state.value.isCasting)
        assertTrue(phone.state.value.isPlaying)
    }

    @Test
    fun `starting the bridge from every screen still listens once`() = runTest(dispatcher) {
        val framework = FakeCastFramework(castEngine = receiver)
        val bridge = bridge(framework)
        bridge.start()
        bridge.start()
        advanceUntilIdle()
        bridge.start()
        advanceUntilIdle()

        assertEquals(1, framework.subscriptions)
    }
}
