package app.kaeru.data.download

import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.FakePlaybackPreferences
import app.kaeru.domain.playback.FakeWatchStateRepository
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.StreamPrefetchCache
import app.kaeru.domain.source.EpisodeSourceProvider
import app.kaeru.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * Kodik's signatures live for hours and an episode can take longer than that. What happens when
 * one expires mid-download is the difference between a download that resumes and one that has to
 * start again from nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class DownloadRefresherTest {

    private val dispatcher = StandardTestDispatcher()
    private val now: Instant = Instant.parse("2026-09-15T10:00:00Z")
    private val clock = MutableClock(now)

    private val anilibria = Translation(609, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12, season = 2)
    private val key = DownloadKey(ANIME, 7, anilibria.id, Quality.P720)

    private val engine = FakeDownloadsSource()
    private val commands = FakeDownloadCommands(engine)
    private val episodes = FakeEpisodeSource()
    private lateinit var refresher: DownloadRefresher

    @Before
    fun setUp() {
        episodes.translations = Result.success(listOf(anilibria))
        refresher = DownloadRefresher(
            commands = commands,
            resolve = ResolveEpisodeStream(
                episodes,
                FakeWatchStateRepository(),
                FakePlaybackPreferences(),
                clock,
                StreamPrefetchCache(clock),
            ),
            clock = clock,
        )
    }

    @Test
    fun `a signature the CDN refuses is resolved again under the same id`() = runTest(dispatcher) {
        episodes.urls = mapOf(Quality.P720 to "https://cdn/720.m3u8?sign=fresh")

        assertTrue(refresher.refresh(failed(), forbidden(403)))

        val again = commands.added.single()
        assertEquals(key.id, again.id)
        assertEquals("https://cdn/720.m3u8?sign=fresh", again.uri.toString())
        assertEquals(MimeTypes.APPLICATION_M3U8, again.mimeType)
        // The blob survives, so the next failure still knows which voice to ask for.
        assertEquals(anilibria.id, DownloadPayload.decode(again.data)?.translationId)
    }

    @Test
    fun `a gone link is refreshed too`() = runTest(dispatcher) {
        assertTrue(refresher.refresh(failed(), forbidden(410)))
    }

    @Test
    fun `a link that expired part way through is refreshed whatever the failure says`() = runTest(dispatcher) {
        // Segments already on the device are the proof that the link worked; whatever broke it
        // since, asking for a fresh one costs one request and resumes rather than restarts.
        assertTrue(refresher.refresh(failed(bytes = 120_000_000), IOException("connection reset")))
    }

    @Test
    fun `a failure before a single byte that is not a refused signature is left alone`() = runTest(dispatcher) {
        assertFalse(refresher.refresh(failed(bytes = 0), IOException("no route to host")))
        assertTrue(commands.added.isEmpty())
    }

    @Test
    fun `a status that is not about the signature is left alone`() = runTest(dispatcher) {
        assertFalse(refresher.refresh(failed(bytes = 0), forbidden(404)))
    }

    @Test
    fun `the refused status is found however deeply it is wrapped`() = runTest(dispatcher) {
        val wrapped = IOException("download failed", IllegalStateException("inner", forbidden(403)))

        assertTrue(refresher.refresh(failed(), wrapped))
    }

    @Test
    fun `three refreshes an hour is the budget`() = runTest(dispatcher) {
        repeat(3) { assertTrue(refresher.refresh(failed(), forbidden(403))) }

        assertFalse(refresher.refresh(failed(), forbidden(403)))
        assertEquals(3, commands.added.size)
    }

    @Test
    fun `the budget comes back once the hour has passed`() = runTest(dispatcher) {
        repeat(3) { refresher.refresh(failed(), forbidden(403)) }
        assertFalse(refresher.refresh(failed(), forbidden(403)))

        clock.advance(Duration.ofHours(1).plusSeconds(1))

        assertTrue(refresher.refresh(failed(), forbidden(403)))
    }

    @Test
    fun `the budget is per download, not per app`() = runTest(dispatcher) {
        repeat(3) { refresher.refresh(failed(), forbidden(403)) }

        val other = key.copy(episode = 8)
        assertTrue(refresher.refresh(failed(key = other), forbidden(403)))
    }

    @Test
    fun `the same voice and season are asked for again`() = runTest(dispatcher) {
        refresher.refresh(failed(), forbidden(403))

        val asked = episodes.resolveCalls.single()
        assertEquals(ANIME to 7, asked.first to asked.second)
        assertEquals(anilibria.id, asked.third?.id)
        assertEquals(anilibria.season, asked.third?.season)
    }

    @Test
    fun `a height the source no longer offers is not refreshed`() = runTest(dispatcher) {
        // Re-adding at another height under this id would put 480p segments behind a name that
        // says 720p, and the player would hand the viewer something they did not ask for.
        episodes.urls = mapOf(Quality.P480 to "https://cdn/480.m3u8")

        assertFalse(refresher.refresh(failed(), forbidden(403)))
        assertTrue(commands.added.isEmpty())
    }

    @Test
    fun `a resolve that fails leaves the download failed`() = runTest(dispatcher) {
        episodes.stream = { Result.failure(EpisodeNotAvailable(ANIME, 7)) }

        assertFalse(refresher.refresh(failed(), forbidden(403)))
        assertTrue(commands.added.isEmpty())
    }

    @Test
    fun `a download this app did not write is not refreshed`() = runTest(dispatcher) {
        val alien = downloadOf(
            DownloadRequest.Builder("someone else's id", "https://cdn/x".toUri()).build(),
            Download.STATE_FAILED,
            failureReason = Download.FAILURE_REASON_UNKNOWN,
        )

        assertFalse(refresher.refresh(alien, forbidden(403)))
    }

    @Test
    fun `a download that has not failed is not refreshed`() = runTest(dispatcher) {
        val running = download(key, Download.STATE_DOWNLOADING, bytes = 100)

        assertFalse(refresher.refresh(running, forbidden(403)))
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private fun failed(key: DownloadKey = this.key, bytes: Long = 0) =
        download(key, Download.STATE_FAILED, bytes, Download.FAILURE_REASON_UNKNOWN)

    private fun download(
        key: DownloadKey,
        state: Int,
        bytes: Long = 0,
        failureReason: Int = Download.FAILURE_REASON_NONE,
    ) = downloadOf(
        DownloadRequest.Builder(key.id, "https://cdn/720.m3u8?sign=stale".toUri())
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setData(DownloadPayload.of(key, "Фрирен", anilibria).encode())
            .build(),
        state,
        bytes,
        failureReason = failureReason,
    )

    private fun forbidden(code: Int) = HttpDataSource.InvalidResponseCodeException(
        code,
        "Forbidden",
        null,
        emptyMap(),
        DataSpec("https://cdn/720.m3u8?sign=stale".toUri()),
        ByteArray(0),
    )

    private class FakeEpisodeSource : EpisodeSourceProvider {
        var translations: Result<List<Translation>> = Result.success(emptyList())
        var urls: Map<Quality, String> = mapOf(Quality.P720 to "https://cdn/720.m3u8?sign=fresh")
        var stream: (() -> Result<EpisodeStream>)? = null
        val resolveCalls = mutableListOf<Triple<Int, Int, Translation?>>()

        override suspend fun translations(shikimoriId: Int) = translations

        override suspend fun resolve(
            shikimoriId: Int,
            episode: Int,
            translation: Translation?,
        ): Result<EpisodeStream> {
            resolveCalls += Triple(shikimoriId, episode, translation)
            stream?.let { return it() }
            return Result.success(
                EpisodeStream(
                    animeId = shikimoriId,
                    episode = episode,
                    translation = translation ?: Translation(0, "", TranslationKind.VOICE, null),
                    urls = urls,
                    resolvedAt = Instant.parse("2026-09-15T10:00:00Z"),
                ),
            )
        }
    }

    private companion object {
        const val ANIME = 52991
    }
}
