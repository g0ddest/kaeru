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
 * The migration that gives the opening and the ending somewhere to be remembered.
 *
 * Worth its own test for the usual reason — the fallback the builder carries drops every table,
 * and the table beside this one holds the viewer's positions — and for one of its own: these rows
 * are what makes the buttons work with no network, so an upgrade that lost them would take the
 * feature off every downloaded episode until each was played online again.
 */
@RunWith(RobolectricTestRunner::class)
class SkipMarksMigrationTest {

    private lateinit var connection: SQLiteConnection

    /** The one table version 5 leaves behind that this migration must not disturb. */
    @Before
    fun setUp() {
        connection = AndroidSQLiteDriver().open(":memory:")
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

    @Test
    fun `the table is the table Room would have created`() {
        MIGRATION_5_6.migrate(connection)

        assertEquals(freshColumnsOf("skip_marks"), columnsOf("skip_marks"))
    }

    @Test
    fun `an episode with both marks can be written and read back`() {
        MIGRATION_5_6.migrate(connection)

        connection.execSQL(
            "INSERT INTO `skip_marks` VALUES (100, 1, 1560, 3000, 93000, 1460000, 1560000, 1789732800000)",
        )

        connection.prepare("SELECT `animeId`, `episode`, `lengthSec`, `opStart`, `edEnd` FROM `skip_marks`")
            .use { statement ->
                assertTrue(statement.step())
                assertEquals(listOf(100L, 1L, 1560L, 3000L, 1_560_000L), (0..4).map { statement.getLong(it) })
            }
    }

    @Test
    fun `an episode nobody marked is a row with nothing in it`() {
        MIGRATION_5_6.migrate(connection)

        connection.execSQL("INSERT INTO `skip_marks` VALUES (100, 2, 1446, NULL, NULL, NULL, NULL, 1789732800000)")

        connection.prepare("SELECT `opStart` FROM `skip_marks`").use { statement ->
            assertTrue(statement.step())
            assertTrue(statement.isNull(0))
        }
    }

    @Test
    fun `the same episode in two lengths is two rows`() {
        MIGRATION_5_6.migrate(connection)

        connection.execSQL("INSERT INTO `skip_marks` VALUES (100, 1, 1560, 3000, 93000, NULL, NULL, 1789732800000)")
        connection.execSQL("INSERT INTO `skip_marks` VALUES (100, 1, 1446, 3000, 93000, NULL, NULL, 1789732800000)")

        connection.prepare("SELECT COUNT(*) FROM `skip_marks`").use { statement ->
            assertTrue(statement.step())
            assertEquals(2L, statement.getLong(0))
        }
    }

    @Test
    fun `an upgrade starts with nothing known, so the first episode asks`() {
        MIGRATION_5_6.migrate(connection)

        connection.prepare("SELECT COUNT(*) FROM `skip_marks`").use { statement ->
            assertTrue(statement.step())
            assertEquals(0L, statement.getLong(0))
        }
    }

    @Test
    fun `the positions the viewer has are left exactly as they were`() {
        connection.execSQL("INSERT INTO `episode_progress` VALUES (100, 7, 600000, 1400000, 1757757600000)")

        MIGRATION_5_6.migrate(connection)

        connection.prepare("SELECT `animeId`, `episode`, `positionMs` FROM `episode_progress`").use { statement ->
            assertTrue(statement.step())
            assertEquals(listOf(100L, 7L, 600_000L), (0..2).map { statement.getLong(it) })
        }
    }
}
