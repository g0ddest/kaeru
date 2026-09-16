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
 * The migration that gives offline marks somewhere to wait.
 *
 * Worth its own test for the usual reason — the fallback the builder carries drops every table —
 * and for one more: this table is the only copy of a write the viewer already believes happened,
 * so a shape Room refuses to open is a shape that loses their marks on the next upgrade.
 */
@RunWith(RobolectricTestRunner::class)
class RateOutboxMigrationTest {

    private lateinit var connection: SQLiteConnection

    /** The tables exactly as version 3 of the database leaves them; only `user_rate` is needed here. */
    @Before
    fun setUp() {
        connection = AndroidSQLiteDriver().open(":memory:")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_rate` (" +
                "`id` INTEGER NOT NULL, `animeId` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                "`episodes` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
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

    private fun indices(): List<String> {
        connection.prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'rate_outbox'")
            .use { statement ->
                val names = mutableListOf<String>()
                while (statement.step()) names += statement.getText(0)
                return names
            }
    }

    private fun rows(): List<List<String>> {
        connection.prepare("SELECT `id`, `animeId`, `kind`, `value`, `createdAt` FROM `rate_outbox` ORDER BY `id`")
            .use { statement ->
                val found = mutableListOf<List<String>>()
                while (statement.step()) {
                    found += listOf(
                        statement.getLong(0).toString(), statement.getLong(1).toString(),
                        statement.getText(2), statement.getText(3), statement.getLong(4).toString(),
                    )
                }
                return found
            }
    }

    @Test
    fun `the queue table is the table Room would have created`() {
        MIGRATION_3_4.migrate(connection)

        assertEquals(freshColumnsOf("rate_outbox"), columnsOf("rate_outbox"))
    }

    @Test
    fun `a queued mark can be written and read back`() {
        MIGRATION_3_4.migrate(connection)

        connection.execSQL("INSERT INTO `rate_outbox` (`animeId`, `kind`, `value`, `createdAt`) VALUES (100, 'EPISODES', '7', 1757757600000)")
        connection.execSQL("INSERT INTO `rate_outbox` (`animeId`, `kind`, `value`, `createdAt`) VALUES (100, 'STATUS', 'completed', 1757757600001)")

        assertEquals(
            listOf(
                listOf("1", "100", "EPISODES", "7", "1757757600000"),
                listOf("2", "100", "STATUS", "completed", "1757757600001"),
            ),
            rows(),
        )
    }

    @Test
    fun `the anime a queued mark waits on is indexed`() {
        MIGRATION_3_4.migrate(connection)

        assertTrue(indices().contains("index_rate_outbox_animeId"))
    }

    @Test
    fun `the rates the queue speaks for are left exactly as they were`() {
        connection.execSQL("INSERT INTO `user_rate` VALUES (5, 100, 'WATCHING', 6, 1757757600000)")

        MIGRATION_3_4.migrate(connection)

        connection.prepare("SELECT `animeId`, `episodes` FROM `user_rate`").use { statement ->
            assertTrue(statement.step())
            assertEquals(listOf(100L, 6L), (0..1).map { statement.getLong(it) })
        }
    }
}
