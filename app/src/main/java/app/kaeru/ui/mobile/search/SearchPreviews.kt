package app.kaeru.ui.mobile.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.ui.common.theme.KaeruTheme

private const val DARK = 0xFF0B0C10

private const val FRIEREN = "Фрирен, провожающая в последний путь"
private const val DANDADAN = "Дандадан"
private const val JUJUTSU = "Магическая битва"
private const val ONE_PUNCH = "Ванпанчмен"
private const val SHADOW = "Восхождение в тени"
private const val DEMON_SLAYER = "Клинок, рассекающий демонов"

private fun anime(id: Int, title: String, year: Int, episodes: Int) = Anime(
    id = id,
    nameRu = title,
    nameRomaji = title,
    posterUrl = null,
    screenshotUrls = emptyList(),
    status = AnimeStatus.RELEASED,
    episodes = episodes,
    episodesAired = episodes,
    nextEpisodeAt = null,
    score = 9.1,
    year = year,
    studio = "Madhouse",
    description = null,
)

private val results = listOf(
    anime(1, FRIEREN, year = 2023, episodes = 28),
    anime(2, DANDADAN, year = 2024, episodes = 12),
    anime(3, JUJUTSU, year = 2020, episodes = 24),
    anime(4, ONE_PUNCH, year = 2015, episodes = 12),
    anime(5, SHADOW, year = 2022, episodes = 20),
    anime(6, DEMON_SLAYER, year = 2019, episodes = 26),
)

private val recent = listOf("фрирен", "дандадан", "магическая битва", "ванпанчмен")

/** The screen with every callback stubbed, so each preview is one line of state. */
@Composable
private fun Screen(state: SearchUiState) = SearchScreen(
    state = state,
    onQuery = {},
    onSubmit = {},
    onRecent = {},
    onPlanned = {},
    onOpen = {},
)

/** Opened and not typed into: an invitation rather than «Ничего не найдено». */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun SearchIdlePreview() = KaeruTheme { Screen(SearchUiState()) }

/**
 * Answers, with the card control in all three of its states at once: one already in the list, one
 * being written, and four still to add.
 */
@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun SearchResultsPreview() = KaeruTheme {
    Screen(
        SearchUiState(
            query = "фрирен",
            results = results,
            recentQueries = recent,
            libraryIds = setOf(3),
            addingAnimeId = 2,
        ),
    )
}

@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun SearchLoadingPreview() = KaeruTheme {
    Screen(SearchUiState(query = "фрирен", recentQueries = recent, searching = true))
}

@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun SearchNotFoundPreview() = KaeruTheme {
    Screen(SearchUiState(query = "фрирн", recentQueries = listOf("фрирн") + recent))
}

@Preview(showBackground = true, backgroundColor = DARK, widthDp = 360, heightDp = 800)
@Composable
private fun SearchErrorPreview() = KaeruTheme {
    Screen(
        SearchUiState(
            query = "фрирен",
            recentQueries = recent,
            errorMessage = "Нет соединения. Проверьте интернет",
        ),
    )
}
