package app.kaeru.data.local

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class KaeruDatabaseTest {
    private lateinit var db: KaeruDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KaeruDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun anime(id: Int) = AnimeEntity(
        id = id,
        nameRu = "Имя $id",
        nameRomaji = "Name $id",
        posterUrl = null,
        screenshots = listOf("a", "b"),
        status = AnimeStatus.ONGOING,
        episodes = 12,
        episodesAired = 3,
        nextEpisodeAt = Instant.ofEpochMilli(100_123),
        score = 8.0,
        year = 2026,
        studio = "MAPPA",
        description = null,
        detailsFetchedAt = Instant.ofEpochMilli(200_456),
    )

    private suspend fun assertSqlFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected SQLite operation to fail")
        } catch (_: SQLiteException) {
            // Expected: callers assert the database state left behind by the failed statement.
        }
    }

    @Test
    fun `anime database round trip preserves lists enums and millisecond instants`() = runTest {
        db.animeDao().upsertAll(listOf(anime(1)))

        val loaded = db.animeDao().getById(1)!!

        assertEquals(listOf("a", "b"), loaded.screenshots)
        assertEquals(AnimeStatus.ONGOING, loaded.status)
        assertEquals(Instant.ofEpochMilli(100_123), loaded.nextEpisodeAt)
        assertEquals(Instant.ofEpochMilli(200_456), loaded.detailsFetchedAt)
        assertEquals(anime(1), loaded)
    }

    @Test
    fun `anime observeAll re-emits after inserts and updates`() = runTest {
        val dao = db.animeDao()

        dao.observeAll().test {
            assertEquals(emptyList<AnimeEntity>(), awaitItem())

            dao.upsertAll(listOf(anime(1)))
            assertEquals(listOf(anime(1)), awaitItem())

            val updated = anime(1).copy(episodesAired = 4)
            dao.upsertAll(listOf(updated))
            assertEquals(listOf(updated), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `anime observeById and getByIds return requested rows`() = runTest {
        val dao = db.animeDao()

        dao.observeById(2).test {
            assertNull(awaitItem())

            dao.upsertAll(listOf(anime(1), anime(2), anime(3)))
            assertEquals(anime(2), awaitItem())
            assertEquals(setOf(anime(1), anime(3)), dao.getByIds(listOf(3, 1)).toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `anime mapper round trip preserves domain values and cache timestamp`() {
        val domain = Anime(
            id = 42,
            nameRu = "Имя",
            nameRomaji = "Name",
            posterUrl = "poster",
            screenshotUrls = listOf("one", "two"),
            status = AnimeStatus.RELEASED,
            episodes = 24,
            episodesAired = 24,
            nextEpisodeAt = null,
            score = 9.25,
            year = 2024,
            studio = "Bones",
            description = "Details",
        )
        val fetchedAt = Instant.ofEpochMilli(987_654_321)

        val entity = domain.toEntity(fetchedAt)

        assertEquals(
            AnimeEntity(
                id = 42,
                nameRu = "Имя",
                nameRomaji = "Name",
                posterUrl = "poster",
                screenshots = listOf("one", "two"),
                status = AnimeStatus.RELEASED,
                episodes = 24,
                episodesAired = 24,
                nextEpisodeAt = null,
                score = 9.25,
                year = 2024,
                studio = "Bones",
                description = "Details",
                detailsFetchedAt = Instant.ofEpochMilli(987_654_321),
            ),
            entity,
        )
        assertEquals(domain, entity.toDomain())
    }

    @Test
    fun `mergeShort refreshes card values without erasing cached details`() {
        val cached = AnimeEntity(
            id = 7,
            nameRu = "Old",
            nameRomaji = "Old Romaji",
            posterUrl = "cached-poster",
            screenshots = listOf("cached-shot"),
            status = AnimeStatus.ANONS,
            episodes = 0,
            episodesAired = 0,
            nextEpisodeAt = Instant.ofEpochMilli(300_123),
            score = 7.5,
            year = 2025,
            studio = "Cached Studio",
            description = "Cached description",
            detailsFetchedAt = Instant.ofEpochMilli(400_456),
        )
        val fresh = Anime(
            id = 7,
            nameRu = "Fresh",
            nameRomaji = "Fresh Romaji",
            posterUrl = null,
            screenshotUrls = emptyList(),
            status = AnimeStatus.ONGOING,
            episodes = 12,
            episodesAired = 4,
            nextEpisodeAt = null,
            score = null,
            year = null,
            studio = null,
            description = null,
        )

        val merged = cached.mergeShort(fresh)

        assertEquals("Fresh", merged.nameRu)
        assertEquals("Fresh Romaji", merged.nameRomaji)
        assertEquals(AnimeStatus.ONGOING, merged.status)
        assertEquals(12, merged.episodes)
        assertEquals(4, merged.episodesAired)
        assertEquals("cached-poster", merged.posterUrl)
        assertEquals(listOf("cached-shot"), merged.screenshots)
        assertEquals(7.5, merged.score)
        assertEquals(2025, merged.year)
        assertEquals("Cached Studio", merged.studio)
        assertEquals("Cached description", merged.description)
        assertEquals(Instant.ofEpochMilli(300_123), merged.nextEpisodeAt)
        assertEquals(Instant.ofEpochMilli(400_456), merged.detailsFetchedAt)
    }

    @Test
    fun `user rate mapper round trip preserves every value`() {
        val domain = UserRate(
            id = 11,
            animeId = 22,
            status = ListStatus.REWATCHING,
            episodes = 6,
            updatedAt = Instant.ofEpochMilli(500_789),
        )

        val entity = domain.toEntity()

        assertEquals(
            UserRateEntity(
                id = 11,
                animeId = 22,
                status = ListStatus.REWATCHING,
                episodes = 6,
                updatedAt = Instant.ofEpochMilli(500_789),
            ),
            entity,
        )
        assertEquals(domain, entity.toDomain())
    }

    @Test
    fun `watch state mapper round trip preserves every value`() {
        val domain = WatchState(
            animeId = 31,
            episode = 8,
            positionMs = 12_345,
            durationMs = 98_765,
            translationId = 17,
            kodikSeason = 2,
            updatedAt = Instant.ofEpochMilli(600_987),
            translationTitle = "AniLibria.TV",
        )

        val entity = domain.toEntity()

        assertEquals(
            WatchStateEntity(
                animeId = 31,
                episode = 8,
                positionMs = 12_345,
                durationMs = 98_765,
                translationId = 17,
                kodikSeason = 2,
                updatedAt = Instant.ofEpochMilli(600_987),
                translationTitle = "AniLibria.TV",
            ),
            entity,
        )
        assertEquals(domain, entity.toDomain())
    }

    @Test
    fun `the dub's name is stored and read back with the position`() = runTest {
        val dao = db.watchStateDao()

        dao.upsert(
            WatchState(
                animeId = 31,
                episode = 8,
                positionMs = 12_345,
                durationMs = 98_765,
                translationId = 17,
                kodikSeason = 2,
                updatedAt = Instant.ofEpochMilli(600_987),
                translationTitle = "Студийная банда",
            ).toEntity(),
        )

        assertEquals("Студийная банда", dao.observeByAnimeId(31).first()?.translationTitle)
    }

    @Test
    fun `replaceAll removes rates that disappeared`() = runTest {
        val dao = db.userRateDao()
        dao.upsertAll(
            listOf(
                UserRateEntity(1, 10, ListStatus.WATCHING, 2, Instant.EPOCH),
                UserRateEntity(2, 20, ListStatus.PLANNED, 0, Instant.EPOCH),
            ),
        )

        dao.replaceAll(listOf(UserRateEntity(1, 10, ListStatus.WATCHING, 3, Instant.EPOCH)))

        dao.observeAll().test {
            val rates = awaitItem()
            assertEquals(1, rates.size)
            assertEquals(3, rates[0].episodes)
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(dao.getByAnimeId(20))
    }

    @Test
    fun `replaceAll rolls back deletion when the upsert phase fails`() = runTest {
        val dao = db.userRateDao()
        val existing = listOf(
            UserRateEntity(1, 10, ListStatus.WATCHING, 2, Instant.ofEpochMilli(100)),
            UserRateEntity(2, 20, ListStatus.PLANNED, 0, Instant.ofEpochMilli(200)),
        )
        dao.upsertAll(existing)
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_user_rate_insert
            BEFORE INSERT ON user_rate
            BEGIN
                SELECT RAISE(ABORT, 'forced upsert failure');
            END
            """.trimIndent(),
        )

        assertSqlFailure {
            dao.replaceAll(listOf(UserRateEntity(3, 30, ListStatus.COMPLETED, 12, Instant.EPOCH)))
        }

        assertEquals(existing[0], dao.getByAnimeId(10))
        assertEquals(existing[1], dao.getByAnimeId(20))
        assertNull(dao.getByAnimeId(30))
    }

    @Test
    fun `user rate observeAll re-emits after insert update and delete`() = runTest {
        val dao = db.userRateDao()
        val inserted = UserRateEntity(1, 10, ListStatus.PLANNED, 0, Instant.ofEpochMilli(100))
        val updated = inserted.copy(status = ListStatus.WATCHING, episodes = 3)

        dao.observeAll().test {
            assertEquals(emptyList<UserRateEntity>(), awaitItem())

            dao.upsertAll(listOf(inserted))
            assertEquals(listOf(inserted), awaitItem())

            dao.upsertAll(listOf(updated))
            assertEquals(listOf(updated), awaitItem())

            dao.deleteByAnimeId(10)
            assertEquals(emptyList<UserRateEntity>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(dao.getByAnimeId(10))
    }

    @Test
    fun `user rate unique anime id rejects a second primary key`() = runTest {
        val dao = db.userRateDao()
        val original = UserRateEntity(1, 10, ListStatus.WATCHING, 2, Instant.ofEpochMilli(100))
        dao.upsertAll(listOf(original))

        dao.upsertAll(listOf(UserRateEntity(2, 10, ListStatus.COMPLETED, 12, Instant.ofEpochMilli(200))))

        assertEquals(original, dao.getByAnimeId(10))
        assertEquals(listOf(original), dao.observeAll().first())
    }

    @Test
    fun `user rate persists every ListStatus through SQL`() = runTest {
        val dao = db.userRateDao()
        val rates = ListStatus.entries.mapIndexed { index, status ->
            UserRateEntity(
                id = index.toLong() + 1,
                animeId = index + 100,
                status = status,
                episodes = index,
                updatedAt = Instant.ofEpochMilli(index.toLong()),
            )
        }

        dao.upsertAll(rates)

        val loadedStatuses = dao.observeAll().first().sortedBy(UserRateEntity::id).map(UserRateEntity::status)
        assertEquals(
            listOf(
                ListStatus.WATCHING,
                ListStatus.PLANNED,
                ListStatus.COMPLETED,
                ListStatus.ON_HOLD,
                ListStatus.DROPPED,
                ListStatus.REWATCHING,
            ),
            loadedStatuses,
        )
    }

    @Test
    fun `watch state upsert overwrites by anime id`() = runTest {
        val dao = db.watchStateDao()
        val inserted = WatchStateEntity(5, 1, 1_000, 100_000, null, null, Instant.EPOCH)
        val updated = WatchStateEntity(5, 2, 500, 100_000, 7, 1, Instant.ofEpochSecond(9))

        dao.observeAll().test {
            assertEquals(emptyList<WatchStateEntity>(), awaitItem())

            dao.upsert(inserted)
            assertEquals(listOf(inserted), awaitItem())

            dao.upsert(updated)
            assertEquals(listOf(updated), awaitItem())

            dao.deleteByAnimeId(5)
            assertEquals(emptyList<WatchStateEntity>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(dao.getByAnimeId(5))
    }
}
