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
 * The migration that gives the new-episode check somewhere to remember what it has already said.
 *
 * Worth its own test for the usual reason — the fallback the builder carries drops every table —
 * and for one of its own: an upgrade that lost these rows would greet the viewer with one
 * notification per title they follow, for episodes that came out weeks ago.
 */
@RunWith(RobolectricTestRunner::class)
class NotifiedEpisodeMigrationTest {

    private lateinit var connection: SQLiteConnection

    /** The tables version 4 leaves behind; only the two this migration must not disturb are made. */
    @Before
    fun setUp() {
        connection = AndroidSQLiteDriver().open(":memory:")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_rate` (" +
                "`id` INTEGER NOT NULL, `animeId` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                "`episodes` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `episode_progress` (" +
                "`animeId` INTEGER NOT NULL, `episode` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`animeId`, `episode`))",
        )
    }

    @After
    fun tearDown() = connection.close()

    /** Column name, declared type, nullability and place in the primary key, in declaration order. */
    private fun columnsOf(table: String): List<String> {
        connection.prepare("SELECT name, type, `notnull`, pk FROM pragma_table_info('$table')").use { statement ->
            val columns = mutableListOf<String>()
            while (statement.step()) {
                columns += (0..3).joinToString(" ") { statement.getText(it) }
            }
            return columns
        }
    }

    private fun freshColumnsOf(table: String): List<String> {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KaeruDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            db.openHelper.writableDatabase
                .query("SELECT name, type, `notnull`, pk FROM pragma_table_info('$table')")
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

    private fun rows(): List<List<String>> {
        connection.prepare("SELECT `animeId`, `episode`, `notifiedAt` FROM `notified_episodes` ORDER BY `animeId`, `episode`")
            .use { statement ->
                val found = mutableListOf<List<String>>()
                while (statement.step()) {
                    found += (0..2).map { statement.getLong(it).toString() }
                }
                return found
            }
    }

    @Test
    fun `the table is the table Room would have created`() {
        MIGRATION_4_5.migrate(connection)

        assertEquals(freshColumnsOf("notified_episodes"), columnsOf("notified_episodes"))
    }

    @Test
    fun `a title and an episode can be written and read back`() {
        MIGRATION_4_5.migrate(connection)

        connection.execSQL("INSERT INTO `notified_episodes` VALUES (100, 7, 1789732800000)")

        assertEquals(listOf(listOf("100", "7", "1789732800000")), rows())
    }

    @Test
    fun `the same pair cannot be written twice`() {
        MIGRATION_4_5.migrate(connection)

        connection.execSQL("INSERT INTO `notified_episodes` VALUES (100, 7, 1789732800000)")
        connection.execSQL("INSERT OR IGNORE INTO `notified_episodes` VALUES (100, 7, 1789819200000)")

        assertEquals(listOf(listOf("100", "7", "1789732800000")), rows())
    }

    @Test
    fun `an upgrade starts with nothing said, so the first check after it is silent`() {
        MIGRATION_4_5.migrate(connection)

        assertTrue(rows().isEmpty())
    }

    @Test
    fun `the rows the check reads are left exactly as they were`() {
        connection.execSQL("INSERT INTO `user_rate` VALUES (5, 100, 'WATCHING', 6, 1757757600000)")
        connection.execSQL("INSERT INTO `episode_progress` VALUES (100, 7, 600000, 1400000, 1757757600000)")

        MIGRATION_4_5.migrate(connection)

        connection.prepare("SELECT `animeId`, `episodes` FROM `user_rate`").use { statement ->
            assertTrue(statement.step())
            assertEquals(listOf(100L, 6L), (0..1).map { statement.getLong(it) })
        }
        connection.prepare("SELECT `animeId`, `episode`, `positionMs` FROM `episode_progress`").use { statement ->
            assertTrue(statement.step())
            assertEquals(listOf(100L, 7L, 600_000L), (0..2).map { statement.getLong(it) })
        }
    }
}
