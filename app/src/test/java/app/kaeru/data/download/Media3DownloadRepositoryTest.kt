package app.kaeru.data.download

import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import app.cash.turbine.test
import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.download.DownloadPolicy
import app.kaeru.domain.download.DownloadState
import app.kaeru.domain.error.DownloadLimitReached
import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.playback.FakePlaybackPreferences
import app.kaeru.domain.playback.FakeWatchStateRepository
import app.kaeru.domain.playback.ResolveEpisodeStream
import app.kaeru.domain.playback.StreamPrefetchCache
import app.kaeru.domain.settings.FakeSettingsStore
import app.kaeru.domain.source.EpisodeSourceProvider
import app.kaeru.player.FakeLibraryRepository
import app.kaeru.test.MutableClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
import java.time.Instant
import javax.inject.Provider

/**
 * The repository over a fake engine: what it asks the engine for, and what it makes of what the
 * engine holds. Nothing here needs a Looper, a cache directory or a database — the two seams
 * ([DownloadCommands] and [DownloadsSource]) are exactly the media3 surface this class touches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class Media3DownloadRepositoryTest {

    private val dispatcher = StandardTestDispatcher()
    private val now: Instant = Instant.parse("2026-09-15T10:00:00Z")
    private val clock = MutableClock(now)

    private val anilibria = Translation(609, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12)

    private val engine = FakeDownloadsSource()
    private val commands = FakeDownloadCommands(engine)
    private val episodes = FakeEpisodeSource()
    private val watchStates = FakeWatchStateRepository()
    private val settings = FakeSettingsStore()
    private val library = FakeLibraryRepository()
    private lateinit var repository: Media3DownloadRepository

    @Before
    fun setUp() {
        episodes.translations = Result.success(listOf(anilibria))
        // A remembered track, so «скачать» asks the source for the same voice the viewer watches.
        watchStates.seed(WatchState(ANIME, 3, 0, 0, anilibria.id, 1, now.minusSeconds(3600)))
        library.put(
            LibraryEntry(
                Anime(
                    ANIME, "Фрирен", "Frieren", null, emptyList(), AnimeStatus.RELEASED,
                    episodes = 12, episodesAired = 12, nextEpisodeAt = null,
                    score = null, year = null, studio = null, description = null,
                ),
                UserRate(1, ANIME, ListStatus.WATCHING, episodes = 3, updatedAt = now),
                null,
            ),
        )
        repository = Media3DownloadRepository(
            source = engine,
            commands = commands,
            resolve = ResolveEpisodeStream(
                episodes,
                watchStates,
                FakePlaybackPreferences(),
                clock,
                StreamPrefetchCache(clock),
            ),
            settings = settings,
            library = Provider { library },
            watchStates = watchStates,
            clock = clock,
            io = dispatcher,
        )
    }

    // ---- reading what the engine holds -------------------------------------------------------

    @Test
    fun `the engine's rows become episode downloads, and rows this app did not write are ignored`() =
        runTest(dispatcher) {
            engine.put(download(key(episode = 7), Download.STATE_COMPLETED, bytes = 320_000_000, percent = 100f))
            engine.put(downloadOf(DownloadRequest.Builder("a leftover scheme", "https://cdn/x".toUri()).build()))

            val only = repository.observeAll().first().single()

            assertEquals(key(episode = 7), only.key)
            assertEquals(DownloadState.COMPLETED, only.state)
            assertEquals(320_000_000L, only.bytes)
            assertEquals(1f, only.progress, 0.001f)
            assertNull(only.failure)
        }

    @Test
    fun `a queued download waiting on the policy says so rather than reading as queued`() = runTest(dispatcher) {
        engine.put(download(key(episode = 7), Download.STATE_QUEUED))
        engine.notMet = Requirements.NETWORK_UNMETERED

        assertEquals(DownloadState.WAITING_FOR_WIFI, repository.observeAll().first().single().state)

        engine.notMet = 0
        assertEquals(DownloadState.QUEUED, repository.observeAll().first().single().state)
    }

    @Test
    fun `a failed download carries copy about the link rather than nothing`() = runTest(dispatcher) {
        engine.put(download(key(episode = 7), Download.STATE_FAILED, failureReason = Download.FAILURE_REASON_UNKNOWN))

        val failed = repository.observeAll().first().single()

        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals("Ссылка устарела, попробуйте позже", failed.failure)
    }

    @Test
    fun `one title's downloads come back in episode order`() = runTest(dispatcher) {
        engine.put(download(key(episode = 9)))
        engine.put(download(key(episode = 2)))
        engine.put(download(DownloadKey(OTHER_ANIME, 1, anilibria.id, Quality.P720)))

        assertEquals(listOf(2, 9), repository.observe(ANIME).first().map { it.episode })
    }

    @Test
    fun `used bytes are every download's bytes, unfinished ones included`() = runTest(dispatcher) {
        engine.put(download(key(episode = 1), Download.STATE_COMPLETED, bytes = 300))
        engine.put(download(key(episode = 2), Download.STATE_DOWNLOADING, bytes = 45))
        engine.put(
            downloadOf(DownloadRequest.Builder("not ours", "https://cdn/x".toUri()).build(), bytes = 5),
        )

        assertEquals(350L, repository.usedBytes.first())
    }

    @Test
    fun `only a finished download is something to play`() = runTest(dispatcher) {
        engine.put(download(key(episode = 7), Download.STATE_DOWNLOADING, bytes = 10))

        assertNull(repository.completed(ANIME, 7))

        engine.put(download(key(episode = 7), Download.STATE_COMPLETED, bytes = 320))

        assertEquals(DownloadState.COMPLETED, repository.completed(ANIME, 7)?.state)
        assertNull(repository.completed(ANIME, 8))
    }

    @Test
    fun `a finished download reads back as one rung the player can open`() = runTest(dispatcher) {
        engine.put(download(key(episode = 7), Download.STATE_COMPLETED, bytes = 320))

        val stream = repository.completedStream(ANIME, 7)

        // The link it was fetched with, expired signature and all: the player reads through the
        // same cache under a key that has no signature in it.
        assertEquals(mapOf(Quality.P720 to "https://cdn/720.m3u8"), stream?.urls)
        assertEquals(7, stream?.episode)
        assertEquals(anilibria.id, stream?.translation?.id)
        assertEquals("AniLibria.TV", stream?.translation?.title)
    }

    @Test
    fun `an episode still arriving is not a stream to open`() = runTest(dispatcher) {
        engine.put(download(key(episode = 7), Download.STATE_DOWNLOADING, bytes = 10))

        assertNull(repository.completedStream(ANIME, 7))
        assertNull(repository.completedStream(ANIME, 8))
    }

    @Test
    fun `a row with no readable blob still plays, under the track its id names`() = runTest(dispatcher) {
        // An older build, or a blob this one cannot read. The id is the part that survives, and
        // it carries enough to remember the voice by.
        engine.put(unreadable(episode = 7))

        val stream = repository.completedStream(ANIME, 7)

        assertEquals(anilibria.id, stream?.translation?.id)
        assertEquals("", stream?.translation?.title)
    }

    @Test
    fun `a row with no readable blob keeps the season this anime is already mapped to`() =
        runTest(dispatcher) {
            // The blob is where the season lives. Naming one here would be a guess the first
            // progress sample writes down, and a later resolve would ask Kodik for it.
            watchStates.seed(WatchState(ANIME, 3, 0, 0, anilibria.id, kodikSeason = 2, updatedAt = now))
            engine.put(unreadable(episode = 7))

            assertEquals(2, repository.completedStream(ANIME, 7)?.translation?.season)
        }

    /** A finished download media3 is holding with nothing this build can read in its blob. */
    private fun unreadable(episode: Int): Download = downloadOf(
        DownloadRequest.Builder(key(episode).id, "https://cdn/720.m3u8".toUri())
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build(),
        Download.STATE_COMPLETED,
    )

    // ---- enqueueing --------------------------------------------------------------------------

    @Test
    fun `enqueue resolves the link and hands the engine a request keyed by the episode`() =
        runTest(dispatcher) {
            assertTrue(repository.enqueue(ANIME, 7).isSuccess)

            val request = commands.added.single()
            assertEquals(DownloadKey(ANIME, 7, anilibria.id, Quality.P720).id, request.id)
            assertEquals(MimeTypes.APPLICATION_M3U8, request.mimeType)
            assertEquals("https://cdn/720.m3u8", request.uri.toString())

            val payload = DownloadPayload.decode(request.data)
            assertEquals(ANIME, payload?.animeId)
            assertEquals(7, payload?.episode)
            assertEquals(anilibria.id, payload?.translationId)
            assertEquals(720, payload?.quality)
            assertEquals("Фрирен", payload?.title)
            assertEquals(anilibria.title, payload?.translationTitle)
            assertEquals(anilibria.season, payload?.season)
        }

    @Test
    fun `downloading an episode does not move where the viewer is`() = runTest(dispatcher) {
        repository.enqueue(ANIME, 12).getOrThrow()

        // Preparing episode 12 must not rewrite the row that says the viewer is on episode 3.
        assertTrue(watchStates.saved.isEmpty())
    }

    @Test
    fun `the policy's height is taken when the caller names none`() = runTest(dispatcher) {
        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(quality = Quality.P480)
        episodes.urls = mapOf(Quality.P360 to "https://cdn/360.m3u8", Quality.P480 to "https://cdn/480.m3u8")

        repository.enqueue(ANIME, 7).getOrThrow()

        assertEquals(480, DownloadKey.parse(commands.added.single().id)?.quality?.height)
    }

    @Test
    fun `a height asked for by hand beats the policy's`() = runTest(dispatcher) {
        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(quality = Quality.P480)
        episodes.urls = mapOf(Quality.P480 to "https://cdn/480.m3u8", Quality.P720 to "https://cdn/720.m3u8")

        repository.enqueue(ANIME, 7, Quality.P720).getOrThrow()

        assertEquals(720, DownloadKey.parse(commands.added.single().id)?.quality?.height)
    }

    @Test
    fun `a policy with no height of its own takes the best the source offers`() = runTest(dispatcher) {
        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(quality = null)
        episodes.urls = mapOf(Quality.P480 to "https://cdn/480.m3u8", Quality.P1080 to "https://cdn/1080.m3u8")

        repository.enqueue(ANIME, 7).getOrThrow()

        assertEquals(1080, DownloadKey.parse(commands.added.single().id)?.quality?.height)
    }

    @Test
    fun `a height the source does not have falls back to the nearest one below it`() = runTest(dispatcher) {
        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(quality = Quality.P1080)
        episodes.urls = mapOf(Quality.P360 to "https://cdn/360.m3u8", Quality.P480 to "https://cdn/480.m3u8")

        repository.enqueue(ANIME, 7).getOrThrow()

        assertEquals(480, DownloadKey.parse(commands.added.single().id)?.quality?.height)
    }

    @Test
    fun `a height below everything on offer takes the lowest there is`() = runTest(dispatcher) {
        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(quality = Quality.P360)
        episodes.urls = mapOf(Quality.P720 to "https://cdn/720.m3u8", Quality.P1080 to "https://cdn/1080.m3u8")

        repository.enqueue(ANIME, 7).getOrThrow()

        assertEquals(720, DownloadKey.parse(commands.added.single().id)?.quality?.height)
    }

    @Test
    fun `a limit that the next download would break refuses it and says by how much`() = runTest(dispatcher) {
        // 200 MB used and one finished episode of that size, so the next one is expected to
        // weigh 200 MB too: 400 MB against a 350 MB limit.
        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(limitBytes = 350_000_000)
        engine.put(download(key(episode = 1), Download.STATE_COMPLETED, bytes = 200_000_000))

        val refused = repository.enqueue(ANIME, 7).exceptionOrNull() as DownloadLimitReached

        assertEquals(350_000_000L, refused.limitBytes)
        assertEquals(200_000_000L, refused.usedBytes)
        assertTrue(commands.added.isEmpty())
    }

    @Test
    fun `before this device has downloaded anything an episode is assumed to weigh 400 MB`() =
        runTest(dispatcher) {
            settings.downloadPolicy.value =
                DownloadPolicy.DEFAULT.copy(limitBytes = DownloadPolicy.FALLBACK_ESTIMATE - 1)
            assertTrue(repository.enqueue(ANIME, 7).exceptionOrNull() is DownloadLimitReached)

            settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(limitBytes = DownloadPolicy.FALLBACK_ESTIMATE)
            assertTrue(repository.enqueue(ANIME, 7).isSuccess)
        }

    @Test
    fun `once there are finished episodes the estimate is what they actually weigh`() = runTest(dispatcher) {
        engine.put(download(key(episode = 1), Download.STATE_COMPLETED, bytes = 100_000_000))
        engine.put(download(key(episode = 2), Download.STATE_COMPLETED, bytes = 300_000_000))

        // 400 MB used, 200 MB expected: 599 MB is not enough and 600 MB is.
        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(limitBytes = 599_999_999)
        assertTrue(repository.enqueue(ANIME, 7).exceptionOrNull() is DownloadLimitReached)

        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(limitBytes = 600_000_000)
        assertTrue(repository.enqueue(ANIME, 7).isSuccess)
    }

    @Test
    fun `no limit takes anything`() = runTest(dispatcher) {
        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(limitBytes = null)
        engine.put(download(key(episode = 1), Download.STATE_COMPLETED, bytes = 900_000_000_000))

        assertTrue(repository.enqueue(ANIME, 7).isSuccess)
    }

    @Test
    fun `the same episode at another height replaces the one already there`() = runTest(dispatcher) {
        val old = DownloadKey(ANIME, 7, anilibria.id, Quality.P480)
        engine.put(download(old, Download.STATE_COMPLETED, bytes = 200))
        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(quality = Quality.P720)

        repository.enqueue(ANIME, 7).getOrThrow()

        assertEquals(listOf(old.id), commands.removed)
        assertEquals(DownloadKey(ANIME, 7, anilibria.id, Quality.P720).id, commands.added.single().id)
    }

    @Test
    fun `the same episode at the same height is not removed first`() = runTest(dispatcher) {
        engine.put(download(key(episode = 7), Download.STATE_FAILED, failureReason = Download.FAILURE_REASON_UNKNOWN))

        repository.enqueue(ANIME, 7).getOrThrow()

        assertTrue(commands.removed.isEmpty())
    }

    @Test
    fun `an episode being resolved shows as resolving, and stops as soon as the engine has it`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            episodes.beforeResolve = { gate.await() }
            val scope = this

            repository.observeAll().test {
                assertEquals(emptyList<Any>(), awaitItem())

                val enqueue = scope.async { repository.enqueue(ANIME, 7) }
                runCurrent()

                val resolving = awaitItem().single()
                assertEquals(DownloadState.RESOLVING, resolving.state)
                assertEquals(7, resolving.episode)

                gate.complete(Unit)
                assertTrue(enqueue.await().isSuccess)

                assertEquals(DownloadState.QUEUED, awaitItem().single().state)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a resolve that fails comes back as the failure and leaves nothing behind`() = runTest(dispatcher) {
        episodes.stream = { _, _, _ -> Result.failure(EpisodeNotAvailable(ANIME, 7)) }

        val failed = repository.enqueue(ANIME, 7)

        assertTrue(failed.exceptionOrNull() is EpisodeNotAvailable)
        assertTrue(commands.added.isEmpty())
        assertEquals(emptyList<Any>(), repository.observeAll().first())
    }

    // ---- removing ----------------------------------------------------------------------------

    @Test
    fun `removing an episode removes whatever is there for it`() = runTest(dispatcher) {
        engine.put(download(key(episode = 7)))
        engine.put(download(key(episode = 8)))

        repository.remove(ANIME, 7)

        assertEquals(listOf(key(episode = 7).id), commands.removed)
    }

    @Test
    fun `removing a title removes every episode of it and nothing else`() = runTest(dispatcher) {
        engine.put(download(key(episode = 7)))
        engine.put(download(key(episode = 8)))
        engine.put(download(DownloadKey(OTHER_ANIME, 1, anilibria.id, Quality.P720)))

        repository.removeAll(ANIME)

        assertEquals(setOf(key(episode = 7).id, key(episode = 8).id), commands.removed.toSet())
    }

    @Test
    fun `clearing everything is one command rather than one per episode`() = runTest(dispatcher) {
        engine.put(download(key(episode = 7)))
        engine.put(download(key(episode = 8)))

        repository.removeAll()

        assertEquals(1, commands.clearedAll)
        assertTrue(commands.removed.isEmpty())
    }

    // ---- the policy reaching the engine ------------------------------------------------------

    @Test
    fun `the wifi setting reaches the engine as a requirement, and again when it changes`() =
        runTest(dispatcher) {
            repository.start(backgroundScope)
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
        repository.start(backgroundScope)
        runCurrent()

        settings.downloadPolicy.value = DownloadPolicy.DEFAULT.copy(deleteWatched = true)
        runCurrent()

        assertEquals(1, commands.requirements.size)
    }

    @Test
    fun `a collector that goes away takes its listener with it`() = runTest(dispatcher) {
        repository.observeAll().first()

        assertEquals(0, engine.listenerCount)
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private fun key(episode: Int) = DownloadKey(ANIME, episode, anilibria.id, Quality.P720)

    private fun download(
        key: DownloadKey,
        state: Int = Download.STATE_COMPLETED,
        bytes: Long = 0,
        percent: Float = 0f,
        failureReason: Int = Download.FAILURE_REASON_NONE,
    ): Download = downloadOf(
        DownloadRequest.Builder(key.id, "https://cdn/${key.quality.height}.m3u8".toUri())
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setData(DownloadPayload.of(key, "Фрирен", anilibria).encode())
            .build(),
        state,
        bytes,
        percent,
        failureReason,
    )

    private class FakeEpisodeSource : EpisodeSourceProvider {
        var translations: Result<List<Translation>> = Result.success(emptyList())
        var urls: Map<Quality, String> = mapOf(Quality.P720 to "https://cdn/720.m3u8")
        var beforeResolve: suspend () -> Unit = {}
        var stream: ((Int, Int, Translation?) -> Result<EpisodeStream>)? = null

        override suspend fun translations(shikimoriId: Int) = translations

        override suspend fun resolve(
            shikimoriId: Int,
            episode: Int,
            translation: Translation?,
        ): Result<EpisodeStream> {
            beforeResolve()
            stream?.let { return it(shikimoriId, episode, translation) }
            return Result.success(
                EpisodeStream(
                    animeId = shikimoriId,
                    episode = episode,
                    translation = translation ?: Translation(0, "По умолчанию", TranslationKind.VOICE, null),
                    urls = urls,
                    resolvedAt = Instant.parse("2026-09-15T10:00:00Z"),
                ),
            )
        }
    }

    private companion object {
        const val ANIME = 52991
        const val OTHER_ANIME = 100
    }
}
