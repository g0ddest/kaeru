package app.kaeru.data.local

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The migration that gives every episode its own position.
 *
 * Worth its own test twice over: the fallback the builder carries is destructive, and this
 * migration does not only create a table — it carries the one position an install already has
 * into it, which is the difference between upgrading mid-episode and starting that episode again.
 */
@RunWith(RobolectricTestRunner::class)
class EpisodeProgressMigrationTest {

    private lateinit var connection: SQLiteConnection

    /** The `watch_state` table exactly as version 2 of the database leaves it. */
    @Before
    fun setUp() {
        connection = AndroidSQLiteDriver().open(":memory:")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `watch_state` (" +
                "`animeId` INTEGER NOT NULL, `episode` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, `translationId` INTEGER, `kodikSeason` INTEGER, " +
                "`updatedAt` INTEGER NOT NULL, `translationTitle` TEXT, PRIMARY KEY(`animeId`))",
        )
    }

    @After
    fun tearDown() = connection.close()

    private fun rows(): List<List<Long>> {
        connection.prepare(
            "SELECT `animeId`, `episode`, `positionMs`, `durationMs`, `updatedAt` " +
                "FROM `episode_progress` ORDER BY `animeId`, `episode`",
        ).use { statement ->
            val found = mutableListOf<List<Long>>()
            while (statement.step()) {
                found += (0..4).map { statement.getLong(it) }
            }
            return found
        }
    }

    /**
     * Column name, declared type, nullability and place in the primary key, in the order the
     * table declares them — which is the whole of what Room compares a table against on open. A
     * key that came out as `animeId` alone would pass every other test here and then refuse to
     * open on the one install that matters: an upgraded one.
     */
    private fun migratedColumns(): List<String> {
        connection.prepare("SELECT name, type, `notnull`, pk FROM pragma_table_info('episode_progress')").use { statement ->
            val columns = mutableListOf<String>()
            while (statement.step()) {
                columns += (0..3).joinToString(" ") { statement.getText(it) }
            }
            return columns
        }
    }

    private fun freshColumns(): List<String> {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KaeruDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            db.openHelper.writableDatabase
                .query("SELECT name, type, `notnull`, pk FROM pragma_table_info('episode_progress')")
                .use { cursor ->
                    val columns = mutableListOf<String>()
                    while (cursor.moveToNext()) {
                        columns += (0..3).joinToString(" ") { cursor.getString(it) }
                    }
                    return columns
                }
        } finally {
            db.close()
        }
    }

    @Test
    fun `an upgraded table is the table Room would have created`() {
        MIGRATION_2_3.migrate(connection)

        assertEquals(freshColumns(), migratedColumns())
    }

    @Test
    fun `the episode the viewer was in the middle of survives the upgrade`() {
        connection.execSQL(
            "INSERT INTO `watch_state` VALUES (100, 7, 2400000, 1440000, 11, 1, 1757757600000, 'AniLibria.TV')",
        )

        MIGRATION_2_3.migrate(connection)

        assertEquals(listOf(listOf(100L, 7L, 2_400_000L, 1_440_000L, 1_757_757_600_000L)), rows())
    }

    @Test
    fun `an anime with nowhere to resume brings no row across`() {
        connection.execSQL(
            "INSERT INTO `watch_state` VALUES (100, 1, 0, 0, 11, 1, 1757757600000, NULL)",
        )

        MIGRATION_2_3.migrate(connection)

        assertTrue(rows().isEmpty())
    }

    @Test
    fun `the watch state row is left exactly as it was`() {
        connection.execSQL(
            "INSERT INTO `watch_state` VALUES (100, 7, 2400000, 1440000, 11, 1, 1757757600000, 'AniLibria.TV')",
        )

        MIGRATION_2_3.migrate(connection)

        connection.prepare("SELECT `episode`, `positionMs`, `translationId` FROM `watch_state`").use { statement ->
            assertTrue(statement.step())
            assertEquals(listOf(7L, 2_400_000L, 11L), (0..2).map { statement.getLong(it) })
        }
    }

    @Test
    fun `the upgraded table keeps one row per episode rather than one per anime`() {
        MIGRATION_2_3.migrate(connection)
        connection.execSQL("INSERT INTO `episode_progress` VALUES (100, 6, 10000, 1440000, 1757757600000)")
        connection.execSQL("INSERT INTO `episode_progress` VALUES (100, 7, 2400000, 1440000, 1757757600000)")

        assertEquals(
            listOf(
                listOf(100L, 6L, 10_000L, 1_440_000L, 1_757_757_600_000L),
                listOf(100L, 7L, 2_400_000L, 1_440_000L, 1_757_757_600_000L),
            ),
            rows(),
        )
    }
}
