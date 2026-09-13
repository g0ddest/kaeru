package app.kaeru.data.playback

import android.database.sqlite.SQLiteException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.kaeru.data.auth.AccountSession
import app.kaeru.data.auth.AuthTokens
import app.kaeru.data.auth.InMemoryTokenStore
import app.kaeru.data.library.AppPreferences
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.toEntity
import app.kaeru.domain.error.AccountSessionChanged
import app.kaeru.domain.error.StorageFailure
import app.kaeru.domain.model.EpisodeProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RoomEpisodeProgressRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val storeScope = TestScope(dispatcher)
    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private lateinit var db: KaeruDatabase
    private lateinit var store: DataStore<Preferences>
    private lateinit var prefs: AppPreferences
    private lateinit var session: AccountSession
    private lateinit var repo: RoomEpisodeProgressRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        store = PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "prefs.preferences_pb") }
        prefs = AppPreferences(store)
        runTest(dispatcher) { prefs.setUserId(42) }
        session = AccountSession(InMemoryTokenStore(AuthTokens("access", "refresh", 9_999_999_999, 42)), prefs, db)
        repo = RoomEpisodeProgressRepository(db.episodeProgressDao(), session, dispatcher)
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
        scope.cancel()
    }

    private fun progress(
        animeId: Int = 100,
        episode: Int = 7,
        positionMs: Long = 2_400_000,
        durationMs: Long = 2_880_000,
    ) = EpisodeProgress(animeId, episode, positionMs, durationMs, now)

    @Test
    fun `a saved position comes back whole`() = scope.runTest {
        repo.save(progress())

        assertEquals(listOf(progress()), repo.observe(100).first())
    }

    @Test
    fun `two episodes of one anime are two rows, not one`() = scope.runTest {
        repo.save(progress(episode = 7, positionMs = 2_400_000))
        repo.save(progress(episode = 6, positionMs = 10_000))

        assertEquals(
            listOf(progress(episode = 6, positionMs = 10_000), progress(episode = 7, positionMs = 2_400_000)),
            repo.observe(100).first(),
        )
    }

    @Test
    fun `saving the same episode twice keeps a single row`() = scope.runTest {
        repo.save(progress(positionMs = 100_000))
        repo.save(progress(positionMs = 200_000))

        assertEquals(listOf(200_000L), repo.observe(100).first().map { it.positionMs })
    }

    @Test
    fun `an anime nobody has watched observes as an empty list`() = scope.runTest {
        repo.save(progress(animeId = 200))

        assertEquals(emptyList<EpisodeProgress>(), repo.observe(100).first())
    }

    @Test
    fun `the flow follows the rows through updates and clearing`() = scope.runTest {
        repo.observe(100).test {
            assertEquals(emptyList<EpisodeProgress>(), awaitItem())

            repo.save(progress(episode = 7, positionMs = 10_000))
            assertEquals(listOf(10_000L), awaitItem().map { it.positionMs })

            repo.save(progress(episode = 8, positionMs = 20_000))
            assertEquals(listOf(10_000L, 20_000L), awaitItem().map { it.positionMs })

            repo.clear(100)
            assertEquals(emptyList<EpisodeProgress>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `every row comes back at once, for the library that builds each entry`() = scope.runTest {
        repo.save(progress(animeId = 100, episode = 7))
        repo.save(progress(animeId = 200, episode = 1))

        val all = repo.observeAll().first().sortedBy { it.animeId }

        assertEquals(listOf(100 to 7, 200 to 1), all.map { it.animeId to it.episode })
    }

    @Test
    fun `another anime being written does not disturb this one`() = scope.runTest {
        repo.save(progress())

        repo.observe(100).test {
            assertEquals(listOf(progress()), awaitItem())

            repo.save(progress(animeId = 200, positionMs = 1_000))
            advanceUntilIdle()

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearing an anime nobody watched is not an error`() = scope.runTest {
        repo.clear(100)

        assertEquals(emptyList<EpisodeProgress>(), repo.observe(100).first())
    }

    @Test
    fun `clearing one anime leaves the others alone`() = scope.runTest {
        repo.save(progress(animeId = 100))
        repo.save(progress(animeId = 200))

        repo.clear(100)

        assertEquals(listOf(200), repo.observeAll().first().map { it.animeId })
    }

    @Test
    fun `a write waits for whoever holds the account`() = scope.runTest {
        val gate = CompletableDeferred<Unit>()
        val holder = launch { session.withAccount { gate.await() } }
        runCurrent()
        var written = false

        val writer = launch {
            repo.save(progress())
            written = true
        }
        runCurrent()
        assertFalse(written)

        gate.complete(Unit)
        holder.join()
        writer.join()
        assertTrue(written)
    }

    @Test
    fun `a read does not wait for the account, so a refresh cannot stall the player`() = scope.runTest {
        repo.save(progress())
        val gate = CompletableDeferred<Unit>()
        val holder = launch { session.withAccount { gate.await() } }
        runCurrent()

        assertEquals(listOf(progress()), repo.observe(100).first())

        gate.complete(Unit)
        holder.join()
    }

    @Test
    fun `a write with no signed in account is refused`() = scope.runTest {
        val signedOut = AccountSession(InMemoryTokenStore(), prefs, db)
        val repository = RoomEpisodeProgressRepository(db.episodeProgressDao(), signedOut, dispatcher)

        try {
            repository.save(progress())
            fail("Expected a save without an account to be refused")
        } catch (expected: AccountSessionChanged) {
            assertEquals(emptyList<EpisodeProgress>(), repo.observe(100).first())
        }
    }

    @Test
    fun `a database failure crosses the boundary as a storage failure`() = scope.runTest {
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_episode_progress_insert
            BEFORE INSERT ON episode_progress
            BEGIN
                SELECT RAISE(ABORT, 'forced insert failure');
            END
            """.trimIndent(),
        )

        try {
            repo.save(progress())
            fail("Expected the aborted insert to surface")
        } catch (expected: StorageFailure) {
            // Nothing above the data layer knows SQLite, so the cause is wrapped, not raised raw.
            assertTrue(expected.cause is SQLiteException)
        }
    }

    @Test
    fun `signing out takes every episode with it`() = scope.runTest {
        repo.save(progress(animeId = 100))
        repo.save(progress(animeId = 200))

        db.clearAccountData()

        assertEquals(emptyList<EpisodeProgress>(), repo.observeAll().first())
    }

    @Test
    fun `the mapper round trip preserves every value`() {
        val domain = progress(animeId = 31, episode = 8, positionMs = 12_345, durationMs = 98_765)

        val entity = domain.toEntity()

        assertEquals(domain, entity.toDomain())
        assertEquals(31, entity.animeId)
        assertEquals(8, entity.episode)
        assertEquals(12_345L, entity.positionMs)
        assertEquals(98_765L, entity.durationMs)
        assertEquals(now, entity.updatedAt)
    }
}
