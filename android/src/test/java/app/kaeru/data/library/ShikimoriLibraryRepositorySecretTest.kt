package app.kaeru.data.library

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.kaeru.data.auth.AccountSession
import app.kaeru.data.auth.AuthTokens
import app.kaeru.data.auth.InMemoryTokenStore
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.SecretTitle
import app.kaeru.domain.sync.OutboxSyncer
import app.kaeru.domain.sync.ReplayOutcome
import app.kaeru.domain.sync.ReplayRequest
import app.kaeru.domain.viewsync.ViewingSyncEvents
import app.kaeru.shared.data.network.NetworkException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * «Смотреть украдкой» in the library: nothing reaches Shikimori for a secret title, the count is
 * kept here, and turning it back into a list status sends that count once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShikimoriLibraryRepositorySecretTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val storeScope = TestScope(dispatcher)
    private lateinit var db: KaeruDatabase
    private val api = FakeShikimoriApi()
    private val events = ViewingSyncEvents()
    private lateinit var repo: ShikimoriLibraryRepository
    private val now = Instant.parse("2026-09-27T12:00:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        val store = PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "prefs.preferences_pb") }
        val prefs = AppPreferences(store)
        runTest(dispatcher) { prefs.setUserId(42) }
        val session = AccountSession(InMemoryTokenStore(AuthTokens("access", "refresh", 9999999999, 42)), prefs, db)
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        repo = ShikimoriLibraryRepository(
            api, db, db.animeDao(), db.userRateDao(), db.watchStateDao(), db.episodeProgressDao(), prefs, session,
            PosterEnricher(api), RoomRateOutboxRepository(db.rateOutboxDao(), clock),
            OutboxSyncer { Result.success(ReplayOutcome(0, emptySet())) }, ReplayRequest {}, dispatcher, clock, events,
        )
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
        scope.cancel()
    }

    /** One title in «Смотрю» with three episodes counted on Shikimori, and one outside the list. */
    private suspend fun seeded() {
        api.rates["watching"] = mutableListOf(api.rate(1, 100, "watching", 3))
        api.animes[100] = api.short(100)
        api.animes[400] = api.short(400)
        repo.refresh().getOrThrow()
        api.calls.clear()
    }

    private fun writes(): List<String> = api.calls.filter { it == "create" || it.startsWith("update:") }

    @Test
    fun `turning a listed title secret keeps its Shikimori record and writes nothing there`() = scope.runTest {
        seeded()

        repo.setStatus(100, ListStatus.SECRET).getOrThrow()

        assertEquals(emptyList<String>(), writes())
        val entry = repo.observeAnime(100).first()!!
        assertEquals(ListStatus.SECRET, entry.rate.status)
        assertEquals(3, entry.rate.episodes)
        // The Shikimori record is left exactly as it was.
        val rate = db.userRateDao().getByAnimeId(100)!!
        assertEquals(ListStatus.WATCHING, rate.status)
        assertEquals(3, rate.episodes)
        assertEquals(SecretTitle(100, true, 3, now), db.secretTitleDao().get(100)!!.toDomain())
    }

    @Test
    fun `a secret title is listed once, under its secret status only`() = scope.runTest {
        seeded()
        repo.setStatus(100, ListStatus.SECRET).getOrThrow()

        val library = repo.observeLibrary().first()

        assertEquals(listOf(ListStatus.SECRET), library.filter { it.anime.id == 100 }.map { it.rate.status })
    }

    @Test
    fun `episodes of a secret title are counted here, never on Shikimori`() = scope.runTest {
        seeded()
        repo.setStatus(100, ListStatus.SECRET).getOrThrow()

        repo.setEpisodes(100, 5).getOrThrow()
        assertEquals(5, repo.observeAnime(100).first()!!.rate.episodes)
        // An un-mark lowers it the way it would lower the Shikimori count.
        repo.setEpisodes(100, 1).getOrThrow()
        assertEquals(1, repo.observeAnime(100).first()!!.rate.episodes)

        assertEquals(emptyList<String>(), writes())
        assertEquals(3, db.userRateDao().getByAnimeId(100)!!.episodes)
    }

    @Test
    fun `a title outside the list turns secret with its card cached and no rate created`() = scope.runTest {
        seeded()
        api.animes[500] = api.short(500)

        repo.setStatus(500, ListStatus.SECRET).getOrThrow()

        assertEquals(emptyList<String>(), writes())
        assertTrue(api.creates.isEmpty())
        assertNull(db.userRateDao().getByAnimeId(500))
        val entry = repo.observeAnime(500).first()!!
        assertEquals(ListStatus.SECRET, entry.rate.status)
        assertEquals(0, entry.rate.episodes)
        assertEquals("Имя 500", entry.anime.nameRu)
    }

    @Test
    fun `without a card and without a network nothing turns secret`() = scope.runTest {
        seeded()
        api.beforeCall = { if (it.startsWith("animes:")) throw NetworkException() }

        assertTrue(repo.setStatus(600, ListStatus.SECRET).isFailure)
        assertNull(db.secretTitleDao().get(600))
    }

    @Test
    fun `back from secret the chosen status is set and the count sent once`() = scope.runTest {
        seeded()
        repo.setStatus(100, ListStatus.SECRET).getOrThrow()
        repo.setEpisodes(100, 7).getOrThrow()

        repo.setStatus(100, ListStatus.WATCHING).getOrThrow()

        assertEquals(listOf(FakeShikimoriApi.Update(1, "watching", null), FakeShikimoriApi.Update(1, null, 7)), api.updates)
        val entry = repo.observeAnime(100).first()!!
        assertEquals(ListStatus.WATCHING, entry.rate.status)
        assertEquals(7, entry.rate.episodes)
        assertFalse(db.secretTitleDao().get(100)!!.isOn)
    }

    @Test
    fun `back from secret with nothing new watched sends only the status`() = scope.runTest {
        seeded()
        repo.setStatus(100, ListStatus.SECRET).getOrThrow()
        repo.setEpisodes(100, 2).getOrThrow()

        repo.setStatus(100, ListStatus.ON_HOLD).getOrThrow()

        assertEquals(listOf(FakeShikimoriApi.Update(1, "on_hold", null)), api.updates)
        assertEquals(3, repo.observeAnime(100).first()!!.rate.episodes)
    }

    @Test
    fun `a title that was only ever secret gets a rate and its count when it joins the list`() = scope.runTest {
        seeded()
        repo.setStatus(400, ListStatus.SECRET).getOrThrow()
        repo.setEpisodes(400, 4).getOrThrow()

        repo.setStatus(400, ListStatus.COMPLETED).getOrThrow()

        assertEquals(400, api.creates.single().animeId)
        assertEquals(4, api.updates.single().episodes)
        assertEquals(ListStatus.COMPLETED, repo.observeAnime(400).first()!!.rate.status)
    }

    @Test
    fun `a refresh from Shikimori leaves secret titles secret`() = scope.runTest {
        seeded()
        repo.setStatus(100, ListStatus.SECRET).getOrThrow()
        repo.setStatus(400, ListStatus.SECRET).getOrThrow()
        repo.setEpisodes(100, 6).getOrThrow()

        repo.refresh().getOrThrow()

        val library = repo.observeLibrary().first().associateBy { it.anime.id }
        assertEquals(ListStatus.SECRET, library.getValue(100).rate.status)
        assertEquals(6, library.getValue(100).rate.episodes)
        assertEquals(ListStatus.SECRET, library.getValue(400).rate.status)
        // The secret-only title's card is refreshed with the rest of the list.
        assertTrue(api.animeBatches.last().contains(400))
    }

    @Test
    fun `every secret change is told to viewing sync`() = scope.runTest {
        seeded()
        repo.setStatus(100, ListStatus.SECRET).getOrThrow()
        repo.setEpisodes(100, 4).getOrThrow()
        repo.setStatus(100, ListStatus.WATCHING).getOrThrow()

        val told = events.events.take(3).toList()
            .map { (it as ViewingSyncEvents.Event.Secret).secret }
        assertEquals(listOf(true to 3, true to 4, false to 4), told.map { it.on to it.watched })
    }

    @Test
    fun `signing out forgets what was secret`() = scope.runTest {
        seeded()
        repo.setStatus(100, ListStatus.SECRET).getOrThrow()

        db.clearAccountData()

        assertTrue(db.secretTitleDao().all().isEmpty())
    }
}
