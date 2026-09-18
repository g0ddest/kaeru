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
    fun `signing out takes what was said with it`() = runTest {
        db.notifiedEpisodeDao().recordAll(listOf(row(100, 7)))

        db.clearAccountData()

        assertTrue(db.notifiedEpisodeDao().getAll().isEmpty())
    }
}
