package app.kaeru.ui.mobile.library

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LibraryTabsTest {

    private fun entry(
        id: Int,
        status: ListStatus = ListStatus.WATCHING,
        watched: Int = 0,
        episodes: Int = 12,
        aired: Int = 12,
        animeStatus: AnimeStatus = AnimeStatus.RELEASED,
    ) = LibraryEntry(
        Anime(id, "Т$id", "T$id", null, emptyList(), animeStatus, episodes, aired, null, null, 2026, null, null),
        UserRate(id.toLong(), id, status, watched, Instant.parse("2026-09-01T00:00:00Z")),
        null,
    )

    @Test
    fun `every status gets a tab and the counts come from the whole list`() {
        val counts = libraryCounts(
            sortKeys(
                listOf(
                    entry(1, ListStatus.WATCHING),
                    entry(2, ListStatus.WATCHING),
                    entry(3, ListStatus.PLANNED),
                    entry(4, ListStatus.DROPPED),
                ),
            ),
        )
        val tabs = libraryTabs(counts)
        assertEquals(ListStatus.entries.size, tabs.size)
        assertEquals(2, tabs.single { it.status == ListStatus.WATCHING }.count)
        assertEquals(1, tabs.single { it.status == ListStatus.PLANNED }.count)
        assertEquals(0, tabs.single { it.status == ListStatus.COMPLETED }.count)
        assertEquals(1, tabs.single { it.status == ListStatus.DROPPED }.count)
    }

    @Test
    fun `the three the viewer opens most come first`() {
        val tabs = libraryTabs(emptyMap())
        assertEquals(
            listOf(ListStatus.WATCHING, ListStatus.PLANNED, ListStatus.COMPLETED),
            tabs.take(3).map { it.status },
        )
    }

    @Test
    fun `a tab reads as a label and a number with nothing between them`() {
        val tabs = libraryTabs(mapOf(ListStatus.WATCHING to 12, ListStatus.PLANNED to 40, ListStatus.COMPLETED to 128))
        assertEquals("Смотрю 12", tabs.single { it.status == ListStatus.WATCHING }.text)
        assertEquals("В планах 40", tabs.single { it.status == ListStatus.PLANNED }.text)
        assertEquals("Завершено 128", tabs.single { it.status == ListStatus.COMPLETED }.text)
        tabs.forEach { tab ->
            assertTrue(tab.text, tab.text.none { it == '·' || it == '(' || it == ')' })
            // Sentence case: a label that equals its own uppercase form is shouting.
            assertNotEquals(tab.text, tab.text.uppercase())
        }
    }

    @Test
    fun `a started title counts up to the season length`() {
        assertEquals("7 из 28", libraryCardSubtitle(entry(1, watched = 7, episodes = 28, aired = 24)))
    }

    @Test
    fun `an untouched title says how long the season is instead of counting from zero`() {
        assertEquals("28 серий", libraryCardSubtitle(entry(1, ListStatus.PLANNED, watched = 0, episodes = 28, aired = 0)))
    }

    @Test
    fun `an ongoing season counts against what has aired when the total is unknown`() {
        assertEquals(
            "3 из 5",
            libraryCardSubtitle(entry(1, watched = 3, episodes = 0, aired = 5, animeStatus = AnimeStatus.ONGOING)),
        )
    }

    @Test
    fun `a title with nothing aired and nothing watched says nothing`() {
        assertNull(
            libraryCardSubtitle(entry(1, ListStatus.PLANNED, watched = 0, episodes = 0, aired = 0, animeStatus = AnimeStatus.ANONS)),
        )
    }
}
