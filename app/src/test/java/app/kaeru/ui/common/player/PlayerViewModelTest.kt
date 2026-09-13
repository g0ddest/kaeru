package app.kaeru.ui.common.player

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
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.playback.FakePlaybackPreferences
import app.kaeru.domain.playback.FakeWatchStateRepository
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.source.EpisodeSourceProvider
import app.kaeru.player.EpisodeQueue
import app.kaeru.player.CastSessionBridge
import app.kaeru.player.FakeCastFramework
import app.kaeru.player.FakePlaybackEngine
import app.kaeru.player.PlaybackEvent
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val clock = MutableClock(now)
    private val anilibria = Translation(11, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12)
    private val studioBanda = Translation(22, "Студийная банда", TranslationKind.VOICE, episodesCount = 12)

    private val controller = FakePlaybackController()
    private val watchStates = FakeWatchStateRepository()
    private val library = FakeLibraryRepository()
    private val source = FakeEpisodeSource()
    // AniLibria is on the viewer's list, which is what puts it above the other track.
    private val prefs = FakePlaybackPreferences(preferred = listOf("AniLibria"))
    private lateinit var viewModel: PlayerViewModel

    private val anime = Anime(
        100, "Фрирен", "Frieren", "https://poster", emptyList(), AnimeStatus.RELEASED,
        episodes = 12, episodesAired = 12, nextEpisodeAt = null,
        score = 9.1, year = 2023, studio = "Madhouse", description = null,
    )

    private val castFramework = FakeCastFramework()

    /** The real bridge over a fake framework: the screen talks to it, so the test does too. */
    private val cast = CastSessionBridge(
        framework = castFramework,
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
            resolve = ResolveEpisodeStream(source, watchStates, prefs, clock),
            library = library,
            watchStates = watchStates,
            prefs = prefs,
            io = main.dispatcher,
        )
    }

    private fun stream(episode: Int = 4, track: Translation = anilibria) = EpisodeStream(
        animeId = 100,
        episode = episode,
        translation = track,
        urls = mapOf(Quality.P480 to "https://cdn/480", Quality.P720 to "https://cdn/720"),
        resolvedAt = now,
    )

    @Test
    fun `starting an episode resumes the position saved for that very episode`() = runTest(main.dispatcher) {
        watchStates.seed(WatchState(100, 4, 320_000, 1_440_000, translationId = 11, kodikSeason = 1, updatedAt = now))

        viewModel.start(animeId = 100, episode = 4)
        advanceUntilIdle()

        assertEquals(PlaybackTarget(100, 4, 320_000, null), controller.played.single())
    }

    @Test
    fun `another episode than the saved one starts from the beginning`() = runTest(main.dispatcher) {
        watchStates.seed(WatchState(100, 4, 320_000, 1_440_000, translationId = 11, kodikSeason = 1, updatedAt = now))

        viewModel.start(animeId = 100, episode = 5)
        advanceUntilIdle()

        assertEquals(0L, controller.played.single().startPositionMs)
    }

    @Test
    fun `an episode that was already watched to the end starts over instead of at its last frame`() =
        runTest(main.dispatcher) {
            watchStates.seed(WatchState(100, 4, 1_430_000, 1_440_000, translationId = 11, kodikSeason = 1, updatedAt = now))

            viewModel.start(animeId = 100, episode = 4)
            advanceUntilIdle()

            assertEquals(0L, controller.played.single().startPositionMs)
        }

    @Test
    fun `the same episode is not started twice when the screen comes back`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        viewModel.start(100, 4)
        advanceUntilIdle()

        assertEquals(1, controller.played.size)
    }

    @Test
    fun `the two calls a screen makes on its way in are one playback`() = runTest(main.dispatcher) {
        // The lifecycle asks and composition asks, both before the first resume position is read.
        viewModel.start(100, 4)
        viewModel.start(100, 4)
        advanceUntilIdle()

        assertEquals(1, controller.played.size)
    }

    @Test
    fun `a screen that comes back to a player with nothing loaded starts it again`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        controller.playback.value = PlaybackState()

        viewModel.start(100, 4)
        advanceUntilIdle()

        assertEquals(2, controller.played.size)
    }

    @Test
    fun `the screen shows the anime, the episode and what is playing`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        controller.playback.value = PlaybackState(
            target = PlaybackTarget(100, 4, 0, null),
            stream = stream(),
            quality = Quality.P720,
            isPlaying = true,
            positionMs = 320_000,
            durationMs = 1_440_000,
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Фрирен", state.title)
        assertEquals("https://poster", state.posterUrl)
        assertEquals(4, state.episode)
        assertEquals("AniLibria.TV", state.translationTitle)
        assertEquals(Quality.P720, state.quality)
        assertEquals(listOf(Quality.P480, Quality.P720), state.qualities)
        assertTrue(state.isPlaying)
        assertEquals(320_000L, state.positionMs)
        assertEquals(1_440_000L, state.durationMs)
    }

    @Test
    fun `a failure is shown in the viewer's language, not the exception's`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        controller.playback.update { it.copy(error = NetworkUnavailable(IOException("boom"))) }
        advanceUntilIdle()

        assertEquals("Нет соединения. Проверьте интернет", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `opening the track sheet loads what the source offers, ranked`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()

        viewModel.openTranslations()
        advanceUntilIdle()

        assertEquals(PlayerSheet.TRANSLATIONS, viewModel.uiState.value.sheet)
        assertEquals(listOf(anilibria, studioBanda), viewModel.uiState.value.translations)
        assertFalse(viewModel.uiState.value.loadingTranslations)
    }

    @Test
    fun `a track the source will not list is reported instead of an empty sheet`() = runTest(main.dispatcher) {
        source.translationsResult = Result.failure(NetworkUnavailable(IOException("down")))
        viewModel.start(100, 4)
        advanceUntilIdle()

        viewModel.openTranslations()
        advanceUntilIdle()

        assertEquals("Нет соединения. Проверьте интернет", viewModel.uiState.value.toast)
        assertNull(viewModel.uiState.value.sheet)
    }

    @Test
    fun `picking a track hands it to the player and closes the sheet`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        viewModel.openTranslations()
        advanceUntilIdle()

        assertEquals(PlayerSheet.TRANSLATIONS, viewModel.uiState.value.sheet)

        viewModel.pickTranslation(studioBanda)
        advanceUntilIdle()

        assertEquals(listOf(studioBanda), controller.tracks)
        assertNull(viewModel.uiState.value.sheet)
    }

    @Test
    fun `picking a quality hands it to the player and closes the sheet`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        viewModel.openQualities()
        advanceUntilIdle()
        assertEquals(PlayerSheet.QUALITY, viewModel.uiState.value.sheet)

        viewModel.pickQuality(Quality.P480)
        advanceUntilIdle()

        assertEquals(listOf(Quality.P480), controller.qualities)
        assertNull(viewModel.uiState.value.sheet)
    }

    @Test
    fun `the seek buttons move by ten seconds and the opening skip by an opening`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        controller.playback.update { it.copy(positionMs = 100_000, durationMs = 1_440_000) }

        viewModel.seekBy(-EpisodeQueue.SEEK_STEP_MS)
        viewModel.seekBy(EpisodeQueue.SEEK_STEP_MS)
        viewModel.skipIntro()

        assertEquals(listOf(90_000L, 110_000L, 185_000L), controller.seeks)
    }

    @Test
    fun `finishing the last episode offers to close the show, and yes writes it down`() = runTest(main.dispatcher) {
        viewModel.start(100, 12)
        advanceUntilIdle()

        controller.announced.emit(PlaybackEvent.SuggestCompleted(100))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.completedPrompt)

        viewModel.confirmCompleted()
        advanceUntilIdle()

        assertEquals(listOf(100 to ListStatus.COMPLETED), library.statusWrites)
        assertFalse(viewModel.uiState.value.completedPrompt)
    }

    @Test
    fun `later leaves the show where it was`() = runTest(main.dispatcher) {
        viewModel.start(100, 12)
        advanceUntilIdle()
        controller.announced.emit(PlaybackEvent.SuggestCompleted(100))
        advanceUntilIdle()

        viewModel.dismissCompleted()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.completedPrompt)
        assertTrue(library.statusWrites.isEmpty())
    }

    @Test
    fun `an episode that has not aired is a passing message, not an error screen`() = runTest(main.dispatcher) {
        viewModel.start(100, 12)
        advanceUntilIdle()

        controller.announced.emit(PlaybackEvent.NextEpisodeUnavailable(EpisodeNotAvailable(100, 13)))
        advanceUntilIdle()

        assertEquals("Серия ещё не появилась в Kodik", viewModel.uiState.value.toast)
        assertNull(viewModel.uiState.value.errorMessage)

        viewModel.consumeToast()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.toast)
    }

    @Test
    fun `the countdown and the next episode offer come straight from the player`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        controller.playback.update { it.copy(nextEpisodeAvailable = true, autoplayCountdownSec = 7) }
        advanceUntilIdle()

        assertEquals(7, viewModel.uiState.value.autoplayCountdownSec)
        assertTrue(viewModel.uiState.value.nextEpisodeAvailable)

        viewModel.cancelAutoplay()
        viewModel.playNext()
        advanceUntilIdle()

        assertEquals(1, controller.cancels)
        assertEquals(1, controller.nexts)
    }

    @Test
    fun `retrying asks the player to resolve the episode again`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        controller.playback.update { it.copy(error = EpisodeNotAvailable(100, 4)) }
        advanceUntilIdle()

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(1, controller.retries)
    }

    @Test
    fun `leaving the screen writes the position down and lets the player go`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()

        viewModel.reportProgress()
        viewModel.release()

        assertEquals(1, controller.reports)
        assertEquals(1, controller.releases)
    }

    private inner class FakeEpisodeSource : EpisodeSourceProvider {
        var translationsResult: Result<List<Translation>> = Result.success(listOf(studioBanda, anilibria))

        override suspend fun translations(shikimoriId: Int) = translationsResult

        override suspend fun resolve(shikimoriId: Int, episode: Int, translation: Translation?) =
            Result.success(stream(episode, translation ?: anilibria))
    }

    private class FakeLibraryRepository : LibraryRepository {
        private val entries = MutableStateFlow<Map<Int, LibraryEntry>>(emptyMap())
        val statusWrites = mutableListOf<Pair<Int, ListStatus>>()

        fun put(entry: LibraryEntry) = entries.update { it + (entry.anime.id to entry) }

        override fun observeLibrary(): Flow<List<LibraryEntry>> = entries.map { it.values.toList() }
        override fun observeAnime(id: Int): Flow<LibraryEntry?> = entries.map { it[id] }
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = entries.map { it[id]?.anime }
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun refreshAnime(id: Int) = Result.success(Unit)
        override suspend fun search(query: String) = Result.success(emptyList<Anime>())
        override suspend fun setEpisodes(animeId: Int, episodes: Int) = Result.success(Unit)

        override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> {
            statusWrites += animeId to status
            return Result.success(Unit)
        }
    }

    @Test
    fun `a screen casting says so, so it can draw a remote control instead of a player`() = runTest(main.dispatcher) {
        viewModel.start(animeId = 100, episode = 4)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isCasting)

        controller.playback.value = controller.playback.value.copy(isCasting = true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isCasting)
    }

    @Test
    fun `disconnecting ends the session and leaves the switching back to the bridge`() = runTest(main.dispatcher) {
        viewModel.start(animeId = 100, episode = 4)
        advanceUntilIdle()

        viewModel.stopCasting()
        advanceUntilIdle()

        assertEquals(1, castFramework.endedSessions)
        assertTrue(controller.switches.isEmpty())
    }

    @Test
    fun `coming back to a screen while the receiver is still playing does not start over`() = runTest(main.dispatcher) {
        // A receiver keeps the episode when the player screen closes, so the controller is
        // still loaded. Playing it again would interrupt a television for nothing — and with a
        // new view model there is no `requested` left to notice.
        controller.playback.value = PlaybackState(
            target = PlaybackTarget(100, 4, 0, null),
            isCasting = true,
            positionMs = 400_000,
        )

        viewModel.start(animeId = 100, episode = 4)
        advanceUntilIdle()

        assertTrue(controller.played.isEmpty())
        assertTrue(viewModel.uiState.value.isCasting)
        assertEquals("Фрирен", viewModel.uiState.value.title)
    }

    @Test
    fun `a different episode asked for while casting is played, not ignored`() = runTest(main.dispatcher) {
        controller.playback.value = PlaybackState(target = PlaybackTarget(100, 4, 0, null), isCasting = true)

        viewModel.start(animeId = 100, episode = 7)
        advanceUntilIdle()

        assertEquals(7, controller.played.single().episode)
    }

    @Test
    fun `a screen coming forward says so, so playback left on a receiver knows someone is there`() =
        runTest(main.dispatcher) {
            viewModel.start(animeId = 100, episode = 4)
            advanceUntilIdle()
            assertEquals(1, controller.attaches)

            controller.playback.value = PlaybackState(target = PlaybackTarget(100, 4, 0, null), isCasting = true)
            viewModel.start(animeId = 100, episode = 4)
            advanceUntilIdle()

            // Even the call that starts nothing has to attach: it is the one a screen reopened
            // over an ongoing cast makes.
            assertEquals(2, controller.attaches)
        }
}
