package app.kaeru.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.kaeru.data.library.AppPreferences
import app.kaeru.data.library.FakeShikimoriApi
import app.kaeru.data.library.ShikimoriLibraryRepository
import app.kaeru.domain.sync.OutboxSyncer
import app.kaeru.data.library.PosterEnricher
import app.kaeru.data.library.RoomRateOutboxRepository
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.WatchStateEntity
import app.kaeru.data.local.toEntity
import app.kaeru.data.shikimori.ShikimoriOAuthApi
import app.kaeru.data.shikimori.AnimeDetailsDto
import app.kaeru.data.shikimori.ImageDto
import app.kaeru.data.shikimori.TokenResponseDto
import app.kaeru.data.shikimori.toDomain
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.repository.MOBILE_REDIRECT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import app.kaeru.domain.sync.ReplayOutcome
import app.kaeru.domain.sync.ReplayRequest

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccountSessionIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val storeScope = TestScope(dispatcher)
    private lateinit var db: KaeruDatabase
    private lateinit var prefsStore: DataStore<Preferences>
    private lateinit var authStore: DataStore<Preferences>
    private lateinit var prefs: AppPreferences
    private lateinit var tokens: DataStoreTokenStore
    private val fence = SessionFence()
    private lateinit var auth: ShikimoriAuthRepository
    private lateinit var library: ShikimoriLibraryRepository
    private lateinit var session: AccountSession
    private val api = FakeShikimoriApi()
    private val now = Instant.parse("2026-09-12T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private var exchange: suspend () -> TokenResponseDto = { TokenResponseDto("new", refreshToken = "new-refresh") }
    private val oauth = object : ShikimoriOAuthApi {
        override suspend fun token(grantType: String, clientId: String,
            code: String?, redirectUri: String?, refreshToken: String?): TokenResponseDto = exchange()
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        prefsStore = PreferenceDataStoreFactory.create(scope = storeScope) { tmp.root.resolve("prefs.preferences_pb") }
        authStore = PreferenceDataStoreFactory.create(scope = storeScope) { tmp.root.resolve("auth.preferences_pb") }
        restartWrappers()
    }

    private fun restartWrappers() {
        prefs = AppPreferences(prefsStore)
        tokens = DataStoreTokenStore(authStore, fence)
        session = AccountSession(tokens, prefs, db)
        auth = ShikimoriAuthRepository(oauth, api, session, prefs, "cid", clock)
        library = ShikimoriLibraryRepository(api, db, db.animeDao(), db.userRateDao(), db.watchStateDao(), db.episodeProgressDao(), prefs, session, PosterEnricher(api), RoomRateOutboxRepository(db.rateOutboxDao(), clock), OutboxSyncer { Result.success(ReplayOutcome(0, emptySet())) }, ReplayRequest {}, dispatcher, clock)
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
        scope.cancel()
    }

    private suspend fun seedA(active: Boolean = true) {
        prefs.setUserId(42)
        prefs.setLastFullSync(now)
        prefsStore.edit { it[floatPreferencesKey("watched_threshold")] = 0.8f }
        api.animes[100] = api.short(100)
        api.details[100] = AnimeDetailsDto(100, "Name", "Имя", ImageDto("/o.jpg", "/p.jpg"), "7.0", "released", 12, 12, "2026-01-01")
        api.rates["watching"] = mutableListOf(api.rate(1, 100, "watching", 3))
        db.animeDao().upsertAll(listOf(api.short(100).toDomain().toEntity(null)))
        db.userRateDao().upsertAll(listOf(api.rate(1, 100, "watching", 3).toDomain().toEntity()))
        db.watchStateDao().upsert(WatchStateEntity(100, 4, 123, 1000, 7, 1, now))
        tokens.set(if (active) AuthTokens("old", "old-refresh", now.epochSecond + 3600, 42) else null)
    }

    private suspend fun assertACache() {
        assertEquals(42L, prefs.userId())
        assertEquals(now, prefs.lastFullSync())
        assertEquals(3, db.userRateDao().getByAnimeId(100)?.episodes)
        assertEquals(123L, db.watchStateDao().getByAnimeId(100)?.positionMs)
    }

    private suspend fun assertAccountRowsEmpty() {
        assertTrue(db.userRateDao().observeAll().first().isEmpty())
        assertTrue(db.watchStateDao().observeAll().first().isEmpty())
        assertNull(prefs.lastFullSync())
        assertNotNull(db.animeDao().getById(100))
        assertEquals(0.8f, prefs.watchedThreshold.first())
    }

    @Test
    fun `logout removes account data but retains shared metadata and global preference`() = scope.runTest {
        seedA()
        auth.logout()
        assertNull(tokens.get())
        assertNull(prefs.userId())
        assertAccountRowsEmpty()
        assertFalse(auth.isLoggedIn.first())
        auth.logout()
        assertAccountRowsEmpty()
    }

    @Test
    fun `tokenless same account reauthentication verifies identity before publishing tokens and retains cache`() = scope.runTest {
        seedA(active = false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.beforeCall = { if (it == "whoami") { entered.complete(Unit); release.await() } }
        auth.isLoggedIn.test {
            assertFalse(awaitItem())
            val login = async { auth.exchangeCode("code", MOBILE_REDIRECT) }
            runCurrent()
            assertTrue("Identity must be requested before login completes", entered.isCompleted)
            assertFalse(login.isCompleted)
            assertNull(tokens.get())
            expectNoEvents()
            release.complete(Unit)
            login.await().getOrThrow()
            assertTrue(awaitItem())
            assertACache()
            assertEquals(listOf("Bearer new"), api.identityBearers)
        }
    }

    @Test
    fun `different account login clears A before B login is observable and all rate calls use B`() = scope.runTest {
        seedA(active = false)
        api.userId = 84
        auth.isLoggedIn.test {
            assertFalse(awaitItem())
            auth.exchangeCode("code-b", MOBILE_REDIRECT).getOrThrow()
            assertTrue(awaitItem())
            assertEquals(84L, prefs.userId())
            assertAccountRowsEmpty()
        }
        api.rates.clear()
        library.refresh().getOrThrow()
        assertEquals(List(6) { 84L }, api.requestedUserIds)
        library.setStatus(100, ListStatus.PLANNED).getOrThrow()
        assertEquals(84L, api.creates.single().userRate.userId)
    }

    @Test
    fun `active account switch also clears prior cache`() = scope.runTest {
        seedA()
        api.userId = 84
        auth.exchangeCode("code-b", MOBILE_REDIRECT).getOrThrow()
        assertEquals(84L, prefs.userId())
        assertAccountRowsEmpty()
    }

    @Test
    fun `failed code exchange preserves current account and tokens`() = scope.runTest {
        seedA()
        val old = tokens.get()
        exchange = { throw IOException("bad code") }
        assertTrue(auth.exchangeCode("bad", MOBILE_REDIRECT).isFailure)
        assertEquals(old, tokens.get())
        assertACache()
    }

    @Test
    fun `failed identity lookup never activates new tokens or mislabels cache`() = scope.runTest {
        seedA()
        val old = tokens.get()
        api.beforeCall = { if (it == "whoami") throw IOException("identity rejected") }
        assertTrue(auth.exchangeCode("bad-identity", MOBILE_REDIRECT).isFailure)
        assertEquals(old, tokens.get())
        assertACache()
    }

    @Test
    fun `login cancellation propagates without activating tokens`() = scope.runTest {
        seedA(active = false)
        exchange = { throw CancellationException("cancelled") }
        try {
            auth.exchangeCode("cancelled", MOBILE_REDIRECT)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertNull(tokens.get())
            assertACache()
        }
    }

    @Test
    fun `restored session works while tokenless restart still verifies identity before retaining cache`() = scope.runTest {
        seedA()
        restartWrappers()
        assertTrue(auth.isLoggedIn.first())
        library.setEpisodes(100, 4).getOrThrow()
        assertEquals(4, db.userRateDao().getByAnimeId(100)!!.episodes)
        tokens.set(null)
        restartWrappers()
        assertFalse(auth.isLoggedIn.first())
        auth.exchangeCode("code-a", MOBILE_REDIRECT).getOrThrow()
        assertEquals(listOf("whoami"), api.calls.filter { it == "whoami" })
        assertEquals(4, db.userRateDao().getByAnimeId(100)!!.episodes)
        assertEquals(now, prefs.lastFullSync())
    }

    @Test
    fun `logout waits for a suspended write then removes its result`() = scope.runTest {
        seedA()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.beforeCall = { if (it == "update:1") { entered.complete(Unit); release.await() } }
        val write = async { library.setEpisodes(100, 8) }
        entered.await()
        val logout = async { auth.logout() }
        runCurrent()
        assertFalse("Logout must join the account write boundary", logout.isCompleted)
        release.complete(Unit)
        write.await().getOrThrow()
        logout.await()
        assertAccountRowsEmpty()
    }

    @Test
    fun `old write queued behind account switch cannot run as B`() = scope.runTest {
        seedA()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        exchange = { entered.complete(Unit); release.await(); TokenResponseDto("b", refreshToken = "br") }
        val login = async { auth.exchangeCode("code-b", MOBILE_REDIRECT) }
        entered.await()
        val write = async { library.setStatus(100, ListStatus.COMPLETED) }
        runCurrent()
        assertFalse(write.isCompleted)
        assertTrue(api.updates.isEmpty())
        api.userId = 84
        release.complete(Unit)
        login.await().getOrThrow()
        assertTrue("Old intent must not mutate the new account", write.await().isFailure)
        assertAccountRowsEmpty()
        assertTrue(api.updates.isEmpty())
    }

    @Test
    fun `cancelled write holder and waiter release boundary for logout and next login`() = scope.runTest {
        seedA()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.beforeCall = { if (it == "update:1") { entered.complete(Unit); release.await() } }
        val holder = async { library.setEpisodes(100, 8) }
        entered.await()
        val waiter = async { library.setStatus(100, ListStatus.COMPLETED) }
        runCurrent()
        waiter.cancelAndJoin()
        holder.cancelAndJoin()
        api.beforeCall = {}
        auth.logout()
        assertAccountRowsEmpty()
        api.userId = 84
        auth.exchangeCode("code-b", MOBILE_REDIRECT).getOrThrow()
        library.setStatus(100, ListStatus.PLANNED).getOrThrow()
        assertEquals(84L, api.creates.single().userRate.userId)
    }

    @Test
    fun `failed second table clear rolls back Room and cannot publish B`() = scope.runTest {
        seedA()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_watch_delete BEFORE DELETE ON watch_state BEGIN SELECT RAISE(ABORT, 'forced failure'); END")
        api.userId = 84
        assertTrue(auth.exchangeCode("code-b", MOBILE_REDIRECT).isFailure)
        assertNull(tokens.get())
        assertFalse(auth.isLoggedIn.first())
        assertACache()
    }

    @Test
    fun `unbound or mismatched restored tokens cannot expose or mutate cached account`() = scope.runTest {
        seedA()
        for (identity in listOf(null, 84L)) {
            tokens.set(AuthTokens("wrong", "refresh", now.epochSecond + 3600, identity))
            restartWrappers()
            assertFalse(auth.isLoggedIn.first())
            assertTrue(library.observeLibrary().first().isEmpty())
            assertTrue(library.setEpisodes(100, 10).isFailure)
            assertTrue(library.refresh().isFailure)
            assertTrue(api.calls.isEmpty())
            assertACache()
        }
    }

    @Test
    fun `cancelled identity verification leaves old account intact and releases session lock`() = scope.runTest {
        seedA()
        val old = tokens.get()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.beforeCall = { if (it == "whoami") { entered.complete(Unit); release.await() } }
        val login = async { auth.exchangeCode("cancelled", MOBILE_REDIRECT) }
        entered.await()
        login.cancelAndJoin()
        assertEquals(old, tokens.get())
        assertACache()
        api.beforeCall = {}
        auth.logout()
        assertAccountRowsEmpty()
    }

    @Test
    fun `each account write queued during logout is rejected after it returns`() = scope.runTest {
        seedA()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gatedPrefs = AppPreferences(object : DataStore<Preferences> by prefsStore {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                entered.complete(Unit)
                release.await()
                return prefsStore.updateData(transform)
            }
        })
        val boundary = AccountSession(tokens, gatedPrefs, db)
        val gatedAuth = ShikimoriAuthRepository(oauth, api, boundary, gatedPrefs, "cid", clock)
        val gatedLibrary = ShikimoriLibraryRepository(api, db, db.animeDao(), db.userRateDao(), db.watchStateDao(), db.episodeProgressDao(), gatedPrefs, boundary, PosterEnricher(api), RoomRateOutboxRepository(db.rateOutboxDao(), clock), OutboxSyncer { Result.success(ReplayOutcome(0, emptySet())) }, ReplayRequest {}, dispatcher, clock)
        val logout = async { gatedAuth.logout() }
        entered.await()
        val writes = listOf(
            async { gatedLibrary.refresh() },
            async { gatedLibrary.refreshAnime(100) },
            async { gatedLibrary.setStatus(100, ListStatus.PLANNED) },
            async { gatedLibrary.setEpisodes(100, 9) },
        )
        runCurrent()
        assertTrue(writes.none { it.isCompleted })
        release.complete(Unit)
        logout.await()
        writes.forEach { assertTrue(it.await().isFailure) }
        assertTrue(api.calls.isEmpty())
        assertAccountRowsEmpty()
    }

    @Test
    fun `switch waits for refresh holder and rejects old mutation queued behind transition`() = scope.runTest {
        seedA()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.beforeCall = { if (it == "rates:watching") { entered.complete(Unit); release.await() } }
        val refresh = async { library.refresh() }
        entered.await()
        exchange = { api.userId = 84; TokenResponseDto("b", refreshToken = "br") }
        val login = async { auth.exchangeCode("code-b", MOBILE_REDIRECT) }
        runCurrent()
        val write = async { library.setStatus(100, ListStatus.COMPLETED) }
        runCurrent()
        assertFalse(login.isCompleted)
        assertFalse(write.isCompleted)
        release.complete(Unit)
        refresh.await().getOrThrow()
        login.await().getOrThrow()
        assertTrue(write.await().isFailure)
        assertEquals(84L, prefs.userId())
        assertAccountRowsEmpty()
        assertTrue(api.updates.isEmpty())
    }

    @Test
    fun `refreshAnime holder must finish before logout returns`() = scope.runTest {
        seedA()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.beforeCall = { if (it == "screenshots:100") { entered.complete(Unit); release.await() } }
        val details = async { library.refreshAnime(100) }
        entered.await()
        val logout = async { auth.logout() }
        runCurrent()
        assertFalse(logout.isCompleted)
        release.complete(Unit)
        details.await().getOrThrow()
        logout.await()
        assertAccountRowsEmpty()
        assertEquals(now, db.animeDao().getById(100)!!.detailsFetchedAt)
    }

    @Test
    fun `cancelled preparation cannot publish B and a restart recovers with verified identity`() = scope.runTest {
        seedA()
        api.userId = 84
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gatedPrefs = AppPreferences(object : DataStore<Preferences> by prefsStore {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                entered.complete(Unit)
                release.await()
                return prefsStore.updateData(transform)
            }
        })
        val boundary = AccountSession(tokens, gatedPrefs, db)
        val gatedAuth = ShikimoriAuthRepository(oauth, api, boundary, gatedPrefs, "cid", clock)
        val login = async { gatedAuth.exchangeCode("code-b", MOBILE_REDIRECT) }
        entered.await()
        assertNull(tokens.get())
        assertFalse(gatedAuth.isLoggedIn.first())
        assertEquals(42L, prefs.userId())
        assertTrue(db.userRateDao().observeAll().first().isEmpty())
        login.cancelAndJoin()
        assertNull(tokens.get())
        restartWrappers()
        auth.exchangeCode("retry-b", MOBILE_REDIRECT).getOrThrow()
        assertEquals(84L, prefs.userId())
        assertEquals(84L, tokens.get()!!.userId)
        assertTrue(auth.isLoggedIn.first())
        assertAccountRowsEmpty()
    }

    @Test
    fun `live library subscription is empty after logout and fresh for B`() = scope.runTest {
        seedA()
        library.observeLibrary().test {
            assertEquals(listOf(100), awaitItem().map { it.anime.id })
            auth.logout()
            assertTrue(awaitItem().isEmpty())
            api.userId = 84
            auth.exchangeCode("code-b", MOBILE_REDIRECT).getOrThrow()
            assertTrue(awaitItem().isEmpty())
            api.rates.clear()
            api.animes[200] = api.short(200)
            library.setStatus(200, ListStatus.PLANNED).getOrThrow()
            var next = awaitItem()
            while (next.isEmpty()) next = awaitItem()
            assertEquals(listOf(200), next.map { it.anime.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `logout SQL failure leaves tokens logged out and repeated logout finishes clearing`() = scope.runTest {
        seedA()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_watch_delete BEFORE DELETE ON watch_state BEGIN SELECT RAISE(ABORT, 'forced failure'); END")
        try {
            auth.logout()
            fail("The SQL failure must propagate")
        } catch (_: android.database.sqlite.SQLiteException) {
            assertNull(tokens.get())
            assertACache()
        }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_watch_delete")
        auth.logout()
        assertAccountRowsEmpty()
        assertNull(prefs.userId())
    }

    @Test
    fun `slow collector cannot receive queued A snapshot after logout and B login`() = scope.runTest {
        assertSlowCollectorSwitch(logoutFirst = true)
    }

    @Test
    fun `slow collector cannot receive queued A snapshot after direct active account switch`() = scope.runTest {
        assertSlowCollectorSwitch(logoutFirst = false)
    }

    @Test
    fun `delayed identity flows cannot deliver queued A snapshot after B is active`() = scope.runTest {
        val identityUpdates = CompletableDeferred<Unit>()
        val delayedTokens = object : TokenStore by tokens {
            override val tokens = this@AccountSessionIntegrationTest.tokens.tokens.delayUpdates(identityUpdates)
        }
        val delayedPrefs = AppPreferences(object : DataStore<Preferences> by prefsStore {
            override val data = prefsStore.data.delayUpdates(identityUpdates)
        })
        session = AccountSession(delayedTokens, delayedPrefs, db)
        auth = ShikimoriAuthRepository(oauth, api, session, delayedPrefs, "cid", clock)
        library = ShikimoriLibraryRepository(api, db, db.animeDao(), db.userRateDao(), db.watchStateDao(), db.episodeProgressDao(), delayedPrefs, session, PosterEnricher(api), RoomRateOutboxRepository(db.rateOutboxDao(), clock), OutboxSyncer { Result.success(ReplayOutcome(0, emptySet())) }, ReplayRequest {}, dispatcher, clock)
        assertSlowCollectorSwitch(logoutFirst = false, releaseIdentity = identityUpdates)
    }

    @Test
    fun `delivery validation suspended across A B A rejects the old generation`() = scope.runTest {
        seedA()
        val validationStarted = CompletableDeferred<Unit>()
        val releaseValidation = CompletableDeferred<Unit>()
        var delayNextRead = false
        val delayedReadStore = object : TokenStore by tokens {
            override suspend fun get(): AuthTokens? {
                val read = this@AccountSessionIntegrationTest.tokens.get()
                if (delayNextRead) {
                    delayNextRead = false
                    validationStarted.complete(Unit)
                    releaseValidation.await()
                }
                return read
            }
        }
        session = AccountSession(delayedReadStore, prefs, db)
        auth = ShikimoriAuthRepository(oauth, api, session, prefs, "cid", clock)
        library = ShikimoriLibraryRepository(api, db, db.animeDao(), db.userRateDao(), db.watchStateDao(), db.episodeProgressDao(), prefs, session, PosterEnricher(api), RoomRateOutboxRepository(db.rateOutboxDao(), clock), OutboxSyncer { Result.success(ReplayOutcome(0, emptySet())) }, ReplayRequest {}, dispatcher, clock)
        val delivered = mutableListOf<List<LibraryEntry>>()
        val first = CompletableDeferred<Unit>()
        val collector = backgroundScope.launch {
            library.observeLibrary().collect { delivered += it; first.complete(Unit) }
        }
        first.await()
        delayNextRead = true
        val oldRate = db.userRateDao().getByAnimeId(100)!!
        db.userRateDao().upsertAll(listOf(oldRate.copy(episodes = 4)))
        runCurrent()
        assertTrue("Each pending delivery must validate the current session", validationStarted.isCompleted)

        api.userId = 84
        auth.exchangeCode("code-b", MOBILE_REDIRECT).getOrThrow()
        api.userId = 42
        auth.exchangeCode("new-code-a", MOBILE_REDIRECT).getOrThrow()
        api.rates.clear()
        library.setStatus(100, ListStatus.PLANNED).getOrThrow()
        val deliveredBeforeRelease = delivered.size
        releaseValidation.complete(Unit)
        runCurrent()
        assertTrue("Matching account IDs do not validate an earlier session generation",
            delivered.drop(deliveredBeforeRelease).flatten().none { it.rate.episodes == 4 })
        assertEquals(0, delivered.last().single().rate.episodes)
        collector.cancelAndJoin()
    }

    @Test
    fun `library observation recovers after failed same account transition`() = scope.runTest {
        seedA()
        val delivered = mutableListOf<List<LibraryEntry>>()
        val first = CompletableDeferred<Unit>()
        val collector = backgroundScope.launch {
            library.observeLibrary().collect { delivered += it; first.complete(Unit) }
        }
        first.await()
        exchange = { throw IOException("bad code") }
        assertTrue(auth.exchangeCode("bad-code", MOBILE_REDIRECT).isFailure)
        library.setEpisodes(100, 7).getOrThrow()
        runCurrent()
        assertEquals(7, delivered.last().single().rate.episodes)
        collector.cancelAndJoin()
    }

    @Test
    fun `authenticator CAS clear during preference validation cannot deliver the captured account`() = scope.runTest {
        assertTokenClearDuringValidation(conditional = true)
    }

    @Test
    fun `unconditional token clear during preference validation cannot deliver the captured account`() = scope.runTest {
        assertTokenClearDuringValidation(conditional = false)
    }

    private suspend fun TestScope.assertTokenClearDuringValidation(conditional: Boolean) {
        seedA()
        val validating = CompletableDeferred<Unit>()
        val releaseValidation = CompletableDeferred<Unit>()
        var delayNextRead = false
        val delayedPrefs = AppPreferences(object : DataStore<Preferences> by prefsStore {
            override val data: Flow<Preferences> = flow {
                val read = prefsStore.data.first()
                if (delayNextRead) {
                    delayNextRead = false
                    validating.complete(Unit)
                    releaseValidation.await()
                }
                emit(read)
            }
        })
        session = AccountSession(tokens, delayedPrefs, db)
        auth = ShikimoriAuthRepository(oauth, api, session, delayedPrefs, "cid", clock)
        library = ShikimoriLibraryRepository(api, db, db.animeDao(), db.userRateDao(), db.watchStateDao(), db.episodeProgressDao(), delayedPrefs, session, PosterEnricher(api), RoomRateOutboxRepository(db.rateOutboxDao(), clock), OutboxSyncer { Result.success(ReplayOutcome(0, emptySet())) }, ReplayRequest {}, dispatcher, clock)
        val delivered = mutableListOf<List<LibraryEntry>>()
        val first = CompletableDeferred<Unit>()
        val collector = backgroundScope.launch {
            library.observeLibrary().collect { delivered += it; first.complete(Unit) }
        }
        first.await()
        delayNextRead = true
        val oldRate = db.userRateDao().getByAnimeId(100)!!
        db.userRateDao().upsertAll(listOf(oldRate.copy(episodes = 4)))
        validating.await()
        // This is the exact TokenStore mutation performed by a failed TokenAuthenticator refresh.
        val refreshSnapshot = tokens.snapshot()
        if (conditional) assertTrue(tokens.compareAndSet(refreshSnapshot, null)) else tokens.set(null)
        assertFalse(auth.isLoggedIn.first())
        val beforeRelease = delivered.size
        releaseValidation.complete(Unit)
        runCurrent()
        assertTrue("A validation that captured tokens before token clear must not deliver A",
            delivered.drop(beforeRelease).flatten().none { it.anime.id == 100 })
        assertTrue(delivered.last().isEmpty())
        assertEquals(4, db.userRateDao().getByAnimeId(100)!!.episodes)
        collector.cancelAndJoin()
    }

    private suspend fun TestScope.assertSlowCollectorSwitch(
        logoutFirst: Boolean,
        releaseIdentity: CompletableDeferred<Unit>? = null,
    ) {
        seedA()
        val firstDelivered = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val delivered = mutableListOf<List<LibraryEntry>>()
        val collector = backgroundScope.launch {
            library.observeLibrary().collect { entries ->
                delivered += entries
                if (delivered.size == 1) {
                    firstDelivered.complete(Unit)
                    releaseCollector.await()
                }
            }
        }
        firstDelivered.await()
        assertEquals(listOf(100), delivered.single().map { it.anime.id })
        library.setEpisodes(100, 4).getOrThrow()
        runCurrent() // Drain Room/Flow producers while the consumer remains suspended.
        assertEquals(1, delivered.size)

        if (logoutFirst) auth.logout()
        api.userId = 84
        auth.exchangeCode("code-b", MOBILE_REDIRECT).getOrThrow()
        assertEquals(84L, tokens.get()!!.userId)
        assertEquals(84L, prefs.userId())
        api.rates.clear()
        api.animes[200] = api.short(200)
        library.setStatus(200, ListStatus.PLANNED).getOrThrow()
        runCurrent()
        releaseCollector.complete(Unit)
        runCurrent()
        assertTrue("No queued A value may be delivered after B activation: $delivered",
            delivered.drop(1).flatten().none { it.anime.id == 100 })

        releaseIdentity?.complete(Unit)
        runCurrent()
        assertTrue("The live subscription must recover and deliver B", delivered.last().any { it.anime.id == 200 })
        collector.cancelAndJoin()
    }

    /** Delay notifications only; get/first and persisted writes still see the real current value. */
    private fun <T> Flow<T>.delayUpdates(release: CompletableDeferred<Unit>): Flow<T> = flow {
        var initial = true
        collect { value ->
            if (!initial) release.await()
            initial = false
            emit(value)
        }
    }
}
