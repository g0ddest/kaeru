package app.kaeru.data.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.kaeru.data.library.AppPreferences
import app.kaeru.data.library.FakeShikimoriApi
import app.kaeru.data.library.ShikimoriLibraryRepository
import app.kaeru.data.library.PosterEnricher
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.toEntity
import app.kaeru.data.shikimori.oauthClient
import app.kaeru.data.shikimori.toDomain
import app.kaeru.shared.data.shikimori.TokenResponseDto
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.repository.MOBILE_REDIRECT
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import app.kaeru.data.library.RoomRateOutboxRepository
import app.kaeru.domain.sync.OutboxSyncer
import app.kaeru.domain.sync.ReplayOutcome
import app.kaeru.domain.sync.ReplayRequest

@RunWith(RobolectricTestRunner::class)
class SessionHandoffThreadTest {
    @get:Rule val tmp = TemporaryFolder()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: KaeruDatabase
    private lateinit var tokens: DataStoreTokenStore
    private lateinit var prefs: AppPreferences
    private val api = FakeShikimoriApi()
    private val clock = Clock.systemUTC()
    private val oauth = oauthClient { TokenResponseDto("new", refreshToken = "new-r") }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .allowMainThreadQueries().build()
        tokens = DataStoreTokenStore(PreferenceDataStoreFactory.create(scope = storeScope) { tmp.root.resolve("auth.preferences_pb") }, SessionFence())
        prefs = AppPreferences(PreferenceDataStoreFactory.create(scope = storeScope) { tmp.root.resolve("prefs.preferences_pb") })
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    @Test
    fun `collector preempted after validation cannot enter with A after B transition returns`() = runBlocking {
        prefs.setUserId(42)
        tokens.set(AuthTokens("a", "a-r", 9999999999, 42))
        db.animeDao().upsertAll(listOf(api.short(100).toDomain().toEntity(null)))
        val rate = api.rate(1, 100, "watching", 3).toDomain().toEntity()
        db.userRateDao().upsertAll(listOf(rate))
        val session = AccountSession(tokens, prefs, db)
        val deliverySession = spyk(session)
        val pauseNextValidation = AtomicBoolean(false)
        val validated = CountDownLatch(1)
        val releaseValidatedThread = CountDownLatch(1)
        // Instrument the existing validation boundary to model OS preemption after its last read.
        // Actual validation, persistent stores, transition, and Flow delivery remain production code.
        coEvery { deliverySession.isCurrent(any()) } coAnswers {
            val result = session.isCurrent(firstArg())
            if (result && pauseNextValidation.compareAndSet(true, false)) {
                validated.countDown()
                check(releaseValidatedThread.await(10, TimeUnit.SECONDS))
            }
            result
        }
        val library = ShikimoriLibraryRepository(
            api, db, db.animeDao(), db.userRateDao(), db.watchStateDao(), db.episodeProgressDao(),
            prefs, deliverySession, PosterEnricher(api), RoomRateOutboxRepository(db.rateOutboxDao(), clock),
            OutboxSyncer { Result.success(ReplayOutcome(0, emptySet())) }, ReplayRequest {}, Dispatchers.IO, clock,
        )
        val auth = ShikimoriAuthRepository(oauth, api, session, prefs, "cid", clock)
        val delivered = ConcurrentLinkedQueue<List<LibraryEntry>>()
        val first = CountDownLatch(1)
        val bDelivered = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val collectorScope = CoroutineScope(SupervisorJob() + worker)
        val collector = collectorScope.launch {
            library.observeLibrary().collect {
                delivered += it
                first.countDown()
                if (it.any { entry -> entry.anime.id == 200 }) bDelivered.countDown()
            }
        }
        try {
            assertTrue("Initial A must be delivered on the worker", first.await(10, TimeUnit.SECONDS))
            pauseNextValidation.set(true)
            db.userRateDao().upsertAll(listOf(rate.copy(episodes = 4)))
            assertTrue("Worker must stop after successful validation", validated.await(10, TimeUnit.SECONDS))
            api.userId = 84
            auth.exchangeCode("code-b", MOBILE_REDIRECT).getOrThrow()
            assertEquals(84L, tokens.get()!!.userId)
            api.animes[200] = api.short(200)
            library.setStatus(200, ListStatus.PLANNED).getOrThrow()
            releaseValidatedThread.countDown()
            assertTrue("Collector must recover and deliver B", bDelivered.await(10, TimeUnit.SECONDS))
            assertTrue("A first entered caller code after B activation",
                delivered.drop(1).flatten().none { it.anime.id == 100 })
        } finally {
            releaseValidatedThread.countDown()
            collector.cancelAndJoin()
            collectorScope.cancel()
            worker.close()
        }
    }
}
