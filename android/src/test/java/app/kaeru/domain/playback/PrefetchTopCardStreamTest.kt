package app.kaeru.domain.playback

import app.kaeru.domain.download.FakeDownloadRepository
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.source.EpisodeSourceProvider
import app.kaeru.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * Resolving the card at the top of the home screen while the viewer is still reading it, so the
 * press that follows starts a video instead of a page fetch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefetchTopCardStreamTest {

    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val clock = MutableClock(now)
    private val anilibria = Translation(11, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12)

    private val watchStates = FakeWatchStateRepository()
    private val prefs = FakePlaybackPreferences()
    private val cache = StreamPrefetchCache(clock)
    private val source = RecordingSource()
    private val resolve = ResolveEpisodeStream(source, watchStates, prefs, clock, cache)
    private val downloads = FakeDownloadRepository()
    private val prefetch = PrefetchTopCardStream(resolve, cache, watchStates, downloads)

    private class RecordingSource : EpisodeSourceProvider {
        var failure: Throwable? = null
        val resolves = mutableListOf<Pair<Int, Int>>()
        val translationCalls = mutableListOf<Int>()

        override suspend fun translations(shikimoriId: Int): Result<List<Translation>> {
            translationCalls += shikimoriId
            return Result.success(listOf(Translation(11, "AniLibria.TV", TranslationKind.VOICE, 12)))
        }

        override suspend fun resolve(shikimoriId: Int, episode: Int, translation: Translation?): Result<EpisodeStream> {
            resolves += shikimoriId to episode
            failure?.let { return Result.failure(it) }
            return Result.success(
                EpisodeStream(
                    animeId = shikimoriId,
                    episode = episode,
                    translation = translation ?: Translation(11, "AniLibria.TV", TranslationKind.VOICE, 12),
                    urls = mapOf(Quality.P720 to "https://cdn/$shikimoriId/$episode"),
                    resolvedAt = Instant.parse("2026-09-13T10:00:00Z"),
                ),
            )
        }
    }

    private fun remembering(episode: Int) = watchStates.seed(
        WatchState(100, episode, 90_000, 1_440_000, translationId = anilibria.id, kodikSeason = 1, updatedAt = now),
    )

    @Test
    fun `the top card's episode is resolved and kept`() = runTest(dispatcher) {
        remembering(episode = 4)

        prefetch(animeId = 100, episode = 4)

        assertEquals(listOf(100 to 4), source.resolves)
        assertNotNull(cache.take(100, 4, anilibria.id))
    }

    @Test
    fun `an episode already prepared is not fetched a second time`() = runTest(dispatcher) {
        remembering(episode = 4)

        prefetch(animeId = 100, episode = 4)
        prefetch(animeId = 100, episode = 4)

        assertEquals(1, source.resolves.size)
    }

    @Test
    fun `preparing an episode does not move this anime's memory`() = runTest(dispatcher) {
        remembering(episode = 3)

        prefetch(animeId = 100, episode = 4)

        assertTrue(watchStates.saved.isEmpty())
    }

    @Test
    fun `an episode already on the device is not prepared at all`() = runTest(dispatcher) {
        // The press will play it from the device without resolving anything, so a prepared link
        // is a Kodik round trip nobody takes — and with no network it is a failure quietly
        // logged about an episode that is about to play perfectly well.
        remembering(episode = 4)
        downloads.downloaded(animeId = 100, episode = 4, translation = anilibria, url = "https://cdn/100/4/720.m3u8")

        prefetch(animeId = 100, episode = 4)

        assertTrue(source.resolves.isEmpty())
        assertNull(cache.take(100, 4, anilibria.id))
    }

    @Test
    fun `an anime with no remembered voice is not prepared at all`() = runTest(dispatcher) {
        // Nothing is remembered, so nothing could claim the links: the take is keyed on the voice
        // the press is about to ask for, and preparing writes no memory to answer it with.
        prefetch(animeId = 100, episode = 1)

        assertTrue(source.resolves.isEmpty())
        assertNull(cache.take(100, 1, anilibria.id))
    }

    @Test
    fun `a source that will not answer costs nothing and says nothing`() = runTest(dispatcher) {
        remembering(episode = 4)
        source.failure = NetworkUnavailable(IOException("offline"))

        prefetch(animeId = 100, episode = 4)

        assertNull(cache.take(100, 4, anilibria.id))
    }
}
