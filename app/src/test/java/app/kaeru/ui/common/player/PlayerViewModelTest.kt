package app.kaeru.ui.common.player

import app.kaeru.domain.connectivity.FakeConnectivity
import app.kaeru.domain.download.FakeDownloadRepository
import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.PlaybackTarget
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.playback.FakeEpisodeProgressRepository
import app.kaeru.domain.playback.FakePlaybackPreferences
import app.kaeru.domain.playback.FakeWatchStateRepository
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.StreamPrefetchCache
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
import kotlinx.coroutines.flow.first
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
    private val episodes = FakeEpisodeProgressRepository()
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
            resolve = ResolveEpisodeStream(source, watchStates, prefs, clock, StreamPrefetchCache(clock)),
            library = library,
            watchStates = watchStates,
            episodeProgress = episodes,
            prefs = prefs,
            downloads = FakeDownloadRepository(),
            connectivity = FakeConnectivity(),
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
    fun `each episode is resumed from its own position`() = runTest(main.dispatcher) {
        episodes.seed(EpisodeProgress(100, 6, 300_000, 1_440_000, now))
        episodes.seed(EpisodeProgress(100, 7, 2_400_000, 2_880_000, now))
        // The pointer names the seventh, because that is the one that played last.
        watchStates.seed(WatchState(100, 7, 2_400_000, 2_880_000, translationId = 11, kodikSeason = 1, updatedAt = now))

        // Going back to the sixth picks the sixth up where it was left, not where the seventh is.
        viewModel.start(animeId = 100, episode = 6)
        advanceUntilIdle()

        assertEquals(300_000L, controller.played.single().startPositionMs)
        assertEquals(2_400_000L, episodes.observe(100).first().single { it.episode == 7 }.positionMs)
    }

    @Test
    fun `an episode the per-episode table has never heard of starts from the beginning`() =
        runTest(main.dispatcher) {
            episodes.seed(EpisodeProgress(100, 7, 2_400_000, 2_880_000, now))

            viewModel.start(animeId = 100, episode = 5)
            advanceUntilIdle()

            assertEquals(0L, controller.played.single().startPositionMs)
        }

    @Test
    fun `an episode holding nothing but a mis-tap starts from the beginning`() = runTest(main.dispatcher) {
        // The same ten seconds the watch button refuses to offer: asked for on purpose, the
        // episode starts where the viewer expects it to, not ten seconds in.
        episodes.seed(EpisodeProgress(100, 6, 10_000, 1_440_000, now))

        viewModel.start(animeId = 100, episode = 6)
        advanceUntilIdle()

        assertEquals(0L, controller.played.single().startPositionMs)
    }

    @Test
    fun `an episode opened at a given position starts there, not at the one saved for it`() =
        runTest(main.dispatcher) {
            // Joining a friend: the row this device keeps for the episode is not where they are.
            episodes.seed(EpisodeProgress(100, 7, 300_000, 1_440_000, now))
            watchStates.seed(WatchState(100, 7, 300_000, 1_440_000, translationId = 11, kodikSeason = 1, updatedAt = now))

            viewModel.start(animeId = 100, episode = 7, explicit = true, startPositionMs = 930_000)
            advanceUntilIdle()

            assertEquals(PlaybackTarget(100, 7, 930_000, null), controller.played.single())
        }

    @Test
    fun `a screen coming back never carries a position`() = runTest(main.dispatcher) {
        // Recreated, or relaunched out of recents, with the intent the join built an hour ago.
        episodes.seed(EpisodeProgress(100, 7, 300_000, 1_440_000, now))

        viewModel.start(animeId = 100, episode = 7, explicit = false, startPositionMs = 930_000)
        advanceUntilIdle()

        assertEquals(300_000L, controller.played.single().startPositionMs)
    }

    @Test
    fun `an episode already playing is not restarted or moved by a position carried in`() =
        runTest(main.dispatcher) {
            viewModel.start(100, 4)
            advanceUntilIdle()
            playingOn(episode = 7, positionMs = 300_000)
            val started = controller.played.size

            // The session's own seek is what moves a prepared player; a seek made here would be
            // announced to the friend as this viewer's.
            viewModel.start(100, 7, explicit = true, startPositionMs = 930_000)
            advanceUntilIdle()

            assertEquals(started, controller.played.size)
            assertTrue(controller.seeks.isEmpty())
        }

    @Test
    fun `choosing an episode from the remote resumes that episode's own position`() = runTest(main.dispatcher) {
        episodes.seed(EpisodeProgress(100, 9, 700_000, 1_440_000, now))
        viewModel.start(100, 4)
        advanceUntilIdle()

        viewModel.playEpisode(9)
        advanceUntilIdle()

        assertEquals(700_000L, controller.played.last().startPositionMs)
    }

    @Test
    fun `the remote control draws a strip on every episode left part-watched`() = runTest(main.dispatcher) {
        episodes.seed(EpisodeProgress(100, 5, 720_000, 1_440_000, now))
        episodes.seed(EpisodeProgress(100, 7, 360_000, 1_440_000, now))

        viewModel.start(100, 4)
        advanceUntilIdle()

        val cells = viewModel.uiState.value.episodes
        assertEquals(0.5f, cells.single { it.number == 5 }.progress!!, 0.001f)
        assertEquals(0.25f, cells.single { it.number == 7 }.progress!!, 0.001f)
        assertNull(cells.single { it.number == 6 }.progress)
    }

    // --- coming back to a player that moved on --------------------------------------------------

    /** Autoplay moved the session on while the screen was away; the intent still names episode 4. */
    private fun playingOn(episode: Int, positionMs: Long = 300_000, animeId: Int = 100) {
        controller.playback.value = PlaybackState(
            target = PlaybackTarget(animeId, episode, startPositionMs = 0, translation = null),
            stream = stream(episode = episode),
            quality = Quality.P720,
            isPlaying = true,
            positionMs = positionMs,
            durationMs = 1_440_000,
            airedEpisodes = 12,
        )
    }

    @Test
    fun `coming back finds the episode the session reached, not the one it was opened with`() =
        runTest(main.dispatcher) {
            viewModel.start(100, 4)
            advanceUntilIdle()
            val started = controller.played.size
            playingOn(episode = 7, positionMs = 300_000)

            viewModel.start(100, 4, explicit = false)
            advanceUntilIdle()

            assertEquals(started, controller.played.size)
            assertEquals(7, viewModel.uiState.value.episode)
            assertEquals(300_000L, viewModel.uiState.value.positionMs)
        }

    @Test
    fun `coming back attaches the screen so a receiver knows it is being watched again`() =
        runTest(main.dispatcher) {
            viewModel.start(100, 4)
            advanceUntilIdle()
            playingOn(episode = 7)
            val attached = controller.attaches

            viewModel.start(100, 4, explicit = false)
            advanceUntilIdle()

            assertEquals(attached + 1, controller.attaches)
        }

    @Test
    fun `a failed session is come back to, not replaced by the episode the intent names`() =
        runTest(main.dispatcher) {
            viewModel.start(100, 4)
            advanceUntilIdle()
            playingOn(episode = 7)
            controller.playback.update { it.copy(isPlaying = false, error = NetworkUnavailable(IOException("offline"))) }
            val started = controller.played.size

            viewModel.start(100, 4, explicit = false)
            advanceUntilIdle()

            assertEquals(started, controller.played.size)
            assertEquals(7, viewModel.uiState.value.episode)
            assertEquals("Нет соединения. Проверьте интернет", viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `coming back to a player with nothing in it starts what the intent names`() =
        runTest(main.dispatcher) {
            viewModel.start(100, 4, explicit = false)
            advanceUntilIdle()

            assertEquals(4, controller.played.single().episode)
        }

    @Test
    fun `a player killed and relaunched comes back to the episode this anime remembers`() =
        runTest(main.dispatcher) {
            // Nothing is loaded — the process died — and the intent still names the episode the
            // viewer opened hours and three episodes ago.
            watchStates.seed(WatchState(100, 7, 300_000, 1_440_000, translationId = 11, kodikSeason = 1, updatedAt = now))

            viewModel.start(100, 4, explicit = false)
            advanceUntilIdle()

            assertEquals(PlaybackTarget(100, 7, 300_000, null), controller.played.single())
        }

    @Test
    fun `with nothing remembered the intent is all there is to go on`() = runTest(main.dispatcher) {
        viewModel.start(100, 4, explicit = false)
        advanceUntilIdle()

        assertEquals(PlaybackTarget(100, 4, 0, null), controller.played.single())
    }

    @Test
    fun `an episode chosen by hand outranks what the row remembers`() = runTest(main.dispatcher) {
        watchStates.seed(WatchState(100, 7, 300_000, 1_440_000, translationId = 11, kodikSeason = 1, updatedAt = now))

        viewModel.start(100, 4, explicit = true)
        advanceUntilIdle()

        assertEquals(4, controller.played.single().episode)
    }

    @Test
    fun `a screen opened with no episode named adopts the session that is playing`() =
        runTest(main.dispatcher) {
            playingOn(episode = 7, positionMs = 420_000)
            val attached = controller.attaches

            assertTrue("the session should have been adopted", viewModel.attachLive())
            advanceUntilIdle()

            assertTrue(controller.played.isEmpty())
            assertEquals(attached + 1, controller.attaches)
            // The catalogue fields the remote control draws from need the anime id, which the
            // notification's intent does not carry.
            assertEquals("Фрирен", viewModel.uiState.value.title)
            assertEquals(7, viewModel.uiState.value.episode)
            assertEquals(420_000L, viewModel.uiState.value.positionMs)
        }

    @Test
    fun `a screen opened with no episode named and nothing playing says there is nothing to show`() =
        runTest(main.dispatcher) {
            // The activity closes on this answer: a remote control with no session behind it is a
            // dead screen, and going back to the app is the only useful thing left.
            assertFalse("there is no session to adopt", viewModel.attachLive())
            advanceUntilIdle()

            assertTrue(controller.played.isEmpty())
            assertEquals("", viewModel.uiState.value.title)
            assertEquals(0, controller.attaches)
        }

    @Test
    fun `a session for another anime does not capture the screen coming back`() = runTest(main.dispatcher) {
        playingOn(episode = 7, animeId = 200)

        viewModel.start(100, 4, explicit = false)
        advanceUntilIdle()

        assertEquals(PlaybackTarget(100, 4, 0, null), controller.played.single())
    }

    @Test
    fun `asking for another episode by hand plays it, whatever the session reached`() =
        runTest(main.dispatcher) {
            viewModel.start(100, 4)
            advanceUntilIdle()
            playingOn(episode = 7)

            viewModel.start(100, 9, explicit = true)
            advanceUntilIdle()

            assertEquals(9, controller.played.last().episode)
        }

    @Test
    fun `asking by hand for the episode already playing changes nothing`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        playingOn(episode = 7)
        val started = controller.played.size

        viewModel.start(100, 7, explicit = true)
        advanceUntilIdle()

        assertEquals(started, controller.played.size)
        assertEquals(7, viewModel.uiState.value.episode)
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
        assertEquals(listOf(anilibria, studioBanda), viewModel.uiState.value.translations.map { it.translation })
        assertFalse(viewModel.uiState.value.loadingTranslations)
    }

    @Test
    fun `the sheet marks a track this viewer keeps choosing elsewhere`() = runTest(main.dispatcher) {
        // Nothing on the viewer's own list, so their watching history is what orders the sheet.
        prefs.preferredTranslations.value = emptyList()
        watchStates.seed(WatchState(1, 1, 0, 0, translationId = studioBanda.id, kodikSeason = 1, updatedAt = now))
        watchStates.seed(WatchState(2, 1, 0, 0, translationId = studioBanda.id, kodikSeason = 1, updatedAt = now))
        viewModel.start(100, 1)
        advanceUntilIdle()

        viewModel.openTranslations()
        advanceUntilIdle()

        assertEquals(listOf(studioBanda, anilibria), viewModel.uiState.value.translations.map { it.translation })
        assertEquals(listOf(true, false), viewModel.uiState.value.translations.map { it.oftenChosen })
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
        controller.playback.update { it.copy(airedEpisodes = 12, autoplayCountdownSec = 7) }
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
    fun `the remote lists the season with what is behind the viewer marked`() = runTest(main.dispatcher) {
        watchStates.seed(WatchState(100, 4, 720_000, 1_440_000, translationId = 11, kodikSeason = 1, updatedAt = now))

        viewModel.start(100, 4)
        advanceUntilIdle()

        val episodes = viewModel.uiState.value.episodes
        assertEquals(12, episodes.size)
        // Three episodes counted on Shikimori, the fourth half watched on this phone.
        assertEquals(listOf(1, 2, 3), episodes.filter { it.watched }.map { it.number })
        assertEquals(0.5f, episodes.first { it.number == 4 }.progress!!, 1e-3f)
        assertTrue(episodes.all { it.aired })
    }

    @Test
    fun `the remote does not list an episode that has not aired as playable`() = runTest(main.dispatcher) {
        library.put(
            LibraryEntry(
                anime.copy(status = AnimeStatus.ONGOING, episodes = 12, episodesAired = 5),
                UserRate(1, 100, ListStatus.WATCHING, 3, now),
                null,
            ),
        )

        viewModel.start(100, 4)
        advanceUntilIdle()

        val episodes = viewModel.uiState.value.episodes
        assertEquals(12, episodes.size)
        assertEquals((1..5).toList(), episodes.filter { it.aired }.map { it.number })
    }

    @Test
    fun `choosing an episode from the remote plays it in the track that is playing`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        controller.playback.update { it.copy(stream = stream(episode = 4, track = studioBanda)) }

        viewModel.playEpisode(9)
        advanceUntilIdle()

        val asked = controller.played.last()
        assertEquals(9, asked.episode)
        assertEquals(studioBanda, asked.translation)
        assertEquals(0L, asked.startPositionMs)
    }

    @Test
    fun `choosing the episode that is already playing changes nothing`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        val before = controller.played.size

        viewModel.playEpisode(4)
        advanceUntilIdle()

        assertEquals(before, controller.played.size)
    }

    @Test
    fun `an episode with aired episodes after it offers the next one`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        controller.playback.update { it.copy(airedEpisodes = 12) }
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.nextEpisodeAvailable)
    }

    @Test
    fun `the last aired episode offers no next one`() = runTest(main.dispatcher) {
        viewModel.start(100, 12)
        advanceUntilIdle()
        controller.playback.update { it.copy(airedEpisodes = 12) }
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.nextEpisodeAvailable)
    }

    @Test
    fun `an episode running out is announced whether or not another one follows`() = runTest(main.dispatcher) {
        viewModel.start(100, 12)
        advanceUntilIdle()
        controller.playback.update { it.copy(airedEpisodes = 12, nextEpisodeDue = true) }
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.episodeEnding)
        assertFalse(viewModel.uiState.value.nextEpisodeAvailable)
    }

    @Test
    fun `a finished show has no more episodes coming`() = runTest(main.dispatcher) {
        viewModel.start(100, 12)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.moreEpisodesComing)
    }

    @Test
    fun `an ongoing show has`() = runTest(main.dispatcher) {
        library.put(
            LibraryEntry(
                anime.copy(status = AnimeStatus.ONGOING, episodesAired = 7),
                UserRate(1, 100, ListStatus.WATCHING, 3, now),
                null,
            ),
        )

        viewModel.start(100, 7)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.moreEpisodesComing)
    }

    @Test
    fun `the screen knows when the next episode is due to air`() = runTest(main.dispatcher) {
        val airing = Instant.parse("2026-09-20T10:00:00Z")
        library.put(
            LibraryEntry(
                anime.copy(status = AnimeStatus.ONGOING, episodesAired = 7, nextEpisodeAt = airing),
                UserRate(1, 100, ListStatus.WATCHING, 3, now),
                null,
            ),
        )

        viewModel.start(100, 7)
        advanceUntilIdle()

        assertEquals(airing, viewModel.uiState.value.nextEpisodeAt)
        assertEquals(7, viewModel.uiState.value.availableEpisodes)
    }

    @Test
    fun `a quality picked with nothing remembering it is for this episode only`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()

        viewModel.pickQuality(Quality.P480)
        advanceUntilIdle()

        assertEquals(Quality.P480, controller.qualities.single())
        assertNull(prefs.defaultQuality.value)
        assertFalse(viewModel.uiState.value.rememberQuality)
    }

    @Test
    fun `asking for the quality to be remembered writes the one that is playing`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        controller.playback.update { it.copy(quality = Quality.P720) }
        advanceUntilIdle()

        viewModel.setRememberQuality(true)
        advanceUntilIdle()

        assertEquals(Quality.P720, prefs.defaultQuality.value)
        assertTrue(viewModel.uiState.value.rememberQuality)
    }

    @Test
    fun `while it is remembered every pick replaces it`() = runTest(main.dispatcher) {
        prefs.defaultQuality.value = Quality.P720
        viewModel.start(100, 4)
        advanceUntilIdle()

        viewModel.pickQuality(Quality.P480)
        advanceUntilIdle()

        assertEquals(Quality.P480, prefs.defaultQuality.value)
    }

    @Test
    fun `no longer remembering it puts the source back in charge`() = runTest(main.dispatcher) {
        prefs.defaultQuality.value = Quality.P480
        viewModel.start(100, 4)
        advanceUntilIdle()

        viewModel.setRememberQuality(false)
        advanceUntilIdle()

        assertNull(prefs.defaultQuality.value)
        assertFalse(viewModel.uiState.value.rememberQuality)
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

    @Test
    fun `a screen that stops while playing pauses and writes the position down`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        controller.playback.update { it.copy(isPlaying = true) }

        viewModel.pause()

        assertEquals(1, controller.toggles)
        assertEquals(1, controller.reports)
        // Still loaded: coming back and pressing play resumes the episode where it stopped.
        assertEquals(0, controller.releases)
    }

    @Test
    fun `a screen that stops on a paused episode only writes the position down`() = runTest(main.dispatcher) {
        viewModel.start(100, 4)
        advanceUntilIdle()
        controller.playback.update { it.copy(isPlaying = false) }

        viewModel.pause()

        // Toggling here would start playing an episode the viewer had deliberately stopped.
        assertEquals(0, controller.toggles)
        assertEquals(1, controller.reports)
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
    fun `the screen knows whether leaving the app should fold it into a window`() = runTest(main.dispatcher) {
        viewModel.start(animeId = 100, episode = 4)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.pipOnLeave)

        prefs.pipOnLeave.value = false
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.pipOnLeave)
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
