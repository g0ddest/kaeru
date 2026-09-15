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
import app.kaeru.test.MutableClock
import androidx.media3.exoplayer.scheduler.Requirements
import app.kaeru.domain.download.DownloadPolicy
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
    private val failures = DownloadFailures()
    private val settings = FakeSettingsStore()
    private lateinit var engine: DownloadEngine

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val resolve = ResolveEpisodeStream(
            episodes,
            FakeWatchStateRepository(),
            FakePlaybackPreferences(),
            clock,
            StreamPrefetchCache(clock),
        )
        engine = DownloadEngine(
            context = app,
            settings = settings,
            commands = commands,
            refresher = DownloadRefresher(commands, resolve, clock),
            outcomes = outcomes,
            failures = failures,
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
    fun `an unfinished download from the last run brings the service back in the foreground`() =
        runTest(dispatcher) {
            source.put(download(Download.STATE_QUEUED))

            engine.start(backgroundScope)
            runCurrent()

            val started = shadowOf(app).nextStartedService
            assertEquals(KaeruDownloadService::class.java.name, started?.component?.className)
            // Without this flag media3 never calls startForeground, and the resumed queue runs in
            // an ordinary background service that Android stops the moment the app is not on screen.
            assertTrue(started?.getBooleanExtra("foreground", false) == true)
        }

    @Test
    fun `a failure keeps why it failed, so a screen can say it later`() = runTest(dispatcher) {
        engine.start(backgroundScope)
        runCurrent()

        source.put(
            download(Download.STATE_FAILED, Download.FAILURE_REASON_UNKNOWN),
            IOException("write failed: ENOSPC (No space left on device)"),
        )
        runCurrent()

        assertEquals("Недостаточно места", failures.messageFor(key.id))
    }

    @Test
    fun `an expired link that nobody will renew is remembered as exactly that`() = runTest(dispatcher) {
        engine.start(backgroundScope)
        runCurrent()

        // Three refreshes spend the hour's budget; the fourth failure has nothing left to try.
        repeat(4) {
            source.put(download(Download.STATE_FAILED, Download.FAILURE_REASON_UNKNOWN), forbidden())
            runCurrent()
        }

        assertEquals("Ссылка устарела, попробуйте позже", failures.messageFor(key.id))
        assertEquals(listOf(key.id), outcomes.broken)
    }

    @Test
    fun `a download that finishes is no longer a failure`() = runTest(dispatcher) {
        engine.start(backgroundScope)
        runCurrent()
        failures.record(key.id, DownloadFailureKind.NETWORK)

        source.put(download(Download.STATE_COMPLETED))
        runCurrent()

        assertEquals("Не удалось скачать", failures.messageFor(key.id))
    }

    @Test
    fun `nothing left to download starts no service`() = runTest(dispatcher) {
        source.put(download(Download.STATE_COMPLETED))

        engine.start(backgroundScope)
        runCurrent()

        assertNull(shadowOf(app).nextStartedService)
    }

    // ---- the policy reaching the engine ------------------------------------------------------

    @Test
    fun `the wifi setting reaches the engine as a requirement, and again when it changes`() =
        runTest(dispatcher) {
            engine.start(backgroundScope)
            runCurrent()

            assertEquals(listOf(Requirements(Requirements.NETWORK_UNMETERED)), commands.requirements)

            settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(wifiOnly = false)
            runCurrent()

            assertEquals(
                listOf(Requirements(Requirements.NETWORK_UNMETERED), Requirements(Requirements.NETWORK)),
                commands.requirements,
            )
        }

    @Test
    fun `a policy change that is not about the network does not disturb the engine`() = runTest(dispatcher) {
        engine.start(backgroundScope)
        runCurrent()

        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(deleteWatched = true)
        runCurrent()

        assertEquals(1, commands.requirements.size)
    }

    @Test
    fun `turning wifi-only off with a queue waiting starts the service that will run it`() =
        runTest(dispatcher) {
            source.put(download(Download.STATE_QUEUED))
            engine.start(backgroundScope)
            runCurrent()
            shadowOf(app).nextStartedService

            settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(wifiOnly = false)
            runCurrent()

            // The requirement alone would change nothing: a DownloadManager is constructed paused
            // and only the service resumes it.
            val started = shadowOf(app).nextStartedService
            assertEquals(KaeruDownloadService::class.java.name, started?.component?.className)
            assertTrue(started?.getBooleanExtra("foreground", false) == true)
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
