package app.kaeru.data.playback

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.kaeru.data.local.KaeruDatabase
import app.kaeru.data.local.toEntity
import app.kaeru.domain.model.EpisodeProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The per-episode positions, as the screens read them.
 *
 * Reads only, because that is all this repository does: a position is written as half of a playback
 * sample and the only door to that is [RoomPlaybackSampleRepository], which has its own suite. Rows
 * are seeded through the DAO here — going through the sample repository would drag the anime's
 * pointer and the account lock into tests that are about a flow of episode rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-television-notnight-mdpi")
class RoomEpisodeProgressRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val now: Instant = Instant.parse("2026-09-13T10:00:00Z")
    private lateinit var db: KaeruDatabase
    private lateinit var repo: RoomEpisodeProgressRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        repo = RoomEpisodeProgressRepository(db.episodeProgressDao())
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun progress(
        animeId: Int = 100,
        episode: Int = 7,
        positionMs: Long = 2_400_000,
        durationMs: Long = 2_880_000,
    ) = EpisodeProgress(animeId, episode, positionMs, durationMs, now)

    private suspend fun seed(vararg rows: EpisodeProgress) =
        rows.forEach { db.episodeProgressDao().upsert(it.toEntity()) }

    @Test
    fun `a stored position comes back whole`() = scope.runTest {
        seed(progress())

        assertEquals(listOf(progress()), repo.observe(100).first())
    }

    @Test
    fun `two episodes of one anime are two rows, not one`() = scope.runTest {
        seed(progress(episode = 7, positionMs = 2_400_000), progress(episode = 6, positionMs = 10_000))

        assertEquals(
            listOf(progress(episode = 6, positionMs = 10_000), progress(episode = 7, positionMs = 2_400_000)),
            repo.observe(100).first(),
        )
    }

    @Test
    fun `an anime nobody has watched observes as an empty list`() = scope.runTest {
        seed(progress(animeId = 200))

        assertEquals(emptyList<EpisodeProgress>(), repo.observe(100).first())
    }

    @Test
    fun `the flow follows the rows as they are written and taken away`() = scope.runTest {
        repo.observe(100).test {
            assertEquals(emptyList<EpisodeProgress>(), awaitItem())

            seed(progress(episode = 7, positionMs = 10_000))
            assertEquals(listOf(10_000L), awaitItem().map { it.positionMs })

            seed(progress(episode = 8, positionMs = 20_000))
            assertEquals(listOf(10_000L, 20_000L), awaitItem().map { it.positionMs })

            db.clearAccountData()
            assertEquals(emptyList<EpisodeProgress>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `another anime being written does not disturb this one`() = scope.runTest {
        seed(progress())

        repo.observe(100).test {
            assertEquals(listOf(progress()), awaitItem())

            seed(progress(animeId = 200, positionMs = 1_000))
            advanceUntilIdle()

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `signing out takes every episode with it`() = scope.runTest {
        seed(progress(animeId = 100), progress(animeId = 200))

        db.clearAccountData()

        assertEquals(emptyList<EpisodeProgress>(), repo.observe(100).first())
        assertEquals(emptyList<EpisodeProgress>(), repo.observe(200).first())
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
