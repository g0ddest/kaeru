package app.kaeru.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The dedup rows: written once, kept as first written, and gone with the account. */
@RunWith(RobolectricTestRunner::class)
class NotifiedEpisodeDaoTest {
    private lateinit var db: KaeruDatabase
    private val monday = Instant.parse("2026-09-14T09:00:00Z")
    private val tuesday = Instant.parse("2026-09-15T09:00:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KaeruDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun row(animeId: Int, episode: Int, at: Instant = monday) =
        NotifiedEpisodeEntity(animeId, episode, at)

    @Test
    fun `a pair written twice keeps the first time it was written`() = runTest {
        db.notifiedEpisodeDao().recordAll(listOf(row(100, 7)))

        db.notifiedEpisodeDao().recordAll(listOf(row(100, 7, tuesday)))

        assertEquals(listOf(row(100, 7)), db.notifiedEpisodeDao().getAll())
    }

    @Test
    fun `two episodes of one title are two rows`() = runTest {
        db.notifiedEpisodeDao().recordAll(listOf(row(100, 7), row(100, 8), row(200, 1)))

        assertEquals(
            listOf(100 to 7, 100 to 8, 200 to 1),
            db.notifiedEpisodeDao().getAll().map { it.animeId to it.episode }.sortedBy { it.first * 100 + it.second },
        )
    }

    @Test
    fun `a title keeps its fifty newest rows and loses the rest`() = runTest {
        val dao = db.notifiedEpisodeDao()
        dao.recordAll((1..60).map { row(100, it) })

        dao.prune(100, keep = 50)

        assertEquals((11..60).toList(), dao.getForAnime(listOf(100)).map { it.episode }.sorted())
    }

    @Test
    fun `pruning one title leaves every other title alone`() = runTest {
        val dao = db.notifiedEpisodeDao()
        dao.recordAll((1..60).map { row(100, it) } + (1..60).map { row(200, it) })

        dao.prune(100, keep = 50)

        assertEquals(60, dao.getForAnime(listOf(200)).size)
    }

    @Test
    fun `a title with less than the limit loses nothing`() = runTest {
        val dao = db.notifiedEpisodeDao()
        dao.recordAll(listOf(row(100, 7), row(100, 8)))

        dao.prune(100, keep = 50)

        assertEquals(2, dao.getForAnime(listOf(100)).size)
    }

    @Test
    fun `the check reads only the titles it is about`() = runTest {
        val dao = db.notifiedEpisodeDao()
        dao.recordAll(listOf(row(100, 7), row(200, 1), row(300, 4)))

        assertEquals(
            listOf(100 to 7, 300 to 4),
            dao.getForAnime(listOf(100, 300)).map { it.animeId to it.episode }.sortedBy { it.first },
        )
    }

    @Test
    fun `signing out takes what was said with it`() = runTest {
        db.notifiedEpisodeDao().recordAll(listOf(row(100, 7)))

        db.clearAccountData()

        assertTrue(db.notifiedEpisodeDao().getAll().isEmpty())
    }
}
