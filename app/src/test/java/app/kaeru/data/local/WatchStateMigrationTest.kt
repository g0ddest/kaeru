package app.kaeru.data.local

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one migration this database has.
 *
 * Worth its own test because the alternative the build is configured with is destructive: a
 * version bump without a migration drops every table, and the table it would drop is the only
 * place a viewer's playback positions live.
 */
@RunWith(RobolectricTestRunner::class)
class WatchStateMigrationTest {

    private lateinit var connection: SQLiteConnection

    /** The `watch_state` table exactly as version 1 of the database created it. */
    @Before
    fun setUp() {
        connection = AndroidSQLiteDriver().open(":memory:")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `watch_state` (" +
                "`animeId` INTEGER NOT NULL, `episode` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, `translationId` INTEGER, `kodikSeason` INTEGER, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`animeId`))",
        )
    }

    @After
    fun tearDown() = connection.close()

    private fun column(name: String): String? {
        connection.prepare("SELECT `$name` FROM `watch_state` WHERE `animeId` = 100").use { statement ->
            if (!statement.step() || statement.isNull(0)) return null
            return statement.getText(0)
        }
    }

    private fun number(name: String): Long? {
        connection.prepare("SELECT `$name` FROM `watch_state` WHERE `animeId` = 100").use { statement ->
            return if (statement.step()) statement.getLong(0) else null
        }
    }

    @Test
    fun `a position saved before the dub's name was stored survives the upgrade`() {
        connection.execSQL(
            "INSERT INTO `watch_state` VALUES (100, 4, 320000, 1440000, 11, 1, 1757757600000)",
        )

        MIGRATION_1_2.migrate(connection)

        assertEquals(4L, number("episode"))
        assertEquals(320_000L, number("positionMs"))
        assertEquals(11L, number("translationId"))
    }

    @Test
    fun `a row that predates the column has no name to show`() {
        connection.execSQL(
            "INSERT INTO `watch_state` VALUES (100, 4, 320000, 1440000, 11, 1, 1757757600000)",
        )

        MIGRATION_1_2.migrate(connection)

        assertNull(column("translationTitle"))
    }

    /** Column name, declared type and nullability, in the order the table declares them. */
    private fun migratedColumns(): List<String> {
        connection.prepare("SELECT name, type, `notnull` FROM pragma_table_info('watch_state')").use { statement ->
            val columns = mutableListOf<String>()
            while (statement.step()) {
                columns += "${statement.getText(0)} ${statement.getText(1)} ${statement.getLong(2)}"
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
                .query("SELECT name, type, `notnull` FROM pragma_table_info('watch_state')")
                .use { cursor ->
                    val columns = mutableListOf<String>()
                    while (cursor.moveToNext()) {
                        columns += "${cursor.getString(0)} ${cursor.getString(1)} ${cursor.getLong(2)}"
                    }
                    return columns
                }
        } finally {
            db.close()
        }
    }

    /**
     * The check the database's own build cannot make for this migration: `exportSchema` starts at
     * version 2, so there is no version 1 for `MigrationTestHelper` to open. Comparing the migrated
     * table with the one Room creates from scratch answers the same question — an upgraded install
     * and a fresh one must not end up with different tables.
     */
    @Test
    fun `an upgraded table is the table Room would have created`() {
        MIGRATION_1_2.migrate(connection)

        assertEquals(freshColumns(), migratedColumns())
    }

    @Test
    fun `the upgraded table takes a name`() {
        MIGRATION_1_2.migrate(connection)
        connection.execSQL(
            "INSERT INTO `watch_state` VALUES (100, 4, 320000, 1440000, 11, 1, 1757757600000, 'AniLibria.TV')",
        )

        assertEquals("AniLibria.TV", column("translationTitle"))
    }
}
