package app.kaeru.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
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

    @Test
    fun `the upgraded table takes a name`() {
        MIGRATION_1_2.migrate(connection)
        connection.execSQL(
            "INSERT INTO `watch_state` VALUES (100, 4, 320000, 1440000, 11, 1, 1757757600000, 'AniLibria.TV')",
        )

        assertEquals("AniLibria.TV", column("translationTitle"))
    }
}
