package app.kaeru.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.kaeru.data.library.AppPreferences
import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.error.NetworkUnavailable
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
import app.kaeru.domain.playback.FakeWatchStateRepository
import app.kaeru.domain.playback.MarkEpisodeWatched
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.WatchProgress
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.source.EpisodeSourceProvider
import app.kaeru.test.MutableClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val storeScope = TestScope(dispatcher)
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
    private lateinit var store: DataStore<Preferences>
    private lateinit var prefs: AppPreferences
    private lateinit var controller: DefaultPlaybackController

    @Before
    fun setUp() {
        store = PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "prefs.preferences_pb") }
        prefs = AppPreferences(store)
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
            engine = engine,
            resolve = ResolveEpisodeStream(source, watchStates, prefs, clock),
            progress = WatchProgress(watchStates, clock),
            markWatched = MarkEpisodeWatched(library, watchStates, clock),
            prefs = prefs,
            headers = headers,
            scope = scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        storeScope.cancel()
    }

    private fun target(episode: Int = 4, startPositionMs: Long = 0, translation: Translation? = null) =
        PlaybackTarget(animeId = 100, episode = episode, startPositionMs = startPositionMs, translation = translation)

    /** Plays [episode] and reports a manifest of [durationMs], the way a real start goes. */
    private suspend fun start(episode: Int = 4, startPositionMs: Long = 0, durationMs: Long = 1_440_000) {
        controller.play(target(episode, startPositionMs))
        engine.ready(durationMs)
    }

    @Test
    fun `playing an episode resolves it and starts the best quality at the asked position`() = runTest(dispatcher) {
        controller.play(target(episode = 4, startPositionMs = 65_000))
        advanceUntilIdle()

        val prepared = engine.prepared.single()
        assertEquals("https://cdn/100/4/11/720", prepared.url)
        assertEquals(65_000L, prepared.startPositionMs)
        assertEquals(headers, prepared.headers)
        assertTrue(engine.state.value.isPlaying)
        assertEquals(Quality.P720, controller.state.value.quality)
        assertEquals(4, controller.state.value.target?.episode)
    }

    @Test
    fun `playback starts on the quality the viewer settled on`() = runTest(dispatcher) {
        prefs.setDefaultQuality(Quality.P480)

        controller.play(target())
        advanceUntilIdle()

        assertEquals("https://cdn/100/4/11/480", engine.prepared.single().url)
        assertEquals(Quality.P480, controller.state.value.quality)
    }

    @Test
    fun `an episode that cannot be resolved is reported instead of played`() = runTest(dispatcher) {
        source.resolveFailure = NetworkUnavailable(java.io.IOException("down"))

        controller.play(target())
        advanceUntilIdle()

        assertTrue(engine.prepared.isEmpty())
        assertTrue(controller.state.value.error is NetworkUnavailable)
        assertFalse(controller.state.value.isBuffering)
    }

    @Test
    fun `the position is written down every five seconds of playback`() = runTest(dispatcher) {
        start()
        engine.moveTo(4_000)
        advanceUntilIdle()
        assertTrue(watchStates.saved.none { it.positionMs == 4_000L })

        engine.moveTo(5_000)
        advanceUntilIdle()
        engine.moveTo(9_000)
        advanceUntilIdle()
        engine.moveTo(10_000)
        advanceUntilIdle()

        assertEquals(listOf(5_000L, 10_000L), watchStates.saved.map { it.positionMs }.filter { it > 0 })
        assertEquals(11, watchStates.saved.last().translationId)
    }

    @Test
    fun `pausing writes the position down even between the five second marks`() = runTest(dispatcher) {
        start()
        engine.moveTo(3_000)
        advanceUntilIdle()

        controller.togglePlayPause()
        advanceUntilIdle()

        assertEquals(3_000L, watchStates.saved.last().positionMs)
        assertFalse(controller.state.value.isPlaying)
    }

    @Test
    fun `nothing is written down while the episode has no length yet`() = runTest(dispatcher) {
        controller.play(target(startPositionMs = 30_000))
        advanceUntilIdle()

        assertTrue(watchStates.saved.none { it.durationMs == 0L && it.positionMs > 0 })
        assertEquals(30_000L, controller.state.value.positionMs)
    }

    @Test
    fun `crossing the threshold marks the episode watched exactly once`() = runTest(dispatcher) {
        start(episode = 4, durationMs = 1_000_000)

        engine.moveTo(900_000)
        advanceUntilIdle()
        engine.moveTo(910_000)
        advanceUntilIdle()

        assertEquals(listOf(100 to 4), library.episodeWrites)
    }

    @Test
    fun `a rewind past the threshold does not mark the episode again`() = runTest(dispatcher) {
        start(episode = 4, durationMs = 1_000_000)
        engine.moveTo(950_000)
        advanceUntilIdle()

        engine.seekTo(100_000)
        advanceUntilIdle()
        engine.moveTo(960_000)
        advanceUntilIdle()

        assertEquals(listOf(100 to 4), library.episodeWrites)
    }

    @Test
    fun `finishing the announced last episode asks whether the show is done`() = runTest(dispatcher) {
        val events = mutableListOf<PlaybackEvent>()
        scope.launch { controller.events.collect { events += it } }
        advanceUntilIdle()

        start(episode = 12, durationMs = 1_000_000)
        engine.moveTo(950_000)
        advanceUntilIdle()

        assertEquals(listOf(PlaybackEvent.SuggestCompleted(100)), events)
    }

    @Test
    fun `the next episode is offered half a minute before the end and counted down`() = runTest(dispatcher) {
        start(durationMs = 1_440_000)

        engine.moveTo(1_412_000)
        advanceUntilIdle()
        assertTrue(controller.state.value.nextEpisodeAvailable)
        assertNull(controller.state.value.autoplayCountdownSec)

        engine.moveTo(1_433_000)
        advanceUntilIdle()
        assertEquals(7, controller.state.value.autoplayCountdownSec)
    }

    @Test
    fun `with autoplay off the next episode is only offered, never started`() = runTest(dispatcher) {
        prefs.setAutoplayNext(false)
        start(durationMs = 1_440_000)

        engine.moveTo(1_435_000)
        advanceUntilIdle()
        engine.end()
        advanceUntilIdle()

        assertTrue(controller.state.value.nextEpisodeAvailable)
        assertNull(controller.state.value.autoplayCountdownSec)
        assertEquals(1, engine.prepared.size)
    }

    @Test
    fun `cancelling the countdown keeps the offer and stops the switch`() = runTest(dispatcher) {
        start(durationMs = 1_440_000)
        engine.moveTo(1_435_000)
        advanceUntilIdle()

        controller.cancelAutoplay()
        advanceUntilIdle()
        engine.end()
        advanceUntilIdle()

        assertNull(controller.state.value.autoplayCountdownSec)
        assertTrue(controller.state.value.nextEpisodeAvailable)
        assertEquals(1, engine.prepared.size)
    }

    @Test
    fun `an episode that runs out starts the next one in the same track`() = runTest(dispatcher) {
        start(episode = 4, durationMs = 1_440_000)
        engine.moveTo(1_439_000)
        advanceUntilIdle()
        engine.end()
        advanceUntilIdle()

        assertEquals(5, controller.state.value.target?.episode)
        assertEquals("https://cdn/100/5/11/720", engine.prepared.last().url)
        assertEquals(0L, engine.prepared.last().startPositionMs)
        assertFalse(controller.state.value.nextEpisodeAvailable)
        assertNull(controller.state.value.autoplayCountdownSec)
    }

    @Test
    fun `an episode that has not aired leaves the current one alone and says so`() = runTest(dispatcher) {
        val events = mutableListOf<PlaybackEvent>()
        scope.launch { controller.events.collect { events += it } }
        start(episode = 12, durationMs = 1_440_000)
        advanceUntilIdle()

        controller.playNext()
        advanceUntilIdle()

        assertEquals(listOf(PlaybackEvent.NextEpisodeMissing), events)
        assertEquals(12, controller.state.value.target?.episode)
        assertNull(controller.state.value.error)
        assertEquals(1, engine.prepared.size)
    }

    @Test
    fun `changing quality swaps the source and keeps the position`() = runTest(dispatcher) {
        start()
        engine.moveTo(320_000)
        advanceUntilIdle()

        controller.changeQuality(Quality.P480)
        advanceUntilIdle()

        assertEquals("https://cdn/100/4/11/480", engine.prepared.last().url)
        assertEquals(320_000L, engine.prepared.last().startPositionMs)
        assertEquals(Quality.P480, controller.state.value.quality)
    }

    @Test
    fun `changing the track resolves the same episode at the same position`() = runTest(dispatcher) {
        start()
        engine.moveTo(320_000)
        advanceUntilIdle()

        controller.changeTranslation(studioBanda)
        advanceUntilIdle()

        assertEquals("https://cdn/100/4/22/720", engine.prepared.last().url)
        assertEquals(320_000L, engine.prepared.last().startPositionMs)
        assertEquals(studioBanda, controller.state.value.stream?.translation)
        assertEquals(4, controller.state.value.target?.episode)
    }

    @Test
    fun `an expired link is resolved again once, without bothering the viewer`() = runTest(dispatcher) {
        start()
        engine.moveTo(320_000)
        advanceUntilIdle()

        engine.fail(NetworkUnavailable(java.io.IOException("403")))
        advanceUntilIdle()

        assertEquals(2, engine.prepared.size)
        assertEquals(320_000L, engine.prepared.last().startPositionMs)
        assertNull(controller.state.value.error)
    }

    @Test
    fun `a link that fails twice is a failure the viewer is told about`() = runTest(dispatcher) {
        start()
        engine.fail(NetworkUnavailable(java.io.IOException("403")))
        advanceUntilIdle()
        engine.ready(1_440_000)
        engine.fail(NetworkUnavailable(java.io.IOException("403 again")))
        advanceUntilIdle()

        assertEquals(2, engine.prepared.size)
        assertTrue(controller.state.value.error is NetworkUnavailable)
        assertFalse(controller.state.value.isPlaying)
    }

    @Test
    fun `a retry after a failure starts the episode again from where it stopped`() = runTest(dispatcher) {
        source.resolveFailure = NetworkUnavailable(java.io.IOException("down"))
        controller.play(target(startPositionMs = 120_000))
        advanceUntilIdle()

        source.resolveFailure = null
        controller.retry()
        advanceUntilIdle()

        assertEquals(120_000L, engine.prepared.single().startPositionMs)
        assertNull(controller.state.value.error)
    }

    @Test
    fun `seeking stays inside the episode`() = runTest(dispatcher) {
        // Autoplay off, so a seek that lands exactly on the end is not answered by the next episode.
        prefs.setAutoplayNext(false)
        start(durationMs = 600_000)
        engine.moveTo(595_000)
        advanceUntilIdle()

        controller.seekBy(EpisodeQueue.SEEK_STEP_MS)
        advanceUntilIdle()
        assertEquals(600_000L, engine.state.value.positionMs)

        controller.seekTo(-5_000)
        advanceUntilIdle()
        assertEquals(0L, engine.state.value.positionMs)
    }

    @Test
    fun `dragging the slider to the very end is a finished episode`() = runTest(dispatcher) {
        start(episode = 4, durationMs = 600_000)

        controller.seekTo(600_000)
        advanceUntilIdle()

        assertEquals(5, controller.state.value.target?.episode)
    }

    @Test
    fun `releasing writes the last position down and stops the engine`() = runTest(dispatcher) {
        start()
        engine.moveTo(123_000)
        advanceUntilIdle()

        controller.release()
        advanceUntilIdle()

        assertEquals(123_000L, watchStates.saved.last().positionMs)
        assertEquals(1, engine.releases)
        assertNull(controller.state.value.target)
    }

    @Test
    fun `a position written down for one episode is not attributed to the next`() = runTest(dispatcher) {
        start(episode = 4, durationMs = 1_000_000)
        engine.moveTo(950_000)
        advanceUntilIdle()

        controller.playNext()
        engine.ready(1_000_000)
        engine.moveTo(6_000)
        advanceUntilIdle()

        val last = watchStates.saved.last()
        assertEquals(5, last.episode)
        assertEquals(6_000L, last.positionMs)
        assertTrue(watchStates.saved.any { it.episode == 4 && it.positionMs == 950_000L })
    }

    private class FakeEpisodeSource : EpisodeSourceProvider {
        val anilibria = Translation(11, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12)
        val studioBanda = Translation(22, "Студийная банда", TranslationKind.VOICE, episodesCount = 12)
        var resolveFailure: Throwable? = null
        var lastAired = 12

        override suspend fun translations(shikimoriId: Int): Result<List<Translation>> =
            Result.success(listOf(anilibria, studioBanda))

        override suspend fun resolve(
            shikimoriId: Int,
            episode: Int,
            translation: Translation?,
        ): Result<EpisodeStream> {
            resolveFailure?.let { return Result.failure(it) }
            if (episode > lastAired) return Result.failure(EpisodeNotAvailable(shikimoriId, episode))
            val track = translation ?: anilibria
            return Result.success(
                EpisodeStream(
                    animeId = shikimoriId,
                    episode = episode,
                    translation = track,
                    urls = listOf(Quality.P360, Quality.P480, Quality.P720).associateWith {
                        "https://cdn/$shikimoriId/$episode/${track.id}/${it.height}"
                    },
                    resolvedAt = Instant.parse("2026-09-13T10:00:00Z"),
                ),
            )
        }
    }

    private class FakeLibraryRepository : LibraryRepository {
        private val entries = MutableStateFlow<Map<Int, LibraryEntry>>(emptyMap())
        val episodeWrites = mutableListOf<Pair<Int, Int>>()
        val statusWrites = mutableListOf<Pair<Int, ListStatus>>()

        fun put(entry: LibraryEntry) = entries.update { it + (entry.anime.id to entry) }

        override fun observeLibrary(): Flow<List<LibraryEntry>> = entries.map { it.values.toList() }
        override fun observeAnime(id: Int): Flow<LibraryEntry?> = entries.map { it[id] }
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = entries.map { it[id]?.anime }
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun refreshAnime(id: Int) = Result.success(Unit)
        override suspend fun search(query: String) = Result.success(emptyList<Anime>())

        override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> {
            statusWrites += animeId to status
            entries.update { rows ->
                val row = rows[animeId] ?: return@update rows
                rows + (animeId to row.copy(rate = row.rate.copy(status = status)))
            }
            return Result.success(Unit)
        }

        override suspend fun setEpisodes(animeId: Int, episodes: Int): Result<Unit> {
            episodeWrites += animeId to episodes
            entries.update { rows ->
                val row = rows[animeId] ?: return@update rows
                rows + (animeId to row.copy(rate = row.rate.copy(episodes = episodes)))
            }
            return Result.success(Unit)
        }
    }
}
