package app.kaeru.data.download

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import app.kaeru.domain.connectivity.FakeConnectivity
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
    private val stranded = FakeStrandedDownloads()
    private val settings = FakeSettingsStore()
    private val connectivity = FakeConnectivity()
    private lateinit var engine: DownloadEngine

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        engine = engineWith(app)
    }

    private fun engineWith(context: Context): DownloadEngine {
        val resolve = ResolveEpisodeStream(
            episodes,
            FakeWatchStateRepository(),
            FakePlaybackPreferences(),
            clock,
            StreamPrefetchCache(clock),
        )
        return DownloadEngine(
            context = context,
            settings = settings,
            connectivity = connectivity,
            commands = commands,
            refresher = DownloadRefresher(commands, resolve, clock),
            outcomes = outcomes,
            failures = failures,
            stranded = stranded,
            source = source,
            io = dispatcher,
        )
    }

    @Test
    fun `starting reaches the engine on io, never on the thread that called it`() = runTest(dispatcher) {
        engine.start(backgroundScope)

        // Registering a listener is not free: it resolves the DownloadManager provider, which
        // opens a database, builds the cache and asks for an external files directory. This is
        // called from Application.onCreate, so doing any of it here would be on the main thread.
        assertEquals(0, source.listenerCount)

        runCurrent()

        assertEquals(1, source.listenerCount)
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

    /**
     * media3 calls a failure terminal: nothing resumes a failed row — not a requirement change, not
     * the service, not the next launch — so «загрузка продолжится позже» was a promise the app had
     * no way of keeping. Now the network's return is what keeps it.
     */
    @Test
    fun `a download the network killed goes back in the queue when the network returns`() =
        runTest(dispatcher) {
            engine.start(backgroundScope)
            runCurrent()
            source.put(download(Download.STATE_FAILED, Download.FAILURE_REASON_UNKNOWN), IOException("no route"))
            runCurrent()
            commands.clear()

            connectivity.goOffline()
            runCurrent()
            connectivity.goOnline()
            runCurrent()

            assertEquals(listOf(key.id), commands.added.map { it.id })
        }

    @Test
    fun `a failure that is not the network's is left alone`() = runTest(dispatcher) {
        engine.start(backgroundScope)
        runCurrent()
        // A refused signature: the refresher's business, and it has a budget of its own.
        source.put(download(Download.STATE_FAILED, Download.FAILURE_REASON_UNKNOWN), forbidden())
        runCurrent()
        commands.clear()

        connectivity.goOffline()
        runCurrent()
        connectivity.goOnline()
        runCurrent()

        assertTrue(commands.added.isEmpty())
    }

    /**
     * The add is the one command that starts a service in the foreground, and the moment a network
     * returns is very often a moment the app is in the background — where Android refuses exactly
     * that. Forgetting the failure before knowing the add worked stranded the download for good,
     * under a row that went on promising it would resume.
     */
    @Test
    fun `a re-add the platform refuses leaves the download eligible for the next try`() =
        runTest(dispatcher) {
            engine.start(backgroundScope)
            runCurrent()
            source.put(download(Download.STATE_FAILED, Download.FAILURE_REASON_UNKNOWN), IOException("no route"))
            runCurrent()
            commands.clear()
            commands.refuseAdds = true

            connectivity.goOffline()
            runCurrent()
            connectivity.goOnline()
            runCurrent()

            assertTrue(commands.added.isEmpty())
            assertEquals(setOf(key.id), stranded.stranded())

            // The viewer opens the app, which is where the platform allows a foreground start.
            commands.refuseAdds = false
            engine.onForeground()
            runCurrent()

            assertEquals(listOf(key.id), commands.added.map { it.id })
            assertTrue(stranded.stranded().isEmpty())
        }

    /**
     * The tunnel is usually the last thing that happens before the phone goes in a pocket and the
     * process is killed. An eligibility that only lived in memory kept the promise for a viewer who
     * stayed in the app and broke it for everybody else.
     */
    @Test
    fun `a download stranded before a restart is put back when the network returns`() =
        runTest(dispatcher) {
            // A new process: the row is in the index, nothing is in memory, the note survived.
            stranded.seed(key.id)
            source.put(download(Download.STATE_FAILED, Download.FAILURE_REASON_UNKNOWN))

            engine.start(backgroundScope)
            runCurrent()
            connectivity.goOffline()
            runCurrent()
            connectivity.goOnline()
            runCurrent()

            assertEquals(listOf(key.id), commands.added.map { it.id })
        }

    @Test
    fun `a note about a download that is no longer there is thrown away`() = runTest(dispatcher) {
        stranded.seed("100:9:609:720")

        engine.start(backgroundScope)
        runCurrent()
        connectivity.goOffline()
        runCurrent()
        connectivity.goOnline()
        runCurrent()

        assertTrue(commands.added.isEmpty())
        assertTrue(stranded.stranded().isEmpty())
    }

    @Test
    fun `a network that never went away re-queues nothing twice`() = runTest(dispatcher) {
        engine.start(backgroundScope)
        runCurrent()
        source.put(download(Download.STATE_FAILED, Download.FAILURE_REASON_UNKNOWN), IOException("no route"))
        runCurrent()
        commands.clear()

        connectivity.goOnline()
        runCurrent()

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
    fun `a foreground start the platform refuses is tried again when the app comes forward`() =
        runTest(dispatcher) {
            val platform = RefusingContext(app)
            val engine = engineWith(platform)
            source.put(download(Download.STATE_QUEUED))

            engine.start(backgroundScope)
            runCurrent()

            // Nothing fell back to a plain background start. That is the service Android stops as
            // soon as the app is off the screen, which is the state this whole path exists to avoid.
            assertNull(shadowOf(app).nextStartedService)

            platform.refusing = false
            engine.onForeground()
            runCurrent()

            val started = shadowOf(app).nextStartedService
            assertEquals(KaeruDownloadService::class.java.name, started?.component?.className)
            assertTrue(started?.getBooleanExtra("foreground", false) == true)
        }

    @Test
    fun `coming forward after a start that was never refused touches nothing`() = runTest(dispatcher) {
        source.put(download(Download.STATE_COMPLETED))
        engine.start(backgroundScope)
        runCurrent()

        engine.onForeground()
        runCurrent()

        assertNull(shadowOf(app).nextStartedService)
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
    fun `a network that dropped part way through does not read as an expired link`() =
        runTest(dispatcher) {
            // The refresher takes on anything that failed after a byte arrived, so a lost network
            // reaches it looking like an expired signature — and the resolve it tries then fails
            // for the same reason. The exception is the only thing that knows better.
            episodes.urls = mapOf(Quality.P480 to "https://cdn/480.m3u8")
            engine.start(backgroundScope)
            runCurrent()

            source.put(
                download(Download.STATE_FAILED, Download.FAILURE_REASON_UNKNOWN, bytes = 120_000_000),
                IOException("connection reset"),
            )
            runCurrent()

            assertEquals("Нет связи, загрузка продолжится позже", failures.messageFor(key.id))
            assertEquals(listOf(key.id), outcomes.broken)
        }

    @Test
    fun `a link nobody will renew still reads as expired when nothing says otherwise`() =
        runTest(dispatcher) {
            episodes.urls = mapOf(Quality.P480 to "https://cdn/480.m3u8")
            engine.start(backgroundScope)
            runCurrent()

            source.put(
                download(Download.STATE_FAILED, Download.FAILURE_REASON_UNKNOWN, bytes = 120_000_000),
                IllegalStateException("nothing about the transfer"),
            )
            runCurrent()

            assertEquals("Ссылка устарела, попробуйте позже", failures.messageFor(key.id))
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

    private fun download(
        state: Int,
        failureReason: Int = Download.FAILURE_REASON_NONE,
        bytes: Long = 0,
    ) = downloadOf(
        DownloadRequest.Builder(key.id, "https://cdn/720.m3u8?sign=stale".toUri())
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setData(DownloadPayload.of(key, "Фрирен", anilibria).encode())
            .build(),
        state,
        bytes = bytes,
        failureReason = failureReason,
    )

    /**
     * A phone that turns a background app away from a foreground service, as Android 12 and up
     * does — but lets a plain `startService` through, as a lenient OEM might.
     *
     * That combination is the point. It is the only phone where falling back to
     * `DownloadService.start` would succeed, and succeeding there is worse than failing: the
     * queue would transfer inside an ordinary background service with no notification, which
     * Android stops without telling anybody.
     */
    private class RefusingContext(base: Context) : ContextWrapper(base) {
        var refusing = true

        override fun startForegroundService(service: Intent): ComponentName? =
            if (refusing) {
                throw IllegalStateException("not allowed to start a foreground service in the background")
            } else {
                super.startForegroundService(service)
            }
    }

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
        /** What the source has to offer. A height the download wants is what a refresh needs. */
        var urls: Map<Quality, String> = mapOf(Quality.P720 to "https://cdn/720.m3u8?sign=fresh")

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
                urls = urls,
                resolvedAt = Instant.parse("2026-09-15T10:00:00Z"),
            ),
        )
    }

    private companion object {
        const val ANIME = 52991
    }
}
