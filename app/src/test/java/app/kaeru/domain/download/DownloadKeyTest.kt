package app.kaeru.domain.download

import app.kaeru.domain.model.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The id is the only thing media3 carries for us: a download is a row keyed by a string, and
 * everything the app knows about it — which anime, which episode, which track, which height —
 * has to survive the trip out to that row and back.
 */
class DownloadKeyTest {

    @Test
    fun `a key survives the round trip through its id`() {
        val key = DownloadKey(animeId = 52991, episode = 7, translationId = 609, quality = Quality.P720)

        assertEquals("52991:7:609:720", key.id)
        assertEquals(key, DownloadKey.parse(key.id))
    }

    @Test
    fun `every rung of the ladder round trips`() {
        Quality.entries.forEach { quality ->
            val key = DownloadKey(1, 1, 1, quality)
            assertEquals(key, DownloadKey.parse(key.id))
        }
    }

    @Test
    fun `junk is not a key`() {
        // Anything the manager holds that this app did not put there — a leftover from an older
        // id scheme, say — has to read as "not mine" rather than as episode 0 of anime 0.
        listOf(
            "",
            "not an id",
            "52991:7:609",
            "52991:7:609:720:extra",
            "52991:7:609:",
            ":7:609:720",
            "52991:seven:609:720",
            "52991:7:609:1440",
            "52991 : 7 : 609 : 720",
        ).forEach { junk ->
            assertNull("«$junk» should not parse", DownloadKey.parse(junk))
        }
    }

    @Test
    fun `a height nobody offers is not a key`() {
        // A rung a future build drops has to read as unknown rather than as the nearest one:
        // guessing here would hand the player a file it never downloaded.
        assertNull(DownloadKey.parse("1:1:1:0"))
        assertNull(DownloadKey.parse("1:1:1:2160"))
    }
}
