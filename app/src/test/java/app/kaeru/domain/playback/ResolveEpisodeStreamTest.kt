package app.kaeru.domain.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.kaeru.data.library.AppPreferences
import app.kaeru.domain.error.AccountSessionChanged
import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.source.EpisodeSourceProvider
import app.kaeru.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ResolveEpisodeStreamTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val storeScope = TestScope(dispatcher)
    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val clock = MutableClock(now)
    private val watchStates = FakeWatchStateRepository()
    private val source = FakeEpisodeSource()
    private lateinit var store: DataStore<Preferences>
    private lateinit var prefs: AppPreferences
    private lateinit var resolve: ResolveEpisodeStream

    private val anilibria = Translation(11, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12)
    private val studioBanda = Translation(22, "Студийная банда", TranslationKind.VOICE, episodesCount = 24)
    private val subtitles = Translation(33, "Субтитры", TranslationKind.SUBTITLES, episodesCount = 24)

    @Before
    fun setUp() {
        store = PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "prefs.preferences_pb") }
        prefs = AppPreferences(store)
        resolve = ResolveEpisodeStream(source, watchStates, prefs, clock)
        source.translations = Result.success(listOf(studioBanda, anilibria, subtitles))
    }

    @After
    fun tearDown() = storeScope.cancel()

    private class FakeEpisodeSource : EpisodeSourceProvider {
        var translations: Result<List<Translation>> = Result.success(emptyList())
        var stream: (Int, Int, Translation?) -> Result<EpisodeStream> = { animeId, episode, translation ->
            Result.success(
                EpisodeStream(
                    animeId = animeId,
                    episode = episode,
                    translation = translation ?: Translation(0, "По умолчанию", TranslationKind.VOICE, null),
                    urls = mapOf(Quality.P720 to "https://cdn/720.m3u8"),
                    resolvedAt = Instant.parse("2026-09-13T10:00:00Z"),
                ),
            )
        }
        val translationCalls = mutableListOf<Int>()
        val resolveCalls = mutableListOf<Triple<Int, Int, Translation?>>()

        override suspend fun translations(shikimoriId: Int): Result<List<Translation>> {
            translationCalls += shikimoriId
            return translations
        }

        override suspend fun resolve(shikimoriId: Int, episode: Int, translation: Translation?): Result<EpisodeStream> {
            resolveCalls += Triple(shikimoriId, episode, translation)
            return stream(shikimoriId, episode, translation)
        }
    }

    private fun row(
        episode: Int,
        positionMs: Long = 0,
        durationMs: Long = 0,
        translationId: Int? = null,
        kodikSeason: Int? = null,
    ) = WatchState(100, episode, positionMs, durationMs, translationId, kodikSeason, now.minusSeconds(3600))

    @Test
    fun `the track this anime was last played with is what the source is asked for`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 3, translationId = studioBanda.id))

        resolve(animeId = 100, episode = 4).getOrThrow()

        assertEquals(studioBanda.id, source.resolveCalls.single().third?.id)
    }

    @Test
    fun `a hand picked track beats the ranking and spares the catalogue call`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 3, translationId = studioBanda.id))

        val stream = resolve(animeId = 100, episode = 4, translationOverride = subtitles).getOrThrow()

        assertEquals(subtitles.id, source.resolveCalls.single().third?.id)
        assertEquals(subtitles.id, stream.translation.id)
        assertEquals(emptyList<Int>(), source.translationCalls)
        assertEquals(subtitles.id, watchStates.saved.single().translationId)
    }

    @Test
    fun `with nothing remembered and nothing watched the built-in studios win`() = runTest(dispatcher) {
        resolve(animeId = 100, episode = 1).getOrThrow()

        assertEquals(anilibria.id, source.resolveCalls.single().third?.id)
    }

    @Test
    fun `an anime nobody has started opens in the track this viewer picks most`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 1, translationId = studioBanda.id).copy(animeId = 1))
        watchStates.seed(row(episode = 1, translationId = studioBanda.id).copy(animeId = 2))

        resolve(animeId = 100, episode = 1).getOrThrow()

        assertEquals(studioBanda.id, source.resolveCalls.single().third?.id)
    }

    @Test
    fun `the track this anime remembers still beats the one the viewer uses everywhere else`() =
        runTest(dispatcher) {
            watchStates.seed(row(episode = 1, translationId = studioBanda.id).copy(animeId = 1))
            watchStates.seed(row(episode = 1, translationId = studioBanda.id).copy(animeId = 2))
            watchStates.seed(row(episode = 3, translationId = anilibria.id))

            resolve(animeId = 100, episode = 4).getOrThrow()

            assertEquals(anilibria.id, source.resolveCalls.single().third?.id)
        }

    @Test
    fun `a first play writes a fresh row at the start of the episode`() = runTest(dispatcher) {
        source.stream = { animeId, episode, translation ->
            Result.success(
                EpisodeStream(animeId, episode, translation!!.copy(season = 2), mapOf(Quality.P480 to "u"), now),
            )
        }

        resolve(animeId = 100, episode = 3).getOrThrow()

        assertEquals(
            WatchState(100, 3, positionMs = 0, durationMs = 0, translationId = anilibria.id, kodikSeason = 2, updatedAt = now),
            watchStates.saved.single(),
        )
    }

    @Test
    fun `resuming the same episode keeps the position already watched`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 3, positionMs = 500_000, durationMs = 1_400_000, translationId = anilibria.id))
        clock.now = now.plusSeconds(60)

        resolve(animeId = 100, episode = 3).getOrThrow()

        assertEquals(
            WatchState(100, 3, 500_000, 1_400_000, anilibria.id, kodikSeason = 1, updatedAt = now.plusSeconds(60)),
            watchStates.saved.single(),
        )
    }

    @Test
    fun `starting another episode rewinds the remembered position`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 3, positionMs = 500_000, durationMs = 1_400_000, translationId = anilibria.id))

        resolve(animeId = 100, episode = 4).getOrThrow()

        val saved = watchStates.saved.single()
        assertEquals(4, saved.episode)
        assertEquals(0L, saved.positionMs)
        assertEquals(0L, saved.durationMs)
        assertEquals(anilibria.id, saved.translationId)
    }

    @Test
    fun `the remembered kodik season is what the source is asked to play`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 3, translationId = anilibria.id, kodikSeason = 2))

        resolve(animeId = 100, episode = 4).getOrThrow()

        assertEquals(2, source.resolveCalls.single().third?.season)
    }

    @Test
    fun `a source that cannot resolve leaves the remembered state untouched`() = runTest(dispatcher) {
        val failure = EpisodeNotAvailable(100, 4)
        source.stream = { _, _, _ -> Result.failure(failure) }

        val result = resolve(animeId = 100, episode = 4)

        assertSame(failure, result.exceptionOrNull())
        assertEquals(emptyList<WatchState>(), watchStates.started)
    }

    @Test
    fun `a catalogue failure is the resolve failure and nothing is asked to play`() = runTest(dispatcher) {
        val failure = NetworkUnavailable(IOException("offline"))
        source.translations = Result.failure(failure)

        val result = resolve(animeId = 100, episode = 4)

        assertSame(failure, result.exceptionOrNull())
        assertEquals(emptyList<Triple<Int, Int, Translation?>>(), source.resolveCalls)
    }

    @Test
    fun `losing the account while remembering still hands back a playable stream`() = runTest(dispatcher) {
        watchStates.failSaveWith = AccountSessionChanged("signed out")

        val stream = resolve(animeId = 100, episode = 4).getOrThrow()

        assertEquals(anilibria.id, stream.translation.id)
        assertTrue(watchStates.saved.isEmpty())
    }

    @Test
    fun `a source with nothing on offer is still asked, so its own failure reaches the caller`() =
        runTest(dispatcher) {
            source.translations = Result.success(emptyList())

            resolve(animeId = 100, episode = 4).getOrThrow()

            assertNull(source.resolveCalls.single().third)
        }

    @Test
    fun `the selection sheet gets the tracks ranked, carrying the remembered season`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 3, translationId = studioBanda.id, kodikSeason = 2))

        val listed = resolve.translations(animeId = 100).getOrThrow()

        assertEquals(listOf(studioBanda.id, anilibria.id, subtitles.id), listed.map { it.translation.id })
        assertEquals(listOf(2, 2, 2), listed.map { it.translation.season })
    }

    @Test
    fun `the sheet marks the tracks this viewer keeps choosing, ranked by how often`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 1, translationId = studioBanda.id).copy(animeId = 1))
        watchStates.seed(row(episode = 1, translationId = studioBanda.id).copy(animeId = 2))
        watchStates.seed(row(episode = 1, translationId = subtitles.id).copy(animeId = 3))

        val listed = resolve.translations(animeId = 100).getOrThrow()

        // Watched once, subtitles still outrank a studio from the built-in list nobody has played;
        // once is not a habit, so only the track two anime carry is marked.
        assertEquals(listOf(studioBanda.id, subtitles.id, anilibria.id), listed.map { it.translation.id })
        assertEquals(listOf(true, false, false), listed.map { it.oftenChosen })
    }

    @Test
    fun `an anime with a track of its own marks nothing, since that track is already marked chosen`() =
        runTest(dispatcher) {
            watchStates.seed(row(episode = 1, translationId = studioBanda.id).copy(animeId = 1))
            watchStates.seed(row(episode = 1, translationId = studioBanda.id).copy(animeId = 2))
            watchStates.seed(row(episode = 3, translationId = anilibria.id))

            val listed = resolve.translations(animeId = 100).getOrThrow()

            assertEquals(listOf(anilibria.id, studioBanda.id, subtitles.id), listed.map { it.translation.id })
            assertEquals(listOf(false, false, false), listed.map { it.oftenChosen })
        }

    @Test
    fun `a catalogue failure reaches the selection sheet unchanged`() = runTest(dispatcher) {
        val failure = NetworkUnavailable(IOException("offline"))
        source.translations = Result.failure(failure)

        assertSame(failure, resolve.translations(animeId = 100).exceptionOrNull())
    }
}
