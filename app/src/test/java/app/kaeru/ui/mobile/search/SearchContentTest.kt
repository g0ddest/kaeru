package app.kaeru.ui.mobile.search

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchContentTest {
    private val frieren = Anime(7, "Фрирен", "Frieren", null, emptyList(), AnimeStatus.RELEASED, 28, 28, null, 9.1, 2023, null, null)

    @Test
    fun `a screen nobody has searched on yet invites rather than reports nothing found`() {
        assertEquals(SearchContent.Idle, searchContentState(SearchUiState()))
    }

    @Test
    fun `typing alone does not turn the screen into a result`() {
        assertEquals(SearchContent.Idle, searchContentState(SearchUiState(query = "фри")))
    }

    @Test
    fun `a search in flight is the skeleton grid, even over results from the last one`() {
        assertEquals(
            SearchContent.Loading,
            searchContentState(SearchUiState(query = "фри", searching = true, results = listOf(frieren))),
        )
    }

    @Test
    fun `results win once they land`() {
        assertEquals(
            SearchContent.Results,
            searchContentState(SearchUiState(query = "фри", results = listOf(frieren), recentQueries = listOf("фри"))),
        )
    }

    @Test
    fun `a search that found nothing is told apart from one that never ran`() {
        assertEquals(
            SearchContent.NotFound,
            searchContentState(SearchUiState(query = "ыыы", recentQueries = listOf("ыыы"))),
        )
    }

    @Test
    fun `a failed search explains itself instead of claiming nothing was found`() {
        assertEquals(
            SearchContent.Error("Нет соединения. Проверьте интернет"),
            searchContentState(
                SearchUiState(
                    query = "фри",
                    recentQueries = listOf("фри"),
                    errorMessage = "Нет соединения. Проверьте интернет",
                ),
            ),
        )
    }

    @Test
    fun `a write that failed never takes the results off the screen`() {
        assertEquals(
            SearchContent.Results,
            searchContentState(
                SearchUiState(
                    query = "фри",
                    results = listOf(frieren),
                    recentQueries = listOf("фри"),
                    addFailure = AddFailure(7, "Нет соединения. Проверьте интернет"),
                ),
            ),
        )
    }

    @Test
    fun `the card action says what it will do, what it is doing, and what is already done`() {
        val state = SearchUiState(results = listOf(frieren), libraryIds = setOf(9), addingAnimeId = 8)
        assertEquals(AddAction(label = "В планы", enabled = true), addAction(state, animeId = 7))
        assertEquals(AddAction(label = "Добавляем…", enabled = false), addAction(state, animeId = 8))
        assertEquals(AddAction(label = "В списке", enabled = false), addAction(state, animeId = 9))
    }
}
