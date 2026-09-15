package app.kaeru.data.download

import android.app.Application
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.core.app.ApplicationProvider
import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.playback.FakePlaybackPreferences
import app.kaeru.domain.playback.FakeWatchStateRepository
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.StreamPrefetchCache
import app.kaeru.domain.settings.FakeSettingsStore
import app.kaeru.domain.source.EpisodeSourceProvider
import app.kaeru.player.FakeLibraryRepository
import app.kaeru.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.time.Instant
import javax.inject.Provider

/** The wiring that has to work whether or not a screen is watching. */
@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class DownloadEngineTest {

    private val dispatcher = StandardTestDispatcher()
    private val now: Instant = Instant.parse("2026-09-15T10:00:00Z")
    private val clock = MutableClock(now)
    private val anilibria = Translation(609, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12)
    private val key = DownloadKey(ANIME, 7, anilibria.id, Quality.P720)

    private lateinit var app: Application
    private val source = FakeDownloadsSource()
    private val commands = FakeDownloadCommands(source)
    private val episodes = FakeEpisodeSource()
    private val outcomes = RecordingOutcomes()
    private lateinit var engine: DownloadEngine

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val watchStates = FakeWatchStateRepository()
        val resolve = ResolveEpisodeStream(
            episodes,
            watchStates,
            FakePlaybackPreferences(),
            clock,
            StreamPrefetchCache(clock),
        )
        engine = DownloadEngine(
            context = app,
            downloads = Media3DownloadRepository(
                source = source,
                commands = commands,
                resolve = resolve,
                settings = FakeSettingsStore(),
                library = Provider { FakeLibraryRepository() },
                watchStates = watchStates,
                clock = clock,
                io = dispatcher,
            ),
            refresher = DownloadRefresher(commands, resolve, clock),
            outcomes = outcomes,
            source = source,
            io = dispatcher,
        )
    }

    @Test
    fun `a failure the refresher takes on is not reported to the viewer`() = runTest(dispatcher) {
        engine.start(backgroundScope)
        runCurrent()

        source.put(download(Download.STATE_FAILED, Download.FAILURE_REASON_UNKNOWN), forbidden())
        runCurrent()

        assertTrue(outcomes.broken.isEmpty())
        assertEquals(key.id, commands.added.single().id)
    }

    @Test
    fun `a failure the refresher will not take is reported`() = runTest(dispatcher) {
        engine.start(backgroundScope)
        runCurrent()

        // Nothing downloaded and no refused signature: this is not an expired link.
        source.put(download(Download.STATE_FAILED, Download.FAILURE_REASON_UNKNOWN), IOException("no route"))
        runCurrent()

        assertEquals(listOf(key.id), outcomes.broken)
        assertTrue(commands.added.isEmpty())
    }

    @Test
    fun `a finished download is reported once`() = runTest(dispatcher) {
        engine.start(backgroundScope)
        runCurrent()

        source.put(download(Download.STATE_COMPLETED))
        runCurrent()

        assertEquals(listOf(key.id), outcomes.finished)
    }

    @Test
    fun `a download still running says nothing`() = runTest(dispatcher) {
        engine.start(backgroundScope)
        runCurrent()

        source.put(download(Download.STATE_DOWNLOADING))
        runCurrent()

        assertTrue(outcomes.finished.isEmpty())
        assertTrue(outcomes.broken.isEmpty())
    }

    @Test
    fun `an unfinished download from the last run brings the service back`() = runTest(dispatcher) {
        source.put(download(Download.STATE_QUEUED))

        engine.start(backgroundScope)
        runCurrent()

        val started = shadowOf(app).nextStartedService
        assertEquals(KaeruDownloadService::class.java.name, started?.component?.className)
    }

    @Test
    fun `nothing left to download starts no service`() = runTest(dispatcher) {
        source.put(download(Download.STATE_COMPLETED))

        engine.start(backgroundScope)
        runCurrent()

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `starting twice registers one listener`() = runTest(dispatcher) {
        engine.start(backgroundScope)
        engine.start(backgroundScope)
        runCurrent()

        assertEquals(1, source.listenerCount)
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private fun download(state: Int, failureReason: Int = Download.FAILURE_REASON_NONE) = downloadOf(
        DownloadRequest.Builder(key.id, "https://cdn/720.m3u8?sign=stale".toUri())
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setData(DownloadPayload.of(key, "Фрирен", anilibria).encode())
            .build(),
        state,
        failureReason = failureReason,
    )

    private fun forbidden() = HttpDataSource.InvalidResponseCodeException(
        403,
        "Forbidden",
        null,
        emptyMap(),
        DataSpec("https://cdn/720.m3u8?sign=stale".toUri()),
        ByteArray(0),
    )

    private class RecordingOutcomes : DownloadOutcomes {
        val finished = mutableListOf<String>()
        val broken = mutableListOf<String>()

        override fun completed(download: Download) {
            finished += download.request.id
        }

        override fun failed(download: Download) {
            broken += download.request.id
        }
    }

    private class FakeEpisodeSource : EpisodeSourceProvider {
        override suspend fun translations(shikimoriId: Int) = Result.success(emptyList<Translation>())

        override suspend fun resolve(
            shikimoriId: Int,
            episode: Int,
            translation: Translation?,
        ): Result<EpisodeStream> = Result.success(
            EpisodeStream(
                animeId = shikimoriId,
                episode = episode,
                translation = translation ?: Translation(0, "", TranslationKind.VOICE, null),
                urls = mapOf(Quality.P720 to "https://cdn/720.m3u8?sign=fresh"),
                resolvedAt = Instant.parse("2026-09-15T10:00:00Z"),
            ),
        )
    }

    private companion object {
        const val ANIME = 52991
    }
}
