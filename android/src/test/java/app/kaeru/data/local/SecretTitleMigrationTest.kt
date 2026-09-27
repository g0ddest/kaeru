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

/** The migration that gives «Смотреть украдкой» a table, and leaves everything else alone. */
@RunWith(RobolectricTestRunner::class)
class SecretTitleMigrationTest {

    private lateinit var connection: SQLiteConnection

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

    private fun columnsOf(table: String): List<String> {
        connection.prepare("SELECT name, type, `notnull`, pk FROM pragma_table_info('$table')").use { statement ->
            val columns = mutableListOf<String>()
            while (statement.step()) columns += (0..3).joinToString(" ") { statement.getText(it) }
            return columns
        }
    }

    private fun freshColumnsOf(table: String): List<String> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KaeruDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            db.openHelper.writableDatabase
                .query("SELECT name, type, `notnull`, pk FROM pragma_table_info('$table')")
                .use { cursor ->
                    val columns = mutableListOf<String>()
                    while (cursor.moveToNext()) columns += (0..3).joinToString(" ") { cursor.getString(it) }
                    return columns
                }
        } finally {
            db.close()
        }
    }

    @Test
    fun `the table is the table Room would have created`() {
        MIGRATION_6_7.migrate(connection)

        assertEquals(freshColumnsOf("secret_title"), columnsOf("secret_title"))
    }

    @Test
    fun `an upgrade starts with nothing secret and keeps the positions`() {
        connection.execSQL("INSERT INTO `episode_progress` VALUES (100, 7, 600000, 1400000, 1757757600000)")

        MIGRATION_6_7.migrate(connection)

        connection.prepare("SELECT COUNT(*) FROM `secret_title`").use { statement ->
            assertTrue(statement.step())
            assertEquals(0L, statement.getLong(0))
        }
        connection.prepare("SELECT COUNT(*) FROM `episode_progress`").use { statement ->
            assertTrue(statement.step())
            assertEquals(1L, statement.getLong(0))
        }
    }
}
