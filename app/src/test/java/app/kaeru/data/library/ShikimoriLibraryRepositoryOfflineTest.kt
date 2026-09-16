package app.kaeru.data.library

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.kaeru.data.auth.AccountSession
import app.kaeru.data.auth.AuthTokens
import app.kaeru.data.auth.InMemoryTokenStore
import app.kaeru.data.local.AnimeEntity
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.UserRateEntity
import app.kaeru.data.playback.RoomPlaybackSampleRepository
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.shikimoriJson
import app.kaeru.domain.error.HttpError
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.playback.MarkEpisodeUnwatched
import app.kaeru.domain.sync.OutboxSyncer
import app.kaeru.domain.sync.RateOp
import app.kaeru.domain.sync.RateOpKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Provider
import app.kaeru.domain.sync.ReplayOutcome
import app.kaeru.domain.sync.ReplayRequest
import kotlinx.coroutines.runBlocking

/**
 * What a rate write does when the network is simply not there.
 *
 * Through a real socket rather than a fake that throws: the whole point is that a dropped
 * connection — not a rejection, not a timeout the caller invented — is what turns a write into a
 * local change plus a queued one, and only the real client decides which failure this is.
 */
@RunWith(RobolectricTestRunner::class)
class ShikimoriLibraryRepositoryOfflineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val now: Instant = Instant.parse("2026-09-15T12:00:00Z")
    private lateinit var db: KaeruDatabase
    private lateinit var prefs: AppPreferences
    private lateinit var session: AccountSession
    private lateinit var api: ShikimoriApi
    private lateinit var outbox: RoomRateOutboxRepository
    private lateinit var repo: ShikimoriLibraryRepository
    private lateinit var clock: Clock
    private var replays = 0

    @Before
    fun setUp() = runTest {
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .allowMainThreadQueries().build()
        prefs = AppPreferences(
            PreferenceDataStoreFactory.create(scope = storeScope) { tmp.root.resolve("prefs.preferences_pb") },
        )
        prefs.setUserId(42)
        session = AccountSession(InMemoryTokenStore(AuthTokens("access", "refresh", 9_999_999_999, 42)), prefs, db)
        api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(shikimoriJson().asConverterFactory("application/json".toMediaType()))
            .build().create(ShikimoriApi::class.java)
        clock = Clock.fixed(now, ZoneOffset.UTC)
        outbox = RoomRateOutboxRepository(db.rateOutboxDao(), clock)
        repo = repositoryWith(OutboxSyncer { replays++; Result.success(ReplayOutcome(0, emptySet())) })
    }

    private fun repositoryWith(syncer: OutboxSyncer, replays: ReplayRequest = ReplayRequest {}) =
        ShikimoriLibraryRepository(
            api, db, db.animeDao(), db.userRateDao(), db.watchStateDao(), db.episodeProgressDao(), prefs, session,
            PosterEnricher(api), outbox, syncer, replays, Dispatchers.IO, clock,
        )

    /** The real drain, so a refresh and a replay meet the way they do in the app. */
    private fun realSyncer() = ShikimoriOutboxSyncer(api, db, db.userRateDao(), db.rateOutboxDao(), prefs, clock)

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        storeScope.cancel()
    }

    private suspend fun seedRate(animeId: Int, rateId: Long, episodes: Int, status: ListStatus = ListStatus.WATCHING) {
        db.userRateDao().upsertAll(
            listOf(UserRateEntity(rateId, animeId, status, episodes, Instant.parse("2026-09-01T00:00:00Z"))),
        )
    }

    private suspend fun seedAnime(animeId: Int) {
        db.animeDao().upsertAll(
            listOf(
                AnimeEntity(
                    animeId, "Name $animeId", "Имя $animeId", null, emptyList(), AnimeStatus.RELEASED,
                    12, 12, null, null, 2026, null, null, null,
                ),
            ),
        )
    }

    /** Nothing listening at all: the closest a unit test gets to a phone in a tunnel. */
    private fun offline() = server.shutdown()

    private suspend fun rate(animeId: Int) = db.userRateDao().getByAnimeId(animeId)

    private suspend fun queued(): List<RateOp> = outbox.observeAll().first()

    @Test
    fun `an episode marked without a network is kept locally and queued`() = runTest {
        seedAnime(10)
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        offline()

        assertTrue(repo.setEpisodes(10, 7).isSuccess)

        assertEquals(7, rate(10)?.episodes)
        assertEquals(now, rate(10)?.updatedAt)
        assertEquals(listOf(RateOp(1, 10, RateOpKind.EPISODES, "7", now)), queued())
    }

    /**
     * The whole un-mark, through the real repository: the lowered count queues like any other
     * write, and the positions this device kept go at the same time.
     */
    @Test
    fun `an episode un-marked without a network queues the lower count and forgets its position`() = runTest {
        seedAnime(10)
        seedRate(animeId = 10, rateId = 5, episodes = 7)
        val samples = RoomPlaybackSampleRepository(db, session, Dispatchers.IO)
        samples.save(
            WatchState(10, episode = 5, positionMs = 1_180_000, durationMs = 1_200_000, 12, 1, now),
            EpisodeProgress(10, 5, 1_180_000, 1_200_000, now),
        )
        samples.save(
            WatchState(10, episode = 5, positionMs = 1_180_000, durationMs = 1_200_000, 12, 1, now),
            EpisodeProgress(10, 4, 600_000, 1_200_000, now),
        )
        offline()

        assertTrue(MarkEpisodeUnwatched(repo, samples, clock)(animeId = 10, episode = 5).isSuccess)

        assertEquals(4, rate(10)?.episodes)
        assertEquals(listOf(RateOp(1, 10, RateOpKind.EPISODES, "4", now)), queued())
        assertEquals(listOf(4), db.episodeProgressDao().observeByAnime(10).first().map { it.episode })
        assertEquals(0L, db.watchStateDao().getByAnimeId(10)?.positionMs)
    }

    @Test
    fun `a status set without a network is kept locally and queued`() = runTest {
        seedAnime(10)
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        offline()

        assertTrue(repo.setStatus(10, ListStatus.COMPLETED).isSuccess)

        assertEquals(ListStatus.COMPLETED, rate(10)?.status)
        assertEquals(6, rate(10)?.episodes)
        assertEquals(listOf(RateOp(1, 10, RateOpKind.STATUS, "completed", now)), queued())
    }

    @Test
    fun `a title with no rate yet gets a local one to show the change in`() = runTest {
        seedAnime(10)
        offline()

        assertTrue(repo.setStatus(10, ListStatus.PLANNED).isSuccess)

        val local = rate(10)
        assertEquals(-10L, local?.id)
        assertEquals(0, local?.episodes)
        assertEquals(ListStatus.PLANNED, local?.status)
        assertEquals(listOf(RateOp(1, 10, RateOpKind.STATUS, "planned", now)), queued())
    }

    @Test
    fun `a rate Shikimori rejects still fails and queues nothing`() = runTest {
        seedAnime(10)
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"errors":["nope"]}"""))

        val error = repo.setEpisodes(10, 7).exceptionOrNull()

        assertTrue(error is HttpError)
        assertEquals(422, (error as HttpError).code)
        assertEquals(6, rate(10)?.episodes)
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `a title this device has never seen cannot be marked offline`() = runTest {
        offline()

        val error = repo.setStatus(10, ListStatus.PLANNED).exceptionOrNull()

        assertTrue(error is NetworkUnavailable)
        assertNull(rate(10))
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `a title resolved on the way out is saved with the rate that names it`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/api/animes?ids=") -> MockResponse().setBody(
                    """[{"id":10,"name":"Name 10","russian":"Имя 10","score":"7.0","status":"released",""" +
                        """"episodes":12,"episodes_aired":12,"aired_on":"2026-01-01"}]""",
                )
                // The card arrives, and the connection dies before the rate can be created.
                else -> MockResponse().setBody("{").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            }
        }

        assertTrue(repo.setStatus(10, ListStatus.PLANNED).isSuccess)

        assertEquals("Имя 10", db.animeDao().getById(10)?.nameRu)
        assertEquals(-10L, rate(10)?.id)
        assertEquals(listOf(RateOpKind.STATUS), queued().map { it.kind })
    }

    /** Shikimori's own answers for a library of one released title the viewer is watching. */
    private fun serveLibrary(episodes: Int, refuseWrites: Boolean = false) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val isWrite = path.startsWith("/api/v2/user_rates/") || request.method == "POST" &&
                    path == "/api/v2/user_rates"
                return when {
                    isWrite && refuseWrites -> MockResponse().setResponseCode(422).setBody("""{"errors":["nope"]}""")
                    isWrite -> MockResponse().setBody(
                        """{"id":5,"target_id":10,"status":"watching","episodes":$episodes,""" +
                            """"updated_at":"2026-09-01T00:00:00.000+03:00"}""",
                    )
                    path.startsWith("/api/v2/user_rates") && path.contains("status=watching") ->
                        MockResponse().setBody(
                            """[{"id":5,"target_id":10,"status":"watching","episodes":$episodes,""" +
                                """"updated_at":"2026-09-01T00:00:00.000+03:00"}]""",
                        )
                    path.startsWith("/api/v2/user_rates") -> MockResponse().setBody("[]")
                    path.startsWith("/api/animes?ids=") -> MockResponse().setBody(
                        """[{"id":10,"name":"Name 10","russian":"Имя 10","score":"7.0","status":"released",""" +
                            """"episodes":12,"episodes_aired":12,"aired_on":"2026-01-01"}]""",
                    )
                    path == "/api/graphql" -> MockResponse().setBody("""{"data":{"animes":[]}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    /** Anything asked of Shikimori here is a mistake, and says so rather than hanging on an empty queue. */
    private fun serveNothing() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }
    }

    @Test
    fun `a status set against a rate Shikimori has never seen is queued rather than sent`() = runTest {
        seedAnime(10)
        db.userRateDao().upsertAll(
            listOf(UserRateEntity(-10, 10, ListStatus.PLANNED, 0, Instant.parse("2026-09-01T00:00:00Z"))),
        )
        serveNothing()

        assertTrue(repo.setStatus(10, ListStatus.WATCHING).isSuccess)

        assertEquals(0, server.requestCount)
        assertEquals(ListStatus.WATCHING, rate(10)?.status)
        assertEquals(-10L, rate(10)?.id)
        assertEquals(listOf(RateOp(1, 10, RateOpKind.STATUS, "watching", now)), queued())
    }

    @Test
    fun `an episode count against a rate Shikimori has never seen is queued rather than sent`() = runTest {
        seedAnime(10)
        db.userRateDao().upsertAll(
            listOf(UserRateEntity(-10, 10, ListStatus.WATCHING, 0, Instant.parse("2026-09-01T00:00:00Z"))),
        )
        serveNothing()

        assertTrue(repo.setEpisodes(10, 3).isSuccess)

        assertEquals(0, server.requestCount)
        assertEquals(3, rate(10)?.episodes)
        assertEquals(listOf(RateOp(1, 10, RateOpKind.EPISODES, "3", now)), queued())
    }

    /** Shikimori's answer to one rate write, whatever the queue happens to be sending. */
    private fun serveRate(episodes: Int) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                """{"id":5,"target_id":10,"status":"watching","episodes":$episodes,""" +
                    """"updated_at":"2026-09-01T00:00:00.000+03:00"}""",
            )
        }
    }

    /**
     * The mark the viewer makes while an older one is still queued.
     *
     * Sent straight out it would reach Shikimori first, and the drain would then send the older
     * value on top of it — leaving the server and the device on the mark the viewer replaced,
     * with nothing left to correct it.
     */
    @Test
    fun `a mark on a title with writes still queued joins the queue instead of overtaking it`() = runTest {
        seedAnime(10)
        seedRate(animeId = 10, rateId = 5, episodes = 4)
        outbox.enqueue(10, RateOpKind.EPISODES, "5")
        serveNothing()

        assertTrue(repo.setEpisodes(10, 6).isSuccess)

        assertEquals(0, server.requestCount)
        assertEquals(6, rate(10)?.episodes)
        assertEquals(listOf("5", "6"), queued().map { it.value })

        // And the drain sends the newest of them, once.
        serveRate(episodes = 6)
        assertEquals(1, realSyncer().replay().getOrThrow().sent)

        assertEquals(1, server.requestCount)
        assertEquals("""{"user_rate":{"episodes":6}}""", server.takeRequest().body.readUtf8())
        assertEquals(6, rate(10)?.episodes)
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `a status change on a title with writes still queued joins the queue too`() = runTest {
        seedAnime(10)
        seedRate(animeId = 10, rateId = 5, episodes = 4)
        outbox.enqueue(10, RateOpKind.EPISODES, "5")
        serveNothing()

        assertTrue(repo.setStatus(10, ListStatus.COMPLETED).isSuccess)

        assertEquals(0, server.requestCount)
        assertEquals(ListStatus.COMPLETED, rate(10)?.status)
        assertEquals(listOf(RateOpKind.EPISODES, RateOpKind.STATUS), queued().map { it.kind })
    }

    @Test
    fun `a title with nothing queued is still written straight to Shikimori`() = runTest {
        seedAnime(10)
        seedRate(animeId = 10, rateId = 5, episodes = 4)
        serveRate(episodes = 6)

        assertTrue(repo.setEpisodes(10, 6).isSuccess)

        assertEquals(1, server.requestCount)
        assertEquals(6, rate(10)?.episodes)
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `a write that joins the queue asks for it to be drained`() = runTest {
        seedAnime(10)
        seedRate(animeId = 10, rateId = 5, episodes = 4)
        outbox.enqueue(10, RateOpKind.EPISODES, "5")
        serveNothing()
        var asked = 0

        assertTrue(repositoryWith(realSyncer(), ReplayRequest { asked++ }).setEpisodes(10, 6).isSuccess)

        assertEquals(1, asked)
    }

    /**
     * A drain that lands while the library list is in flight.
     *
     * The list in hand was written before the value the drain produced, and by the time the merge
     * runs the anime is no longer pending — so only the queue as it stood before the fetch can
     * say that this title is not the server's to overwrite. The drain is stood in for here: the
     * real one cannot run inside a MockWebServer dispatcher it would itself have to be served by.
     */
    @Test
    fun `a write that drains while the list is in flight is not overwritten by it`() = runTest {
        seedAnime(10)
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        val op = outbox.enqueue(10, RateOpKind.EPISODES, "7")
        serveLibrary(episodes = 2)
        val listing = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path.orEmpty().startsWith("/api/v2/user_rates?")) {
                    runBlocking {
                        outbox.remove(op)
                        db.userRateDao().upsertAll(
                            listOf(UserRateEntity(5, 10, ListStatus.WATCHING, 7, now)),
                        )
                    }
                }
                return listing.dispatch(request)
            }
        }

        assertTrue(repo.refresh().isSuccess)

        assertEquals(7, rate(10)?.episodes)
    }

    /**
     * The whole of the offline path, end to end, with the real drain inside the real refresh.
     *
     * A rejected write used to send the drain back through `refreshAnime`, which takes the account
     * lock the refresh was already holding — this call would never return, and neither would any
     * account write after it.
     */
    @Test
    fun `a refresh completes when a queued write is refused`() = runTest {
        seedAnime(10)
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        outbox.enqueue(10, RateOpKind.EPISODES, "7")
        serveLibrary(episodes = 2, refuseWrites = true)

        assertTrue(repositoryWith(realSyncer()).refresh().isSuccess)

        assertTrue(queued().isEmpty())
        // Refused, so the server's word wins rather than the local claim it never accepted.
        assertEquals(2, rate(10)?.episodes)
    }

    @Test
    fun `a refresh that drains the queue takes the server's word for what it just sent`() = runTest {
        seedAnime(10)
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        outbox.enqueue(10, RateOpKind.EPISODES, "7")
        serveLibrary(episodes = 7)

        assertTrue(repositoryWith(realSyncer()).refresh().isSuccess)

        assertTrue(queued().isEmpty())
        assertEquals(7, rate(10)?.episodes)
    }

    @Test
    fun `a full refresh does not undo a mark that has not reached Shikimori`() = runTest {
        seedAnime(10)
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        outbox.enqueue(10, RateOpKind.EPISODES, "7")
        serveLibrary(episodes = 2)

        assertTrue(repo.refresh().isSuccess)

        assertEquals(6, rate(10)?.episodes)
    }

    @Test
    fun `a full refresh takes the server's word once nothing is queued`() = runTest {
        seedAnime(10)
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        serveLibrary(episodes = 2)

        assertTrue(repo.refresh().isSuccess)

        assertEquals(2, rate(10)?.episodes)
    }

    @Test
    fun `every refresh gives the queue a chance to drain first`() = runTest {
        serveLibrary(episodes = 2)

        assertTrue(repo.refresh().isSuccess)

        assertEquals(1, replays)
    }
}
