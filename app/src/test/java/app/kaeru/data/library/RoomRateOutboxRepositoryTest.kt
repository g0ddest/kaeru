package app.kaeru.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.domain.sync.RateOp
import app.kaeru.domain.sync.RateOpKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import app.kaeru.data.local.RateOutboxEntity

/** The queue of marks a viewer made without a network, as the syncer and a refresh read it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RoomRateOutboxRepositoryTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val now: Instant = Instant.parse("2026-09-15T10:00:00Z")
    private lateinit var db: KaeruDatabase
    private lateinit var repo: RoomRateOutboxRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        repo = RoomRateOutboxRepository(db.rateOutboxDao(), Clock.fixed(now, ZoneOffset.UTC))
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    @Test
    fun `an enqueued write comes back whole, stamped with the moment it was made`() = scope.runTest {
        val id = repo.enqueue(100, RateOpKind.EPISODES, "7")

        assertEquals(listOf(RateOp(id, 100, RateOpKind.EPISODES, "7", now)), repo.observeAll().first())
    }

    @Test
    fun `ids are handed out in the order the viewer acted`() = scope.runTest {
        repo.enqueue(100, RateOpKind.EPISODES, "7")
        repo.enqueue(200, RateOpKind.STATUS, "completed")
        repo.enqueue(100, RateOpKind.EPISODES, "8")

        val queue = repo.observeAll().first()
        assertEquals(listOf("7", "completed", "8"), queue.map { it.value })
        assertEquals(queue.map { it.id }.sorted(), queue.map { it.id })
    }

    @Test
    fun `the anime still waiting are named once each`() = scope.runTest {
        repo.enqueue(100, RateOpKind.EPISODES, "7")
        repo.enqueue(100, RateOpKind.STATUS, "watching")
        repo.enqueue(200, RateOpKind.EPISODES, "1")

        assertEquals(setOf(100, 200), repo.observePendingAnimeIds().first())
    }

    @Test
    fun `a write that reached Shikimori stops being pending`() = scope.runTest {
        val first = repo.enqueue(100, RateOpKind.EPISODES, "7")
        repo.enqueue(200, RateOpKind.EPISODES, "1")

        repo.remove(first)

        assertEquals(setOf(200), repo.observePendingAnimeIds().first())
        assertEquals(listOf(200), repo.observeAll().first().map { it.animeId })
    }

    @Test
    fun `the queue is watched, not polled`() = scope.runTest {
        repo.observeAll().test {
            assertEquals(emptyList<RateOp>(), awaitItem())

            repo.enqueue(100, RateOpKind.EPISODES, "7")
            assertEquals(listOf("7"), awaitItem().map { it.value })

            repo.clear()
            assertEquals(emptyList<RateOp>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the anime still waiting can be asked for once instead of watched`() = scope.runTest {
        repo.enqueue(100, RateOpKind.EPISODES, "7")
        repo.enqueue(100, RateOpKind.STATUS, "watching")
        repo.enqueue(200, RateOpKind.EPISODES, "1")

        assertEquals(setOf(100, 200), repo.pendingAnimeIds())
    }

    @Test
    fun `one title can be asked about on its own, which is what every write does`() = scope.runTest {
        repo.enqueue(100, RateOpKind.EPISODES, "7")

        assertTrue(repo.hasPendingFor(100))
        assertFalse(repo.hasPendingFor(200))
    }

    @Test
    fun `a row of a kind this build cannot read is passed over rather than thrown on`() = scope.runTest {
        db.rateOutboxDao().insert(RateOutboxEntity(animeId = 100, kind = "FUTURE", value = "?", createdAt = now))
        repo.enqueue(200, RateOpKind.EPISODES, "1")

        assertEquals(listOf(200), repo.observeAll().first().map { it.animeId })
    }

    @Test
    fun `signing out leaves nothing queued against the next account`() = scope.runTest {
        repo.enqueue(100, RateOpKind.EPISODES, "7")
        repo.enqueue(200, RateOpKind.STATUS, "completed")

        db.clearAccountData()
        advanceUntilIdle()

        assertEquals(emptyList<RateOp>(), repo.observeAll().first())
        assertEquals(emptySet<Int>(), repo.observePendingAnimeIds().first())
    }
}
