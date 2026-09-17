package app.kaeru.domain.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.kaeru.data.library.AppPreferences
import app.kaeru.domain.error.AccountSessionChanged
import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.error.EpisodeUnavailableReason
import app.kaeru.domain.error.SourceUnavailable
import app.kaeru.domain.error.SourceUnavailableReason
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
import org.junit.Assert.assertFalse
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
    private val prefetch = StreamPrefetchCache(clock)

    private val anilibria = Translation(11, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12)
    private val studioBanda = Translation(22, "Студийная банда", TranslationKind.VOICE, episodesCount = 24)
    private val subtitles = Translation(33, "Субтитры", TranslationKind.SUBTITLES, episodesCount = 24)

    @Before
    fun setUp() {
        store = PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "prefs.preferences_pb") }
        prefs = AppPreferences(store)
        resolve = ResolveEpisodeStream(source, watchStates, prefs, clock, prefetch)
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

        /** What this source has already seen listed, per track: the pre-check reads it, never the network. */
        val listed = mutableMapOf<Int, Set<Int>>()
        val forgotten = mutableListOf<Int>()

        override suspend fun translations(shikimoriId: Int): Result<List<Translation>> {
            translationCalls += shikimoriId
            return translations
        }

        override suspend fun resolve(shikimoriId: Int, episode: Int, translation: Translation?): Result<EpisodeStream> {
            resolveCalls += Triple(shikimoriId, episode, translation)
            return stream(shikimoriId, episode, translation)
        }

        override suspend fun listedEpisodes(shikimoriId: Int, translationId: Int): Set<Int>? = listed[translationId]

        override suspend fun forget(shikimoriId: Int) {
            forgotten += shikimoriId
        }
    }

    /** A source whose tracks in [lacking] do not carry the episode asked for; every other track plays it. */
    private fun FakeEpisodeSource.lacking(vararg lacking: Int) {
        stream = { animeId, episode, translation ->
            if (translation != null && translation.id in lacking) {
                Result.failure(EpisodeNotAvailable(animeId, episode, EpisodeUnavailableReason.NOT_IN_TRANSLATION))
            } else {
                Result.success(
                    EpisodeStream(
                        animeId = animeId,
                        episode = episode,
                        translation = translation ?: Translation(0, "По умолчанию", TranslationKind.VOICE, null),
                        urls = mapOf(Quality.P720 to "https://cdn/720.m3u8"),
                        resolvedAt = now,
                    ),
                )
            }
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

        val stream = resolve(animeId = 100, episode = 4, translationOverride = subtitles).getOrThrow().stream

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
            WatchState(
                100, 3, positionMs = 0, durationMs = 0, translationId = anilibria.id, kodikSeason = 2,
                updatedAt = now, translationTitle = anilibria.title,
            ),
            watchStates.saved.single(),
        )
    }

    @Test
    fun `resuming the same episode keeps the position already watched`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 3, positionMs = 500_000, durationMs = 1_400_000, translationId = anilibria.id))
        clock.now = now.plusSeconds(60)

        resolve(animeId = 100, episode = 3).getOrThrow()

        assertEquals(
            WatchState(
                100, 3, 500_000, 1_400_000, anilibria.id, kodikSeason = 1,
                updatedAt = now.plusSeconds(60), translationTitle = anilibria.title,
            ),
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
        val failure = EpisodeNotAvailable(100, 4, EpisodeUnavailableReason.TITLE_NOT_ON_SOURCE)
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

        val stream = resolve(animeId = 100, episode = 4).getOrThrow().stream

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

    // --- the prefetched stream ---------------------------------------------------------------

    @Test
    fun `an episode already resolved for the home screen is not resolved again`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 4, translationId = anilibria.id))
        val prepared = EpisodeStream(100, 4, anilibria, mapOf(Quality.P720 to "https://cdn/prepared"), now)
        prefetch.put(Resolution(prepared))

        val stream = resolve(animeId = 100, episode = 4).getOrThrow().stream

        assertSame(prepared, stream)
        assertTrue(source.resolveCalls.isEmpty())
        assertTrue(source.translationCalls.isEmpty())
    }

    @Test
    fun `a prefetched stream in a voice the viewer has since changed is ignored`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 4, translationId = studioBanda.id))
        prefetch.put(Resolution(EpisodeStream(100, 4, anilibria, mapOf(Quality.P720 to "https://cdn/prepared"), now)))

        val stream = resolve(animeId = 100, episode = 4).getOrThrow().stream

        assertEquals(studioBanda.id, stream.translation.id)
        assertEquals(1, source.resolveCalls.size)
    }

    @Test
    fun `a prefetched stream still becomes this anime's memory when it is played`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 3, positionMs = 90_000, durationMs = 1_440_000, translationId = anilibria.id))
        prefetch.put(Resolution(EpisodeStream(100, 4, anilibria, mapOf(Quality.P720 to "https://cdn/prepared"), now)))

        resolve(animeId = 100, episode = 4).getOrThrow()

        val saved = watchStates.saved.last()
        assertEquals(4, saved.episode)
        assertEquals(anilibria.id, saved.translationId)
    }

    @Test
    fun `a resolve that is only preparing leaves the memory alone`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 3, positionMs = 90_000, durationMs = 1_440_000, translationId = anilibria.id))
        val before = watchStates.saved.size

        resolve(animeId = 100, episode = 4, persist = false).getOrThrow()

        assertEquals(before, watchStates.saved.size)
    }

    @Test
    fun `a movie the source lists no tracks for still shows the one that is playing`() = runTest(dispatcher) {
        source.translations = Result.success(emptyList())

        val listed = resolve.translations(animeId = 100, playing = anilibria).getOrThrow()

        assertEquals(listOf(anilibria), listed.map { it.translation })
        assertEquals(listOf(false), listed.map { it.oftenChosen })
    }

    @Test
    fun `nothing listed and nothing playing is still an empty list, not an invented track`() =
        runTest(dispatcher) {
            source.translations = Result.success(emptyList())

            assertEquals(emptyList<RankedTranslation>(), resolve.translations(animeId = 100).getOrThrow())
        }

    @Test
    fun `a track the source does list is not replaced by the one that is playing`() = runTest(dispatcher) {
        val listed = resolve.translations(animeId = 100, playing = subtitles).getOrThrow()

        assertEquals(3, listed.size)
    }

    // --- an episode the chosen track does not carry ---------------------------------------------

    @Test
    fun `when the remembered track lacks the episode the best other track plays, marked as standing in`() =
        runTest(dispatcher) {
            watchStates.seed(row(episode = 3, translationId = studioBanda.id))
            source.lacking(studioBanda.id)

            val resolution = resolve(animeId = 100, episode = 4).getOrThrow()

            assertEquals(anilibria.id, resolution.stream.translation.id)
            assertEquals(studioBanda.id, resolution.insteadOf?.id)
            assertEquals(listOf(studioBanda.id, anilibria.id), source.resolveCalls.map { it.third?.id })
        }

    @Test
    fun `a track standing in does not replace the one this anime remembers`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 3, translationId = studioBanda.id).copy(translationTitle = "Студийная банда"))
        source.lacking(studioBanda.id)

        resolve(animeId = 100, episode = 4).getOrThrow()

        val saved = watchStates.saved.single()
        assertEquals(4, saved.episode)
        assertEquals(studioBanda.id, saved.translationId)
        assertEquals("Студийная банда", saved.translationTitle)
    }

    @Test
    fun `with nothing remembered the track that stood in becomes the memory`() = runTest(dispatcher) {
        // Nothing is overwritten: the ranking's guess was never a choice of the viewer's.
        source.lacking(anilibria.id)

        val resolution = resolve(animeId = 100, episode = 4).getOrThrow()

        assertEquals(studioBanda.id, resolution.stream.translation.id)
        assertEquals(studioBanda.id, watchStates.saved.single().translationId)
    }

    @Test
    fun `an episode no track carries says so, after every track was asked`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 3, translationId = studioBanda.id))
        source.lacking(studioBanda.id, anilibria.id, subtitles.id)

        val error = resolve(animeId = 100, episode = 4).exceptionOrNull()

        assertTrue("expected EpisodeNotAvailable, got $error", error is EpisodeNotAvailable)
        assertEquals(EpisodeUnavailableReason.NOT_IN_ANY_TRANSLATION, (error as EpisodeNotAvailable).reason)
        assertEquals(4, error.episode)
        assertEquals(3, source.resolveCalls.size)
        assertTrue(watchStates.started.isEmpty())
    }

    @Test
    fun `a source that stops answering mid-walk is that failure, not a missing episode`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 3, translationId = studioBanda.id))
        val outage = SourceUnavailable(SourceUnavailableReason.REJECTED)
        source.stream = { animeId, episode, translation ->
            when (translation?.id) {
                studioBanda.id -> Result.failure(EpisodeNotAvailable(animeId, episode, EpisodeUnavailableReason.NOT_IN_TRANSLATION))
                else -> Result.failure(outage)
            }
        }

        val result = resolve(animeId = 100, episode = 4)

        assertSame(outage, result.exceptionOrNull())
        assertEquals(2, source.resolveCalls.size)
    }

    @Test
    fun `a title the source does not have at all is not walked`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 3, translationId = studioBanda.id))
        val missing = EpisodeNotAvailable(100, 4, EpisodeUnavailableReason.TITLE_NOT_ON_SOURCE)
        source.stream = { _, _, _ -> Result.failure(missing) }

        val result = resolve(animeId = 100, episode = 4)

        assertSame(missing, result.exceptionOrNull())
        assertEquals(1, source.resolveCalls.size)
    }

    @Test
    fun `a track whose count says it ends before the episode is never asked`() = runTest(dispatcher) {
        // AniLibria counts twelve; the twentieth cannot be there, so the walk goes straight past it.
        watchStates.seed(row(episode = 19, translationId = studioBanda.id))
        source.lacking(studioBanda.id)

        val resolution = resolve(animeId = 100, episode = 20).getOrThrow()

        assertEquals(subtitles.id, resolution.stream.translation.id)
        assertEquals(listOf(studioBanda.id, subtitles.id), source.resolveCalls.map { it.third?.id })
    }

    @Test
    fun `the count is not trusted for a season other than the first`() = runTest(dispatcher) {
        // The count comes off the initial player page, which never says which season it counts.
        watchStates.seed(row(episode = 19, translationId = studioBanda.id, kodikSeason = 2))
        source.lacking(studioBanda.id)

        val resolution = resolve(animeId = 100, episode = 20).getOrThrow()

        assertEquals(anilibria.id, resolution.stream.translation.id)
    }

    @Test
    fun `a track the source has already listed without the episode is skipped`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 3, translationId = studioBanda.id))
        source.listed[anilibria.id] = setOf(1, 2, 3)
        source.lacking(studioBanda.id)

        val resolution = resolve(animeId = 100, episode = 4).getOrThrow()

        assertEquals(subtitles.id, resolution.stream.translation.id)
        assertFalse(source.resolveCalls.any { it.third?.id == anilibria.id })
    }

    @Test
    fun `a track picked by hand is never swapped for another`() = runTest(dispatcher) {
        source.lacking(subtitles.id)

        val error = resolve(animeId = 100, episode = 4, translationOverride = subtitles).exceptionOrNull()

        assertEquals(EpisodeUnavailableReason.NOT_IN_TRANSLATION, (error as EpisodeNotAvailable).reason)
        assertEquals(1, source.resolveCalls.size)
        assertEquals(emptyList<Int>(), source.translationCalls)
    }

    @Test
    fun `a carried voice may be swapped when the caller allows it, the remembered one first`() = runTest(dispatcher) {
        // Autoplay carries the voice that was playing, which can differ from the remembered one
        // during a shared viewing; the walk starts from what this anime remembers.
        watchStates.seed(row(episode = 3, translationId = studioBanda.id))
        source.lacking(subtitles.id)

        val resolution = resolve(animeId = 100, episode = 4, translationOverride = subtitles, substitute = true).getOrThrow()

        assertEquals(studioBanda.id, resolution.stream.translation.id)
        assertEquals(subtitles.id, resolution.insteadOf?.id)
        assertEquals(listOf(subtitles.id, studioBanda.id), source.resolveCalls.map { it.third?.id })
    }

    @Test
    fun `the sheet says which tracks carry the episode, from what is already known`() = runTest(dispatcher) {
        // AniLibria counts twelve; the subtitles' own page has been read and stops at two.
        source.listed[subtitles.id] = setOf(1, 2)

        val listed = resolve.translations(animeId = 100, episode = 20).getOrThrow()

        assertEquals(listOf(anilibria.id, studioBanda.id, subtitles.id), listed.map { it.translation.id })
        assertEquals(listOf(false, true, false), listed.map { it.hasEpisode })
    }

    @Test
    fun `with no episode in question the sheet claims nothing`() = runTest(dispatcher) {
        val listed = resolve.translations(animeId = 100).getOrThrow()

        assertEquals(listOf(null, null, null), listed.map { it.hasEpisode })
    }

    @Test
    fun `the sheet does not read the count against a later season`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 19, translationId = studioBanda.id, kodikSeason = 2))

        val listed = resolve.translations(animeId = 100, episode = 20).getOrThrow()

        assertEquals(listOf(null, null, null), listed.map { it.hasEpisode })
    }

    @Test
    fun `forgetting the catalogue reaches the source`() = runTest(dispatcher) {
        resolve.forgetCatalogue(100)

        assertEquals(listOf(100), source.forgotten)
    }

    @Test
    fun `a stream prepared in a stand-in voice is taken by the voice that was asked for`() = runTest(dispatcher) {
        watchStates.seed(row(episode = 3, translationId = studioBanda.id))
        val prepared = EpisodeStream(100, 4, anilibria, mapOf(Quality.P720 to "https://cdn/prepared"), now)
        prefetch.put(Resolution(prepared, insteadOf = studioBanda))

        val resolution = resolve(animeId = 100, episode = 4).getOrThrow()

        assertSame(prepared, resolution.stream)
        assertEquals(studioBanda.id, resolution.insteadOf?.id)
        assertTrue(source.resolveCalls.isEmpty())
        assertEquals(studioBanda.id, watchStates.saved.single().translationId)
    }

    @Test
    fun `a stand-in prepared ahead is not handed to a hand pick of the voice it stood in for`() = runTest(dispatcher) {
        prefetch.put(Resolution(EpisodeStream(100, 4, anilibria, mapOf(Quality.P720 to "u"), now), insteadOf = studioBanda))

        val resolution = resolve(animeId = 100, episode = 4, translationOverride = studioBanda).getOrThrow()

        assertEquals(studioBanda.id, resolution.stream.translation.id)
        assertEquals(studioBanda.id, source.resolveCalls.single().third?.id)
    }
}
