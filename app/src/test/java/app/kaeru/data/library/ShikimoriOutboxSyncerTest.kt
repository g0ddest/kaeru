package app.kaeru.data.library

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.UserRateEntity
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.shikimoriJson
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.sync.RateOp
import app.kaeru.domain.sync.RateOpKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import javax.inject.Provider

/** The queue reaching Shikimori: in order, one request at a time, and never twice. */
@RunWith(RobolectricTestRunner::class)
class ShikimoriOutboxSyncerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    private val now: Instant = Instant.parse("2026-09-15T12:00:00Z")
    private lateinit var db: KaeruDatabase
    private lateinit var outbox: RoomRateOutboxRepository
    private lateinit var syncer: ShikimoriOutboxSyncer
    private val refreshed = mutableListOf<Int>()

    /** Only `refreshAnime` matters here: it is the one call a rejected write asks the library for. */
    private val library = object : LibraryRepository {
        override fun observeLibrary(): Flow<List<LibraryEntry>> = flowOf(emptyList())
        override fun observeAnime(id: Int): Flow<LibraryEntry?> = flowOf(null)
        override fun observeAnimeDetails(id: Int): Flow<Anime?> = flowOf(null)
        override suspend fun refresh(): Result<Unit> = Result.success(Unit)
        override suspend fun refreshAnime(id: Int): Result<Unit> {
            refreshed += id
            return Result.success(Unit)
        }
        override suspend fun search(query: String): Result<List<Anime>> = Result.success(emptyList())
        override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> = Result.success(Unit)
        override suspend fun setEpisodes(animeId: Int, episodes: Int): Result<Unit> = Result.success(Unit)
    }

    @Before
    fun setUp() = runTest {
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .allowMainThreadQueries().build()
        val prefs = AppPreferences(
            PreferenceDataStoreFactory.create(scope = storeScope) { tmp.root.resolve("prefs.preferences_pb") },
        )
        prefs.setUserId(42)
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(shikimoriJson().asConverterFactory("application/json".toMediaType()))
            .build().create(ShikimoriApi::class.java)
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        outbox = RoomRateOutboxRepository(db.rateOutboxDao(), clock)
        syncer = ShikimoriOutboxSyncer(api, db.userRateDao(), db.rateOutboxDao(), prefs, Provider { library }, clock)
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

        assertEquals(2, syncer.replay().getOrThrow())

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

        assertEquals(1, syncer.replay().getOrThrow())

        assertEquals(listOf(20), queued().map { it.animeId })
        assertEquals(ListStatus.WATCHING, db.userRateDao().getByAnimeId(20)?.status)
    }

    @Test
    fun `a write Shikimori refuses is dropped and the title is re-read from the server`() = runTest {
        seedRate(animeId = 10, rateId = 5, episodes = 6)
        outbox.enqueue(10, RateOpKind.EPISODES, "7")
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"errors":["nope"]}"""))

        assertEquals(0, syncer.replay().getOrThrow())

        assertTrue(queued().isEmpty())
        assertEquals(listOf(10), refreshed)
    }

    @Test
    fun `a run of marks on one title costs one request`() = runTest {
        seedRate(animeId = 10, rateId = 5, episodes = 3)
        outbox.enqueue(10, RateOpKind.EPISODES, "4")
        outbox.enqueue(10, RateOpKind.EPISODES, "5")
        outbox.enqueue(10, RateOpKind.EPISODES, "6")
        server.enqueue(rateBody(5, 10, "watching", 6))

        assertEquals(1, syncer.replay().getOrThrow())

        assertEquals(1, server.requestCount)
        assertEquals("""{"user_rate":{"episodes":6}}""", server.takeRequest().body.readUtf8())
        assertTrue(queued().isEmpty())
    }

    @Test
    fun `a title added to the list offline is created rather than updated`() = runTest {
        seedRate(animeId = 10, rateId = -10, episodes = 0, status = ListStatus.PLANNED)
        outbox.enqueue(10, RateOpKind.STATUS, "planned")
        server.enqueue(rateBody(900, 10, "planned", 0))

        assertEquals(1, syncer.replay().getOrThrow())

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

        assertEquals(1, syncer.replay().getOrThrow())

        assertEquals(9, db.userRateDao().getByAnimeId(10)?.episodes)
        assertEquals(listOf(RateOpKind.EPISODES), queued().map { it.kind })
    }

    @Test
    fun `an empty queue asks Shikimori nothing`() = runTest {
        assertEquals(0, syncer.replay().getOrThrow())

        assertEquals(0, server.requestCount)
    }
}
