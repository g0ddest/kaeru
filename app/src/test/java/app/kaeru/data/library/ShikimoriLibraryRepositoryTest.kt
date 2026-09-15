package app.kaeru.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.kaeru.data.auth.AccountSession
import app.kaeru.data.auth.AuthTokens
import app.kaeru.data.auth.InMemoryTokenStore
import app.kaeru.data.local.EpisodeProgressEntity
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.WatchStateEntity
import app.kaeru.data.shikimori.AnimeDetailsDto
import app.kaeru.data.shikimori.ImageDto
import app.kaeru.data.shikimori.ScreenshotDto
import app.kaeru.data.shikimori.StudioDto
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.ListStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShikimoriLibraryRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val storeScope = TestScope(dispatcher)
    private lateinit var db: KaeruDatabase
    private lateinit var prefsStore: DataStore<Preferences>
    private lateinit var prefs: AppPreferences
    private val api = FakeShikimoriApi()
    private lateinit var repo: ShikimoriLibraryRepository
    private lateinit var session: AccountSession
    private val now = Instant.parse("2026-09-12T12:00:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        prefsStore = PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "prefs.preferences_pb") }
        prefs = AppPreferences(prefsStore)
        runTest(dispatcher) { prefs.setUserId(42) }
        session = AccountSession(InMemoryTokenStore(AuthTokens("access", "refresh", 9999999999, 42)), prefs, db)
        repo = repositoryAt(now)
    }

    private fun repositoryAt(at: Instant) = ShikimoriLibraryRepository(
        api, db.animeDao(), db.userRateDao(), db.watchStateDao(), db.episodeProgressDao(), prefs, session,
        PosterEnricher(api), dispatcher, Clock.fixed(at, ZoneOffset.UTC),
    )

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
        scope.cancel()
    }

    private fun seedWatching() {
        api.rates["watching"] = mutableListOf(api.rate(1, 100, "watching", 3), api.rate(2, 200, "watching", 5))
        api.rates["planned"] = mutableListOf(api.rate(3, 300, "planned", 0))
        api.animes[100] = api.short(100, "ongoing", episodes = 0, aired = 4)
        api.animes[200] = api.short(200)
        api.animes[300] = api.short(300)
        api.details[100] = AnimeDetailsDto(
            100, "Name 100", "Имя 100", ImageDto("/o.jpg", "/p.jpg"), "7.0", "ongoing", 0, 4, "2026-01-01",
            description = "desc", nextEpisodeAt = "2026-09-14T17:00:00.000+03:00",
            studios = listOf(StudioDto("MAPPA")), screenshots = listOf(ScreenshotDto("/ignored.jpg")),
        )
        api.screenshots[100] = listOf(ScreenshotDto("/s.jpg", "/sp.jpg"))
    }

    @Test
    fun `posters hidden by REST are replaced from GraphQL`() = scope.runTest {
        api.rates["watching"] = mutableListOf(api.rate(1, 500, "watching", 1))
        api.animes[500] = api.short(500).copy(image = ImageDto("/assets/globals/missing_original.jpg", "/assets/globals/missing_preview.jpg"))
        api.posters[500] = "https://shikimori.io/uploads/poster/animes/500/main-abc.webp"
        repo.refresh().getOrThrow()
        val entry = repo.observeLibrary().first().single()
        assertEquals("https://shikimori.io/uploads/poster/animes/500/main-abc.webp", entry.anime.posterUrl)
        assertEquals(1, api.graphqlQueries.size)
    }

    @Test
    fun `refresh loads rates animes and separate details for ongoing watching`() = scope.runTest {
        seedWatching()
        repo.refresh().getOrThrow()
        val lib = repo.observeLibrary().first().sortedBy { it.anime.id }
        assertEquals(listOf(100, 200, 300), lib.map { it.anime.id })
        assertEquals(3, lib[0].rate.episodes)
        assertEquals(ListStatus.PLANNED, lib[2].rate.status)
        assertEquals("MAPPA", lib[0].anime.studio)
        assertEquals("desc", lib[0].anime.description)
        assertEquals(listOf("https://shikimori.io/s.jpg"), lib[0].anime.screenshotUrls)
        assertEquals(Instant.parse("2026-09-14T14:00:00Z"), lib[0].anime.nextEpisodeAt)
        assertNull(lib[1].anime.studio)
        assertEquals(listOf("anime:100"), api.calls.filter { it.startsWith("anime:") })
        assertEquals(listOf("screenshots:100"), api.calls.filter { it.startsWith("screenshots:") })
        assertEquals(now, prefs.lastFullSync())
        assertEquals(now, db.animeDao().getById(100)!!.detailsFetchedAt)
    }

    @Test
    fun `refresh includes all six statuses and paginates large lists in API sized chunks`() = scope.runTest {
        api.rates["watching"] = (1..1001).map { api.rate(it.toLong(), it, "watching", 2) }.toMutableList()
        listOf("planned", "completed", "on_hold", "dropped", "rewatching").forEachIndexed { index, status ->
            api.rates[status] = mutableListOf(api.rate(2000L + index, 2000 + index, status, 1))
        }
        api.rates.values.flatten().forEach { api.animes[it.targetId] = api.short(it.targetId) }
        repo.refresh().getOrThrow()
        val lib = repo.observeLibrary().first()
        assertEquals(1006, lib.size)
        assertEquals(setOf(ListStatus.WATCHING, ListStatus.PLANNED, ListStatus.COMPLETED, ListStatus.ON_HOLD, ListStatus.DROPPED, ListStatus.REWATCHING), lib.map { it.rate.status }.toSet())
        assertEquals(listOf(1, 2), api.ratePages.filter { it.first == "watching" }.map { it.second })
        assertEquals(1006, api.animeBatches.flatten().distinct().size)
        assertTrue(api.animeBatches.all { it.size <= 50 })
    }

    @Test
    fun `second refresh keeps cached details while updating short fields and reuses user id`() = scope.runTest {
        seedWatching()
        repo.refresh().getOrThrow()
        api.animes[100] = api.short(100, "ongoing", episodes = 0, aired = 5)
        repo.refresh().getOrThrow()
        val cached = db.animeDao().getById(100)!!
        assertEquals(5, cached.episodesAired)
        assertEquals("MAPPA", cached.studio)
        assertEquals("desc", cached.description)
        assertEquals(listOf("https://shikimori.io/s.jpg"), cached.screenshots)
        assertEquals(Instant.parse("2026-09-14T14:00:00Z"), cached.nextEpisodeAt)
        assertEquals(now, cached.detailsFetchedAt)
        assertEquals(1, api.calls.count { it == "anime:100" })
        assertEquals(1, api.calls.count { it == "screenshots:100" })
        assertEquals(0, api.calls.count { it == "whoami" })
    }

    @Test
    fun `details expire at six hours and are loaded for rewatching but not planned ongoing`() = scope.runTest {
        seedWatching()
        api.rates["watching"]!!.removeAt(0)
        api.rates["rewatching"] = mutableListOf(api.rate(1, 100, "rewatching", 3))
        api.animes[300] = api.short(300, "ongoing")
        repo.refresh().getOrThrow()
        repositoryAt(now.plusSeconds(21599)).refresh().getOrThrow()
        assertEquals(1, api.calls.count { it == "anime:100" })
        repositoryAt(now.plusSeconds(21600)).refresh().getOrThrow()
        assertEquals(2, api.calls.count { it == "anime:100" })
        assertFalse(api.calls.contains("anime:300"))
        assertEquals(now.plusSeconds(21600), db.animeDao().getById(100)!!.detailsFetchedAt)
    }

    @Test
    fun `refresh removes deleted rates including an empty server library but retains watch history`() = scope.runTest {
        seedWatching()
        repo.refresh().getOrThrow()
        val watch = WatchStateEntity(300, 1, 100, 1000, null, null, now)
        db.watchStateDao().upsert(watch)
        api.rates["planned"]!!.clear()
        repo.refresh().getOrThrow()
        assertEquals(listOf(100, 200), repo.observeLibrary().first().map { it.anime.id }.sorted())
        api.rates.clear()
        repo.refresh().getOrThrow()
        assertTrue(repo.observeLibrary().first().isEmpty())
        assertEquals(watch, db.watchStateDao().getByAnimeId(300))
        assertNotNull(db.animeDao().getById(300))
    }

    @Test
    fun `library and anime observation react to all three caches without a network request`() = scope.runTest {
        seedWatching()
        repo.refresh().getOrThrow()
        api.calls.clear()
        repo.observeAnime(200).test {
            assertEquals(5, awaitItem()!!.rate.episodes)
            val anime = db.animeDao().getById(200)!!
            db.animeDao().upsertAll(listOf(anime.copy(nameRu = "Новое имя")))
            assertEquals("Новое имя", awaitItem()!!.anime.nameRu)
            val rate = db.userRateDao().getByAnimeId(200)!!
            db.userRateDao().upsertAll(listOf(rate.copy(episodes = 6)))
            assertEquals(6, awaitItem()!!.rate.episodes)
            val watch = WatchStateEntity(200, 7, 100, 1000, 12, 1, now)
            db.watchStateDao().upsert(watch)
            assertEquals(watch.toDomain(), awaitItem()!!.watch)
            db.userRateDao().deleteByAnimeId(200)
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(repo.observeAnime(999).first())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `each entry carries the episode positions of its own anime and no others`() = scope.runTest {
        // The only path by which `episode_progress` reaches the home screen, the title screen and
        // the television. Group it on the wrong key and every test in the suite stays green while
        // the feature does nothing on a device.
        seedWatching()
        repo.refresh().getOrThrow()
        db.episodeProgressDao().upsert(EpisodeProgressEntity(100, 4, 600_000, 1_440_000, now))
        db.episodeProgressDao().upsert(EpisodeProgressEntity(100, 5, 120_000, 1_440_000, now))
        db.episodeProgressDao().upsert(EpisodeProgressEntity(200, 7, 300_000, 1_440_000, now))

        val byAnime = repo.observeLibrary().first().associateBy { it.anime.id }

        assertEquals(
            listOf(4 to 600_000L, 5 to 120_000L),
            byAnime.getValue(100).progress.map { it.episode to it.positionMs },
        )
        assertEquals(listOf(7 to 300_000L), byAnime.getValue(200).progress.map { it.episode to it.positionMs })
        assertEquals(emptyList<EpisodeProgress>(), byAnime.getValue(300).progress)
    }

    @Test
    fun `failed rate or short fetch preserves cached library and sync timestamp`() = scope.runTest {
        seedWatching()
        repo.refresh().getOrThrow()
        val cached = repo.observeLibrary().first()
        api.rates["planned"]!!.clear()
        for (failure in listOf("rates:completed", "animes:100,200")) {
            api.beforeCall = { if (it == failure) throw IOException("offline") }
            assertTrue(repositoryAt(now.plusSeconds(60)).refresh().isFailure)
            assertEquals(cached, repo.observeLibrary().first())
            assertEquals(now, prefs.lastFullSync())
        }
    }

    @Test
    fun `failed detail enrichment reports failure but keeps refreshed rates and previous details`() = scope.runTest {
        seedWatching()
        repo.refresh().getOrThrow()
        api.rates["watching"]!![0] = api.rate(1, 100, "watching", 4)
        api.beforeCall = { if (it == "screenshots:100") throw IOException("offline") }
        assertTrue(repositoryAt(now.plusSeconds(21600)).refresh().isFailure)
        assertEquals(4, db.userRateDao().getByAnimeId(100)!!.episodes)
        assertEquals("desc", db.animeDao().getById(100)!!.description)
        assertEquals(now, db.animeDao().getById(100)!!.detailsFetchedAt)
        assertEquals(now, prefs.lastFullSync())
    }

    @Test
    fun `rate replacement rolls back when insertion fails`() = scope.runTest {
        seedWatching()
        repo.refresh().getOrThrow()
        val cached = db.userRateDao().observeAll().first()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_rate BEFORE INSERT ON user_rate BEGIN SELECT RAISE(ABORT, 'forced failure'); END")
        api.rates["planned"]!!.clear()
        assertTrue(repositoryAt(now.plusSeconds(60)).refresh().isFailure)
        assertEquals(cached, db.userRateDao().observeAll().first())
        assertEquals(now, prefs.lastFullSync())
    }

    @Test
    fun `refreshAnime always fetches separate screenshots and replaces cached details`() = scope.runTest {
        seedWatching()
        repo.refresh().getOrThrow()
        api.details[100] = api.details.getValue(100).copy(description = "new description", studios = emptyList(), nextEpisodeAt = null)
        api.screenshots[100] = emptyList()
        repo.refreshAnime(100).getOrThrow()
        val anime = repo.observeAnime(100).first()!!.anime
        assertEquals("new description", anime.description)
        assertNull(anime.studio)
        assertNull(anime.nextEpisodeAt)
        assertTrue(anime.screenshotUrls.isEmpty())
        assertEquals(2, api.calls.count { it == "anime:100" })
        assertEquals(2, api.calls.count { it == "screenshots:100" })
    }

    @Test
    fun `refreshAnime screenshot failure leaves the entire previous detail row untouched`() = scope.runTest {
        seedWatching()
        repo.refresh().getOrThrow()
        val cached = db.animeDao().getById(100)
        api.details[100] = api.details.getValue(100).copy(description = "changed")
        api.beforeCall = { if (it == "screenshots:100") throw IOException("offline") }
        assertTrue(repo.refreshAnime(100).isFailure)
        assertEquals(cached, db.animeDao().getById(100))
    }

    @Test
    fun `setEpisodes patches only episodes and updates cache immediately preserving rate identity`() = scope.runTest {
        seedWatching()
        repo.refresh().getOrThrow()
        repo.setEpisodes(200, 6).getOrThrow()
        val (id, request) = api.updates.single()
        assertEquals(2L, id)
        assertEquals(6, request.userRate.episodes)
        assertNull(request.userRate.status)
        assertNull(request.userRate.targetId)
        assertNull(request.userRate.targetType)
        val cached = db.userRateDao().getByAnimeId(200)!!
        assertEquals(6, cached.episodes)
        assertEquals(2L, cached.id)
        assertEquals(ListStatus.WATCHING, cached.status)
        assertEquals(now, cached.updatedAt)
        assertEquals(6, repo.observeAnime(200).first()!!.rate.episodes)
    }

    @Test
    fun `setStatus patches an existing rate without create fields and preserves episode progress`() = scope.runTest {
        seedWatching()
        repo.refresh().getOrThrow()
        repo.setStatus(200, ListStatus.ON_HOLD).getOrThrow()
        val (id, request) = api.updates.single()
        assertEquals(2L, id)
        assertEquals("on_hold", request.userRate.status)
        assertNull(request.userRate.targetId)
        assertNull(request.userRate.targetType)
        assertNull(request.userRate.userId)
        assertNull(request.userRate.episodes)
        assertTrue(api.creates.isEmpty())
        val entry = repo.observeAnime(200).first()!!
        assertEquals(ListStatus.ON_HOLD, entry.rate.status)
        assertEquals(5, entry.rate.episodes)
        assertEquals(now, entry.rate.updatedAt)
    }

    @Test
    fun `setStatus on unknown search result creates an explicit Anime rate and caches the anime`() = scope.runTest {
        api.animes[400] = api.short(400)
        assertEquals(listOf(400), repo.search("Name 4").getOrThrow().map { it.id })
        assertNull(db.animeDao().getById(400))
        repo.setStatus(400, ListStatus.PLANNED).getOrThrow()
        val payload = api.creates.single().userRate
        assertEquals(400, payload.targetId)
        assertEquals("planned", payload.status)
        assertEquals("Anime", payload.targetType)
        assertEquals(42L, payload.userId)
        val entry = repo.observeAnime(400).first()!!
        assertEquals(ListStatus.PLANNED, entry.rate.status)
        assertEquals("Имя 400", entry.anime.nameRu)
        assertEquals(now, entry.rate.updatedAt)
        assertNull(db.animeDao().getById(400)!!.detailsFetchedAt)
    }

    @Test
    fun `failed or missing unknown anime lookup prevents an invisible remote create`() = scope.runTest {
        assertTrue(repo.setStatus(400, ListStatus.PLANNED).isFailure)
        assertNull(db.userRateDao().getByAnimeId(400))
        assertTrue(api.creates.isEmpty())
        api.beforeCall = { if (it.startsWith("animes:")) throw IOException("offline") }
        assertTrue(repo.setStatus(400, ListStatus.PLANNED).isFailure)
        assertTrue(api.creates.isEmpty())
    }

    @Test
    fun `mutation failures keep cache and missing episode rate does not call API`() = scope.runTest {
        seedWatching()
        repo.refresh().getOrThrow()
        val cached = db.userRateDao().getByAnimeId(200)
        api.beforeCall = { if (it.startsWith("update:")) throw IOException("offline") }
        assertTrue(repo.setEpisodes(200, 6).isFailure)
        assertTrue(repo.setStatus(200, ListStatus.COMPLETED).isFailure)
        assertEquals(cached, db.userRateDao().getByAnimeId(200))
        api.calls.clear()
        assertTrue(repo.setEpisodes(999, 1).isFailure)
        assertTrue(api.calls.isEmpty())
        api.animes[400] = api.short(400)
        api.beforeCall = { if (it == "create") throw IOException("offline") }
        assertTrue(repo.setStatus(400, ListStatus.PLANNED).isFailure)
        assertNull(db.userRateDao().getByAnimeId(400))
    }

    @Test
    fun `search maps results without touching cache and reports remote failures`() = scope.runTest {
        api.animes[100] = api.short(100)
        val results = repo.search("name 1").getOrThrow()
        assertEquals(listOf(100), results.map { it.id })
        assertEquals("https://shikimori.io/o100.jpg", results.single().posterUrl)
        assertNull(db.animeDao().getById(100))
        api.beforeCall = { throw IOException("offline") }
        assertTrue(repo.search("name 1").isFailure)
        assertTrue(repo.observeLibrary().first().isEmpty())
    }

    @Test
    fun `overlapping creates serialize so one anime receives only one remote rate`() = scope.runTest {
        api.animes[400] = api.short(400)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.beforeCall = { if (it == "create") { entered.complete(Unit); release.await() } }
        val first = async { repo.setStatus(400, ListStatus.PLANNED) }
        entered.await()
        val second = async { repo.setStatus(400, ListStatus.WATCHING) }
        runCurrent()
        release.complete(Unit)
        first.await().getOrThrow()
        second.await().getOrThrow()
        assertEquals(1, api.creates.size)
        assertEquals(1, api.updates.size)
        assertEquals(ListStatus.WATCHING, repo.observeAnime(400).first()!!.rate.status)
    }

    @Test
    fun `cancellation propagates instead of becoming a failed Result`() = scope.runTest {
        api.beforeCall = { throw CancellationException("cancelled") }
        try {
            repo.search("anything")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(repo.observeLibrary().first().isEmpty())
        }
    }

    @Test
    fun `preferences persist identity sync timestamp and watched threshold with clear defaults`() = scope.runTest {
        assertEquals(42L, prefs.userId())
        assertNull(prefs.lastFullSync())
        assertEquals(0.9f, prefs.watchedThreshold.first())
        prefs.setUserId(42)
        prefs.setLastFullSync(now)
        prefsStore.edit { it[floatPreferencesKey("watched_threshold")] = 0.8f }
        val reloaded = AppPreferences(prefsStore)
        assertEquals(42L, reloaded.userId())
        assertEquals(now, reloaded.lastFullSync())
        assertEquals(0.8f, reloaded.watchedThreshold.first())
        reloaded.clear()
        assertNull(prefs.userId())
        assertNull(prefs.lastFullSync())
        assertEquals(0.9f, prefs.watchedThreshold.first())
    }
}
