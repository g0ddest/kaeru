package app.kaeru.data.viewsync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.kaeru.data.auth.AccountSession
import app.kaeru.data.auth.AuthTokens
import app.kaeru.data.auth.InMemoryTokenStore
import app.kaeru.data.library.AppPreferences
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.toEntity
import app.kaeru.data.playback.RoomPlaybackSampleRepository
import app.kaeru.data.playback.RoomWatchStateRepository
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.SecretTitle
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.viewsync.RememberedDub
import app.kaeru.domain.viewsync.SyncedViewing
import app.kaeru.domain.viewsync.ViewingSyncEvents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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
import java.time.Instant

/**
 * What viewing sync writes into Room and hears from it: another device's positions, dubs and
 * tombstones going in around the playback path, and this device's own positions and dub choices
 * coming out as events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RoomLocalViewingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val storeScope = TestScope(dispatcher)
    private lateinit var db: KaeruDatabase
    private lateinit var store: DataStore<Preferences>
    private lateinit var prefs: AppPreferences
    private lateinit var session: AccountSession
    private lateinit var local: RoomLocalViewing
    private val events = ViewingSyncEvents()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        store = PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "prefs.preferences_pb") }
        prefs = AppPreferences(store)
        runTest(dispatcher) { prefs.setUserId(ACCOUNT) }
        session = AccountSession(InMemoryTokenStore(AuthTokens("access", "refresh", 9_999_999_999, ACCOUNT)), prefs, db)
        local = RoomLocalViewing(db, session, dispatcher)
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
        scope.cancel()
    }

    private fun progress(episode: Int, at: Long, positionMs: Long = 60_000) =
        EpisodeProgress(ANIME, episode, positionMs, 1_440_000, Instant.ofEpochMilli(at))

    private fun watch(episode: Int, at: Long, translationId: Int? = 7, title: String? = "AniDUB") =
        WatchState(ANIME, episode, 90_000, 1_440_000, translationId, 2, Instant.ofEpochMilli(at), title)

    @Test
    fun `positions go in only over older ones`() = scope.runTest {
        db.episodeProgressDao().upsertAll(
            listOf(progress(1, at = 100).toEntity(), progress(2, at = 300).toEntity()),
        )

        val written = local.apply(
            ACCOUNT,
            SyncedViewing(positions = listOf(progress(1, at = 200, 700_000), progress(2, at = 250, 1), progress(3, at = 50, 2))),
        )

        assertTrue(written)
        val rows = local.positions().associateBy { it.episode }
        assertEquals(700_000L, rows.getValue(1).positionMs)
        assertEquals(60_000L, rows.getValue(2).positionMs)
        assertEquals(2L, rows.getValue(3).positionMs)
    }

    @Test
    fun `a tombstone drops positions saved at or before it and rewinds the pointer, keeping the dub`() = scope.runTest {
        db.episodeProgressDao().upsertAll(
            listOf(1 to 100L, 2 to 200L, 3 to 300L).map { (episode, at) -> progress(episode, at).toEntity() },
        )
        db.watchStateDao().upsert(watch(episode = 2, at = 200).toEntity())

        local.apply(ACCOUNT, SyncedViewing(tombstones = mapOf(ANIME to Instant.ofEpochMilli(200))))

        assertEquals(listOf(3), local.positions().map { it.episode })
        val pointer = db.watchStateDao().getByAnimeId(ANIME)!!
        assertEquals(0L, pointer.positionMs)
        assertEquals(7, pointer.translationId)
    }

    @Test
    fun `a dub from another device replaces the remembered one or starts a row`() = scope.runTest {
        db.watchStateDao().upsert(watch(episode = 4, at = 100).toEntity())

        local.apply(
            ACCOUNT,
            SyncedViewing(
                positions = listOf(EpisodeProgress(OTHER_ANIME, 6, 1_000, 1_400_000, Instant.ofEpochMilli(5))),
                dubs = mapOf(ANIME to RememberedDub(610, "AniLibria.TV"), OTHER_ANIME to RememberedDub(9, "JAM")),
            ),
        )

        val kept = db.watchStateDao().getByAnimeId(ANIME)!!
        assertEquals(610, kept.translationId)
        assertEquals("AniLibria.TV", kept.translationTitle)
        // The episode and the position the row stood on are this device's; only the voice changed.
        assertEquals(4, kept.episode)
        assertEquals(90_000L, kept.positionMs)
        val started = db.watchStateDao().getByAnimeId(OTHER_ANIME)!!
        assertEquals(9, started.translationId)
        assertEquals(6, started.episode)
        assertEquals(0L, started.positionMs)
        assertEquals(mapOf(ANIME to RememberedDub(610, "AniLibria.TV"), OTHER_ANIME to RememberedDub(9, "JAM")), local.dubs())
    }

    @Test
    fun `a write for an account no longer signed in is turned down`() = scope.runTest {
        val written = local.apply(OTHER_ACCOUNT, SyncedViewing(positions = listOf(progress(1, at = 5))))

        assertFalse(written)
        assertTrue(local.positions().isEmpty())
    }

    @Test
    fun `the list's statuses are read by title`() = scope.runTest {
        assertEquals(emptyMap<Int, Any>(), local.statuses().first())
    }

    // --- «украдкой» -----------------------------------------------------------------------------

    private fun anime(id: Int, episodes: Int = 12, status: AnimeStatus = AnimeStatus.RELEASED) = Anime(
        id, "Имя $id", "Name $id", null, emptyList(), status, episodes, episodes, null, null, 2026, null, null,
    )

    @Test
    fun `a secret from another device goes in only over an older one, and its card is fetched`() = scope.runTest {
        val fetched = mutableListOf<Int>()
        val viewing = RoomLocalViewing(db, session, dispatcher, SecretCards { fetched += it })
        db.animeDao().upsertAll(listOf(anime(ANIME).toEntity(detailsFetchedAt = null)))
        db.secretTitleDao().upsert(SecretTitle(ANIME, true, 4, Instant.ofEpochMilli(300)).toEntity())

        viewing.apply(
            ACCOUNT,
            SyncedViewing(
                secrets = listOf(
                    SecretTitle(ANIME, false, 9, Instant.ofEpochMilli(200)),
                    SecretTitle(OTHER_ANIME, true, 2, Instant.ofEpochMilli(100)),
                ),
            ),
        )

        val secrets = viewing.secrets()
        assertEquals(SecretTitle(ANIME, true, 4, Instant.ofEpochMilli(300)), secrets[ANIME])
        assertEquals(SecretTitle(OTHER_ANIME, true, 2, Instant.ofEpochMilli(100)), secrets[OTHER_ANIME])
        // Only the title this device has no card for.
        assertEquals(listOf(OTHER_ANIME), fetched)
    }

    @Test
    fun `a secret title reads as secret, and as completed once a finished show is all watched`() = scope.runTest {
        db.animeDao().upsertAll(
            listOf(anime(ANIME).toEntity(detailsFetchedAt = null), anime(OTHER_ANIME).toEntity(detailsFetchedAt = null)),
        )
        db.userRateDao().upsertAll(
            listOf(UserRate(1, ANIME, ListStatus.WATCHING, 3, Instant.EPOCH).toEntity()),
        )
        db.secretTitleDao().upsert(SecretTitle(ANIME, true, 5, Instant.ofEpochMilli(1)).toEntity())
        db.secretTitleDao().upsert(SecretTitle(OTHER_ANIME, true, 12, Instant.ofEpochMilli(1)).toEntity())

        assertEquals(
            mapOf(ANIME to ListStatus.SECRET, OTHER_ANIME to ListStatus.COMPLETED),
            local.statuses().first(),
        )
    }

    @Test
    fun `an ongoing show is never finished by its secret count`() {
        val ongoing = anime(ANIME, status = AnimeStatus.ONGOING)
        assertFalse(SecretTitle.finished(ongoing, 12, Instant.EPOCH))
        assertTrue(SecretTitle.finished(anime(ANIME), 12, Instant.EPOCH))
        assertFalse(SecretTitle.finished(anime(ANIME), 11, Instant.EPOCH))
        assertFalse(SecretTitle.finished(anime(ANIME, episodes = 0), 3, Instant.EPOCH))
    }

    // --- what the repositories tell sync --------------------------------------------------------

    @Test
    fun `a saved sample is announced as a position`() = scope.runTest {
        val samples = RoomPlaybackSampleRepository(db, session, dispatcher, events)
        samples.save(watch(episode = 1, at = 10), progress(1, at = 10))

        val event = withTimeoutOrNull(1_000) { events.events.first() }
        assertEquals(ViewingSyncEvents.Event.Position(progress(1, at = 10)), event)
    }

    @Test
    fun `a changed and named track is announced as a dub, the same one again is not`() = scope.runTest {
        val repository = RoomWatchStateRepository(db.watchStateDao(), session, dispatcher, events)
        repository.save(watch(episode = 1, at = 10, translationId = 7, title = "AniDUB"))
        repository.save(watch(episode = 2, at = 20, translationId = 7, title = "AniDUB"))
        repository.save(watch(episode = 2, at = 30, translationId = 8, title = null))
        repository.save(watch(episode = 2, at = 40, translationId = 9, title = "JAM"))

        val announced = events.events.take(2).toList()
        assertEquals(
            listOf(
                ViewingSyncEvents.Event.Dub(ANIME, RememberedDub(7, "AniDUB")),
                ViewingSyncEvents.Event.Dub(ANIME, RememberedDub(9, "JAM")),
            ),
            announced,
        )
        assertNull(withTimeoutOrNull(100) { events.events.first() })
    }

    private companion object {
        const val ACCOUNT = 42L
        const val OTHER_ACCOUNT = 77L
        const val ANIME = 100
        const val OTHER_ANIME = 200
    }
}
