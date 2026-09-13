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

    /**
     * Recent queries used to stand in for «a search has run». They are about to be persisted, and a
     * screen opened with yesterday's chips on it must still say «Что посмотреть сегодня?».
     */
    @Test
    fun `remembered queries alone are not a search that ran`() {
        assertEquals(SearchContent.Idle, searchContentState(SearchUiState(recentQueries = listOf("фрирен"))))
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
            searchContentState(SearchUiState(query = "фри", results = listOf(frieren), hasSearched = true)),
        )
    }

    @Test
    fun `a search that found nothing is told apart from one that never ran`() {
        assertEquals(
            SearchContent.NotFound,
            searchContentState(SearchUiState(query = "ыыы", hasSearched = true)),
        )
    }

    @Test
    fun `a failed search explains itself instead of claiming nothing was found`() {
        assertEquals(
            SearchContent.Error("Нет соединения. Проверьте интернет"),
            searchContentState(
                SearchUiState(
                    query = "фри",
                    hasSearched = true,
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
                    hasSearched = true,
                    addFailure = AddFailure(7, "Нет соединения. Проверьте интернет", event = 1),
                ),
            ),
        )
    }

    @Test
    fun `the card action says what it will do, what it is doing, and what is already done`() {
        val inList = setOf(9)
        assertEquals(AddAction(label = "В планы", enabled = true), addAction(inList, adding = 8, animeId = 7))
        assertEquals(AddAction(label = "Добавляем…", enabled = false), addAction(inList, adding = 8, animeId = 8))
        assertEquals(AddAction(label = "В списке", enabled = false), addAction(inList, adding = 8, animeId = 9))
    }

    /** The title landing in the list wins over a write still marked pending, never the other way. */
    @Test
    fun `a title that is both pending and already in the list reads as done`() {
        assertEquals(
            AddAction(label = "В списке", enabled = false),
            addAction(setOf(7), adding = 7, animeId = 7),
        )
    }
}
