package app.kaeru.ui.mobile.library

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class LibraryViewModelTest {
    private fun item(id: Int, title: String, status: ListStatus, updated: String) = LibraryEntry(
        Anime(id, title, title, null, emptyList(), AnimeStatus.RELEASED, 12, 12, null, null, 2026, null, null),
        UserRate(id.toLong(), id, status, id, Instant.parse(updated)),
        null,
    )

    @Test
    fun `selected status is filtered then sorted by recent activity`() {
        val items = listOf(
            item(1, "А", ListStatus.WATCHING, "2026-09-01T00:00:00Z"),
            item(2, "Б", ListStatus.WATCHING, "2026-09-10T00:00:00Z"),
            item(3, "В", ListStatus.PLANNED, "2026-09-11T00:00:00Z"),
        )
        assertEquals(listOf(2, 1), selectLibrary(items, ListStatus.WATCHING, LibrarySort.UPDATED).map { it.anime.id })
        assertEquals(listOf(1, 2), selectLibrary(items, ListStatus.WATCHING, LibrarySort.TITLE).map { it.anime.id })
    }
}
