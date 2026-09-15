package app.kaeru.domain.playback

import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.test.MutableClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The one resolved episode kept between the home screen rendering and the viewer pressing play. */
class StreamPrefetchCacheTest {

    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private val clock = MutableClock(now)
    private val cache = StreamPrefetchCache(clock)

    private val anilibria = Translation(11, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12)
    private val studioBanda = Translation(22, "Студийная банда", TranslationKind.VOICE, episodesCount = 12)

    private fun stream(animeId: Int = 100, episode: Int = 4, track: Translation = anilibria) = EpisodeStream(
        animeId = animeId,
        episode = episode,
        translation = track,
        urls = mapOf(Quality.P720 to "https://cdn/$animeId/$episode/${track.id}"),
        resolvedAt = clock.instant(),
    )

    @Test
    fun `an episode put in is the episode that comes back`() {
        val kept = stream()
        cache.put(kept)

        assertSame(kept, cache.take(animeId = 100, episode = 4, translationId = 11))
    }

    @Test
    fun `taking it leaves nothing behind`() {
        cache.put(stream())

        cache.take(100, 4, 11)

        assertNull(cache.take(100, 4, 11))
    }

    @Test
    fun `another episode of the same anime is not it`() {
        cache.put(stream(episode = 4))

        assertNull(cache.take(100, 5, 11))
    }

    @Test
    fun `another anime is not it`() {
        cache.put(stream(animeId = 100))

        assertNull(cache.take(200, 4, 11))
    }

    @Test
    fun `a different voice is not it, however right the episode is`() {
        cache.put(stream(track = anilibria))

        assertNull(cache.take(100, 4, translationId = studioBanda.id))
    }

    @Test
    fun `links prepared for a voice the viewer has abandoned are thrown away, not just refused`() {
        cache.put(stream(track = anilibria))

        cache.take(100, 4, translationId = studioBanda.id)

        assertFalse("the stale entry should be gone, not waiting for its TTL", cache.holds(100, 4))
    }

    @Test
    fun `an episode with nothing remembered about its voice is not served from here`() {
        cache.put(stream())

        assertNull(cache.take(100, 4, translationId = null))
    }

    @Test
    fun `links older than their lifetime are not handed out`() {
        cache.put(stream())
        clock.advance(StreamPrefetchCache.TTL.plusSeconds(1))

        assertNull(cache.take(100, 4, 11))
        assertFalse(cache.holds(100, 4))
    }

    @Test
    fun `links inside their lifetime still are`() {
        cache.put(stream())
        clock.advance(StreamPrefetchCache.TTL.minusSeconds(1))

        assertEquals(4, cache.take(100, 4, 11)?.episode)
    }

    @Test
    fun `only one episode is kept, and it is the last one put in`() {
        cache.put(stream(episode = 4))
        cache.put(stream(episode = 5))

        assertNull(cache.take(100, 4, 11))
        assertEquals(5, cache.take(100, 5, 11)?.episode)
    }

    @Test
    fun `it says whether it already holds an episode, without taking it`() {
        assertFalse(cache.holds(100, 4))

        cache.put(stream())

        assertTrue(cache.holds(100, 4))
        assertFalse(cache.holds(100, 5))
        assertEquals(4, cache.take(100, 4, 11)?.episode)
    }
}
