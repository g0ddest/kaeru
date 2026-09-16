package app.kaeru.data.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.kaeru.data.auth.AccountSession
import app.kaeru.data.auth.AuthTokens
import app.kaeru.data.auth.InMemoryTokenStore
import app.kaeru.data.auth.TokenStore
import app.kaeru.data.library.AppPreferences
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.domain.error.AccountSessionChanged
import app.kaeru.domain.error.StorageFailure
import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.WatchState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * The two rows of one progress sample, and the promise that they arrive together.
 *
 * The sample used to be two writes, each taking the account lock on its own; these are the
 * properties that say it is now one — one wait on the lock, and nothing half-written when the
 * second row cannot be stored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-television-notnight-mdpi")
class RoomPlaybackSampleRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val storeScope = TestScope(dispatcher)
    private val now: Instant = Instant.parse("2026-09-13T10:00:00Z")
    private lateinit var db: KaeruDatabase
    private lateinit var store: DataStore<Preferences>
    private lateinit var prefs: AppPreferences
    private lateinit var session: AccountSession
    private lateinit var lock: CountingTokenStore
    private lateinit var repo: RoomPlaybackSampleRepository

    /**
     * Counts turns of the account lock.
     *
     * `AccountSession.withAccount` reads the store exactly once per call, inside its mutex and
     * after taking it, so a read counted here is one critical section entered. That is the property
     * this class exists for and the one nothing else in the suite can see: parking a holder on the
     * lock and watching a save block behind it is satisfied by two acquisitions exactly as well as
     * by one, so it cannot tell this implementation from the one it replaced.
     */
    private class CountingTokenStore(private val delegate: TokenStore) : TokenStore by delegate {
        var turns = 0
            private set

        override suspend fun get(): AuthTokens? {
            turns++
            return delegate.get()
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        store = PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "prefs.preferences_pb") }
        prefs = AppPreferences(store)
        runTest(dispatcher) { prefs.setUserId(42) }
        lock = CountingTokenStore(InMemoryTokenStore(AuthTokens("access", "refresh", 9_999_999_999, 42)))
        session = AccountSession(lock, prefs, db)
        repo = RoomPlaybackSampleRepository(db, session, dispatcher)
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
        scope.cancel()
    }

    private fun watch(episode: Int = 7, positionMs: Long = 2_400_000) =
        WatchState(100, episode, positionMs, 2_880_000, translationId = 12, kodikSeason = 1, updatedAt = now)

    private fun progress(episode: Int = 7, positionMs: Long = 2_400_000) =
        EpisodeProgress(100, episode, positionMs, 2_880_000, now)

    private suspend fun storedWatch() = db.watchStateDao().observeByAnimeId(100).first()?.toDomain()

    private suspend fun storedProgress() = db.episodeProgressDao().observeByAnime(100).first().map { it.toDomain() }

    @Test
    fun `a sample writes the episode's row and the anime's pointer`() = scope.runTest {
        repo.save(watch(), progress())

        assertEquals(listOf(progress()), storedProgress())
        assertEquals(watch(), storedWatch())
    }

    @Test
    fun `a later sample replaces both rows rather than adding to them`() = scope.runTest {
        repo.save(watch(positionMs = 100_000), progress(positionMs = 100_000))
        repo.save(watch(positionMs = 200_000), progress(positionMs = 200_000))

        assertEquals(listOf(200_000L), storedProgress().map { it.positionMs })
        assertEquals(200_000L, storedWatch()?.positionMs)
    }

    @Test
    fun `a sample in a different episode leaves the earlier episode's row alone`() = scope.runTest {
        repo.save(watch(episode = 7), progress(episode = 7))
        repo.save(watch(episode = 6, positionMs = 10_000), progress(episode = 6, positionMs = 10_000))

        assertEquals(listOf(6 to 10_000L, 7 to 2_400_000L), storedProgress().map { it.episode to it.positionMs })
        assertEquals(6, storedWatch()?.episode)
    }

    @Test
    fun `a sample takes the account lock once, not once per row`() = scope.runTest {
        // The headline property of the whole change, and the one every other assertion in this
        // file passes just as happily without: the sample used to be two writes with an
        // acquisition each, and a viewer's position is sampled every few seconds while a library
        // refresh can hold that lock for a second or two.
        repo.save(watch(), progress())

        assertEquals(1, lock.turns)

        repo.save(watch(positionMs = 300_000), progress(positionMs = 300_000))

        assertEquals(2, lock.turns)
    }

    @Test
    fun `a sample waits for whoever is holding the account`() = scope.runTest {
        val gate = CompletableDeferred<Unit>()
        val holder = launch { session.withAccount { gate.await() } }
        runCurrent()
        var written = false

        val writer = launch {
            repo.save(watch(), progress())
            written = true
        }
        runCurrent()
        assertFalse(written)
        assertTrue(storedProgress().isEmpty())
        assertNull(storedWatch())

        gate.complete(Unit)
        holder.join()
        writer.join()
        assertTrue(written)
        assertEquals(1, storedProgress().size)
        assertEquals(watch(), storedWatch())
    }

    @Test
    fun `a pointer that cannot be stored takes the episode's row back with it`() = scope.runTest {
        // The whole reason for one transaction: there is no state where the episode says minute
        // forty and the anime's pointer still names the episode before it.
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
            repo.save(watch(), progress())
            fail("Expected the aborted insert to surface")
        } catch (expected: StorageFailure) {
            assertTrue(expected.cause != null)
        }

        assertEquals(emptyList<EpisodeProgress>(), storedProgress())
        assertNull(storedWatch())
    }

    // --- the same two rows, taken away ----------------------------------------------------------

    @Test
    fun `forgetting from an episode drops its row and every later one`() = scope.runTest {
        repo.save(watch(episode = 3), progress(episode = 3))
        repo.save(watch(episode = 4), progress(episode = 4))
        repo.save(watch(episode = 5), progress(episode = 5))

        repo.forgetFrom(100, episode = 4, at = now.plusSeconds(60))

        assertEquals(listOf(3), storedProgress().map { it.episode })
    }

    @Test
    fun `a pointer standing on a forgotten episode is rewound and keeps its track`() = scope.runTest {
        repo.save(watch(episode = 5), progress(episode = 5))

        repo.forgetFrom(100, episode = 5, at = now.plusSeconds(60))

        val pointer = storedWatch()
        assertEquals(5, pointer?.episode)
        assertEquals(0L, pointer?.positionMs)
        assertEquals(0L, pointer?.durationMs)
        assertEquals(12, pointer?.translationId)
        assertEquals(1, pointer?.kodikSeason)
        assertEquals(now.plusSeconds(60), pointer?.updatedAt)
    }

    @Test
    fun `a pointer on an earlier episode is left exactly as it was`() = scope.runTest {
        repo.save(watch(episode = 3), progress(episode = 3))

        repo.forgetFrom(100, episode = 5, at = now.plusSeconds(60))

        assertEquals(watch(episode = 3), storedWatch())
    }

    @Test
    fun `another anime's positions are none of this one's business`() = scope.runTest {
        repo.save(watch(episode = 5), progress(episode = 5))
        val other = EpisodeProgress(200, 5, 2_400_000, 2_880_000, now)
        repo.save(WatchState(200, 5, 2_400_000, 2_880_000, 12, 1, now), other)

        repo.forgetFrom(100, episode = 5, at = now.plusSeconds(60))

        assertEquals(listOf(other), db.episodeProgressDao().observeByAnime(200).first().map { it.toDomain() })
    }

    @Test
    fun `restoring puts the rows back with the timestamps they had`() = scope.runTest {
        repo.save(watch(episode = 5), progress(episode = 5))
        val taken = storedProgress()
        repo.forgetFrom(100, episode = 5, at = now.plusSeconds(60))
        assertEquals(emptyList<EpisodeProgress>(), storedProgress())

        repo.restore(taken)

        // Their own timestamps, not «now»: when a title was last watched is read off these rows,
        // and an undo that restamped them would put something nobody watched at the top of the feed.
        assertEquals(taken, storedProgress())
    }

    @Test
    fun `restoring nothing leaves the table as it was`() = scope.runTest {
        repo.save(watch(episode = 5), progress(episode = 5))
        val untouched = storedProgress()

        repo.restore(emptyList())

        assertEquals(untouched, storedProgress())
    }

    @Test
    fun `forgetting takes the account lock once, like the sample it undoes`() = scope.runTest {
        val before = lock.turns

        repo.forgetFrom(100, episode = 5, at = now)

        assertEquals(before + 1, lock.turns)
    }

    @Test
    fun `a sample with no signed in account is refused`() = scope.runTest {
        val signedOut = RoomPlaybackSampleRepository(db, AccountSession(InMemoryTokenStore(), prefs, db), dispatcher)

        try {
            signedOut.save(watch(), progress())
            fail("Expected a sample without an account to be refused")
        } catch (expected: AccountSessionChanged) {
            assertEquals(emptyList<EpisodeProgress>(), storedProgress())
            assertNull(storedWatch())
        }
    }
}
