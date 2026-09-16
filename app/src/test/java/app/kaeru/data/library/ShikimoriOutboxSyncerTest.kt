package app.kaeru.data.library

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.UserRateEntity
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.shikimoriJson
import app.kaeru.domain.model.ListStatus
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
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
import app.kaeru.data.auth.AccountSession
import app.kaeru.data.auth.AuthTokens
import app.kaeru.data.auth.InMemoryTokenStore
import app.kaeru.data.local.RateOutboxEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The queue reaching Shikimori: in order, one request at a time, and never twice. */
@RunWith(RobolectricTestRunner::class)
class ShikimoriOutboxSyncerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val now: Instant = Instant.parse("2026-09-15T12:00:00Z")
    private lateinit var db: KaeruDatabase
    private lateinit var prefs: AppPreferences
    private lateinit var session: AccountSession
    private lateinit var outbox: RoomRateOutboxRepository
    private lateinit var syncer: ShikimoriOutboxSyncer

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
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(shikimoriJson().asConverterFactory("application/json".toMediaType()))
            .build().create(ShikimoriApi::class.java)
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        outbox = RoomRateOutboxRepository(db.rateOutboxDao(), clock)
        syncer = ShikimoriOutboxSyncer(api, db, db.userRateDao(), db.rateOutboxDao(), prefs, clock)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        storeScope.cancel()
    }

    private suspend fun seedRate(animeId: Int, rateId: Long, episodes: Int, status: ListStatus = ListStatus.WATCHING) {
        db.userRateDao().upsertAll(listOf(UserRateEntity(rateId, animeId, status, episodes, now)))
    }

    private fun rateBody(id: Long, animeId: Int, status: String, episodes: Int) = MockResponse().setBody(
        """{"id":$id,"target_id":$animeId,"status":"$status","episodes":$episodes,""" +
            """"updated_at":"2026-09-01T00:00:00.000+03:00"}""",
    )

    /**
     * The connection dropping mid-answer.
     *
     * Not `DISCONNECT_AT_START`: the wrapper this MockWebServer ships with turns that into an
     * empty 200, which is a parse error rather than the lost connection the test is about.
     */
    private fun disconnect() = MockResponse().setBody("{")
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)

    private suspend fun queued(): List<RateOp> = outbox.observeAll().first()

    @Test
    fun `queued writes are sent in the order they were made`() = runTest {
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        seedRate(animeId = 20, rateId = 8, episodes = 1)
        outbox.enqueue(10, RateOpKind.EPISODES, "7")
        outbox.enqueue(20, RateOpKind.STATUS, "completed")
        server.enqueue(rateBody(5, 10, "watching", 7))
        server.enqueue(rateBody(8, 20, "completed", 1))

        assertEquals(2, syncer.replay().getOrThrow().sent)

        assertEquals("/api/v2/user_rates/5", server.takeRequest().path)
        assertEquals("/api/v2/user_rates/8", server.takeRequest().path)
        assertTrue(queued().isEmpty())
        assertEquals(7, db.userRateDao().getByAnimeId(10)?.episodes)
        assertEquals(ListStatus.COMPLETED, db.userRateDao().getByAnimeId(20)?.status)
    }

    @Test
    fun `the network going away again leaves the rest of the queue in place`() = runTest {
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        seedRate(animeId = 20, rateId = 8, episodes = 1)
        outbox.enqueue(10, RateOpKind.EPISODES, "7")
        outbox.enqueue(20, RateOpKind.STATUS, "completed")
        server.enqueue(rateBody(5, 10, "watching", 7))
        server.enqueue(disconnect())

        assertEquals(1, syncer.replay().getOrThrow().sent)

        assertEquals(listOf(20), queued().map { it.animeId })
        assertEquals(ListStatus.WATCHING, db.userRateDao().getByAnimeId(20)?.status)
    }

    @Test
    fun `a write Shikimori refuses is dropped and named for the caller to re-read`() = runTest {
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        outbox.enqueue(10, RateOpKind.EPISODES, "7")
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"errors":["nope"]}"""))

        val outcome = syncer.replay().getOrThrow()

        assertEquals(0, outcome.sent)
        assertEquals(setOf(10), outcome.refused)
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `a run of marks on one title costs one request`() = runTest {
        seedRate(animeId = 10, rateId = 5, episodes = 3)
        outbox.enqueue(10, RateOpKind.EPISODES, "4")
        outbox.enqueue(10, RateOpKind.EPISODES, "5")
        outbox.enqueue(10, RateOpKind.EPISODES, "6")
        server.enqueue(rateBody(5, 10, "watching", 6))

        assertEquals(1, syncer.replay().getOrThrow().sent)

        assertEquals(1, server.requestCount)
        assertEquals("""{"user_rate":{"episodes":6}}""", server.takeRequest().body.readUtf8())
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `a title added to the list offline is created rather than updated`() = runTest {
        seedRate(animeId = 10, rateId = -10, episodes = 0, status = ListStatus.PLANNED)
        outbox.enqueue(10, RateOpKind.STATUS, "planned")
        server.enqueue(rateBody(900, 10, "planned", 0))

        assertEquals(1, syncer.replay().getOrThrow().sent)

        val request = server.takeRequest()
        assertEquals("/api/v2/user_rates", request.path)
        assertEquals("POST", request.method)
        assertEquals(
            """{"user_rate":{"user_id":42,"target_id":10,"target_type":"Anime","status":"planned"}}""",
            request.body.readUtf8(),
        )
        assertEquals(900L, db.userRateDao().getByAnimeId(10)?.id)
        assertEquals(1, db.userRateDao().observeAll().first().size)
    }

    @Test
    fun `an episode count still waiting is not undone by the status that goes before it`() = runTest {
        seedRate(animeId = 10, rateId = 5, episodes = 9, status = ListStatus.COMPLETED)
        outbox.enqueue(10, RateOpKind.STATUS, "completed")
        outbox.enqueue(10, RateOpKind.EPISODES, "9")
        // Shikimori still has the old count when it answers the status change.
        server.enqueue(rateBody(5, 10, "completed", 3))
        server.enqueue(disconnect())

        assertEquals(1, syncer.replay().getOrThrow().sent)

        assertEquals(9, db.userRateDao().getByAnimeId(10)?.episodes)
        assertEquals(listOf(RateOpKind.EPISODES), queued().map { it.kind })
    }

    @Test
    fun `an empty queue asks Shikimori nothing`() = runTest {
        assertEquals(0, syncer.replay().getOrThrow().sent)

        assertEquals(0, server.requestCount)
    }

    /**
     * The whole reason a drain reports refused titles instead of re-reading them itself.
     *
     * `AccountSession`'s mutex is not reentrant, and a refresh replays from inside it. If anything
     * in a drain reached for that lock, this test would hang rather than fail.
     */
    @Test
    fun `a drain runs while an account write holds the session lock`() = runTest {
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        outbox.enqueue(10, RateOpKind.EPISODES, "7")
        server.enqueue(rateBody(5, 10, "watching", 7))
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            session.withAccount {
                held.complete(Unit)
                release.await()
            }
        }
        held.await()

        assertEquals(1, syncer.replay().getOrThrow().sent)

        release.complete(Unit)
        holder.join()
    }

    @Test
    fun `an episode count with no rate to set it on is dropped rather than blocking the queue`() = runTest {
        seedRate(animeId = 10, rateId = -10, episodes = 0, status = ListStatus.PLANNED)
        seedRate(animeId = 20, rateId = 8, episodes = 1)
        outbox.enqueue(10, RateOpKind.EPISODES, "3")
        outbox.enqueue(20, RateOpKind.EPISODES, "2")
        server.enqueue(rateBody(8, 20, "watching", 2))

        val outcome = syncer.replay().getOrThrow()

        assertEquals(1, outcome.sent)
        assertEquals(setOf(10), outcome.refused)
        assertEquals(1, server.requestCount)
        assertTrue(queued().isEmpty())
        assertEquals(2, db.userRateDao().getByAnimeId(20)?.episodes)
    }

    @Test
    fun `an episode count queued behind a create waits for the rate the create makes`() = runTest {
        seedRate(animeId = 10, rateId = -10, episodes = 0, status = ListStatus.PLANNED)
        outbox.enqueue(10, RateOpKind.STATUS, "watching")
        outbox.enqueue(10, RateOpKind.EPISODES, "3")
        server.enqueue(rateBody(900, 10, "watching", 0))
        server.enqueue(rateBody(900, 10, "watching", 3))

        assertEquals(2, syncer.replay().getOrThrow().sent)

        assertEquals("/api/v2/user_rates", server.takeRequest().path)
        assertEquals("/api/v2/user_rates/900", server.takeRequest().path)
        assertEquals(3, db.userRateDao().getByAnimeId(10)?.episodes)
        assertEquals(900L, db.userRateDao().getByAnimeId(10)?.id)
    }

    @Test
    fun `a drain stops when the account changes under it`() = runTest {
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        seedRate(animeId = 20, rateId = 8, episodes = 1)
        outbox.enqueue(10, RateOpKind.EPISODES, "7")
        outbox.enqueue(20, RateOpKind.EPISODES, "2")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Somebody signs out and back in as somebody else while the first write is away.
                runBlocking { prefs.setUserId(43) }
                return rateBody(5, 10, "watching", 7)
            }
        }

        assertEquals(1, syncer.replay().getOrThrow().sent)

        assertEquals(1, server.requestCount)
        assertEquals(listOf(20), queued().map { it.animeId })
    }

    @Test
    fun `a queued write of an unknown kind is dropped instead of wedging the queue`() = runTest {
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        db.rateOutboxDao().insert(RateOutboxEntity(animeId = 10, kind = "FUTURE", value = "?", createdAt = now))
        outbox.enqueue(10, RateOpKind.EPISODES, "7")
        server.enqueue(rateBody(5, 10, "watching", 7))

        assertEquals(1, syncer.replay().getOrThrow().sent)

        assertTrue(db.rateOutboxDao().getAll().isEmpty())
        assertEquals(7, db.userRateDao().getByAnimeId(10)?.episodes)
    }

    @Test
    fun `a replayed write is stamped with the moment the viewer made it`() = runTest {
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        outbox.enqueue(10, RateOpKind.EPISODES, "7")
        server.enqueue(rateBody(5, 10, "watching", 7))

        syncer.replay().getOrThrow()

        // `now` is when the op was queued; the clock has not moved, but the drain must read the
        // op rather than the wall, so the home rows keep the order the viewer acted in.
        assertEquals(now, db.userRateDao().getByAnimeId(10)?.updatedAt)
    }

    /** SQLite binds at most 999 variables, and a superseded run has no bound. */
    @Test
    fun `a run of marks longer than SQLite will bind still collapses to one request`() = runTest {
        seedRate(animeId = 10, rateId = 5, episodes = 0)
        repeat(1_200) { outbox.enqueue(10, RateOpKind.EPISODES, it.toString()) }
        server.enqueue(rateBody(5, 10, "watching", 1_199))

        assertEquals(1, syncer.replay().getOrThrow().sent)

        assertEquals(1, server.requestCount)
        assertEquals("""{"user_rate":{"episodes":1199}}""", server.takeRequest().body.readUtf8())
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `two drains at once do not send the same write twice`() = runTest {
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        outbox.enqueue(10, RateOpKind.EPISODES, "7")
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                inFlight.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return rateBody(5, 10, "watching", 7)
            }
        }

        val first = async(Dispatchers.IO) { syncer.replay().getOrThrow() }
        check(inFlight.await(10, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { syncer.replay().getOrThrow() }
        release.countDown()

        assertEquals(1, first.await().sent)
        assertEquals(0, second.await().sent)
        assertEquals(1, server.requestCount)
    }
}
