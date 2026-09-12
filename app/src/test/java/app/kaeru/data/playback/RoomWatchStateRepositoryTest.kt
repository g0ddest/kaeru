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
import app.kaeru.domain.error.AccountSessionChanged
import app.kaeru.domain.error.StorageFailure
import app.kaeru.domain.model.WatchState
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
import org.junit.Assert.assertNull
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
class RoomWatchStateRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val storeScope = TestScope(dispatcher)
    private val now = Instant.parse("2026-09-13T10:00:00Z")
    private lateinit var db: KaeruDatabase
    private lateinit var store: DataStore<Preferences>
    private lateinit var prefs: AppPreferences
    private lateinit var session: AccountSession
    private lateinit var repo: RoomWatchStateRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        store = PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "prefs.preferences_pb") }
        prefs = AppPreferences(store)
        runTest(dispatcher) { prefs.setUserId(42) }
        session = AccountSession(InMemoryTokenStore(AuthTokens("access", "refresh", 9_999_999_999, 42)), prefs, db)
        repo = RoomWatchStateRepository(db.watchStateDao(), session, dispatcher)
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
        scope.cancel()
    }

    private fun state(
        animeId: Int = 100,
        episode: Int = 4,
        positionMs: Long = 65_000,
        durationMs: Long = 1_400_000,
        translationId: Int? = 7,
        kodikSeason: Int? = 2,
    ) = WatchState(animeId, episode, positionMs, durationMs, translationId, kodikSeason, now)

    @Test
    fun `a saved position comes back whole`() = scope.runTest {
        repo.save(state())

        assertEquals(state(), repo.observe(100).first())
    }

    @Test
    fun `an anime nobody has watched observes as nothing`() = scope.runTest {
        repo.save(state(animeId = 200))

        assertNull(repo.observe(100).first())
    }

    @Test
    fun `the flow follows the row through updates and clearing`() = scope.runTest {
        repo.observe(100).test {
            assertNull(awaitItem())

            repo.save(state(positionMs = 10_000))
            assertEquals(10_000L, awaitItem()!!.positionMs)

            repo.save(state(positionMs = 20_000))
            assertEquals(20_000L, awaitItem()!!.positionMs)

            repo.clear(100)
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `another anime being written does not disturb this one`() = scope.runTest {
        repo.save(state())

        repo.observe(100).test {
            assertEquals(state(), awaitItem())

            repo.save(state(animeId = 200, positionMs = 1_000))
            advanceUntilIdle()

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saving twice for one anime keeps a single row`() = scope.runTest {
        repo.save(state(episode = 4))
        repo.save(state(episode = 5, positionMs = 0))

        assertEquals(5, db.watchStateDao().getByAnimeId(100)!!.episode)
        assertEquals(1, db.watchStateDao().observeAll().first().size)
    }

    @Test
    fun `clearing an anime nobody watched is not an error`() = scope.runTest {
        repo.clear(100)

        assertNull(repo.observe(100).first())
    }

    @Test
    fun `a write waits for whoever holds the account`() = scope.runTest {
        val gate = CompletableDeferred<Unit>()
        val holder = launch { session.withAccount { gate.await() } }
        runCurrent()
        var written = false

        val writer = launch {
            repo.save(state())
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
        repo.save(state())
        val gate = CompletableDeferred<Unit>()
        val holder = launch { session.withAccount { gate.await() } }
        runCurrent()

        assertEquals(state(), repo.observe(100).first())

        gate.complete(Unit)
        holder.join()
    }

    @Test
    fun `a write with no signed in account is refused`() = scope.runTest {
        val signedOut = AccountSession(InMemoryTokenStore(), prefs, db)
        val repository = RoomWatchStateRepository(db.watchStateDao(), signedOut, dispatcher)

        try {
            repository.save(state())
            fail("Expected a save without an account to be refused")
        } catch (expected: AccountSessionChanged) {
            assertNull(db.watchStateDao().getByAnimeId(100))
        }
    }

    @Test
    fun `a write for another account is refused`() = scope.runTest {
        val otherAccount = AccountSession(
            InMemoryTokenStore(AuthTokens("access", "refresh", 9_999_999_999, 7)), prefs, db,
        )
        val repository = RoomWatchStateRepository(db.watchStateDao(), otherAccount, dispatcher)

        try {
            repository.clear(100)
            fail("Expected a clear for a foreign account to be refused")
        } catch (expected: AccountSessionChanged) {
            // The account boundary holds for deletes exactly as it does for writes.
        }
    }

    @Test
    fun `a database failure crosses the boundary as a storage failure`() = scope.runTest {
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_watch_state_insert
            BEFORE INSERT ON watch_state
            BEGIN
                SELECT RAISE(ABORT, 'forced insert failure');
            END
            """.trimIndent(),
        )

        try {
            repo.save(state())
            fail("Expected the aborted insert to surface")
        } catch (expected: StorageFailure) {
            // Nothing above the data layer knows SQLite, so the cause is wrapped, not raised raw.
            assertTrue(expected.cause is SQLiteException)
            assertNull(db.watchStateDao().getByAnimeId(100))
        }
    }

    @Test
    fun `signing out takes the positions with it`() = scope.runTest {
        repo.save(state())

        db.clearAccountData()

        assertNull(repo.observe(100).first())
    }
}
