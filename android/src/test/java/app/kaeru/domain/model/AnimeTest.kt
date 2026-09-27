package app.kaeru.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimeTest {

    private fun anime(
        status: AnimeStatus,
        episodes: Int,
        episodesAired: Int,
    ) = Anime(
        id = 1,
        nameRu = "Тест",
        nameRomaji = "Test",
        posterUrl = null,
        screenshotUrls = emptyList(),
        status = status,
        episodes = episodes,
        episodesAired = episodesAired,
        nextEpisodeAt = null,
        score = null,
        year = null,
        studio = null,
        description = null,
    )

    // --- availableEpisodes: the rule is `NextEpisodeRules.availableEpisodes`, tested in `shared` ---

    @Test
    fun `each status reaches the shared rule as itself`() {
        assertEquals(7, anime(AnimeStatus.ONGOING, episodes = 24, episodesAired = 7).availableEpisodes)
        assertEquals(0, anime(AnimeStatus.ANONS, episodes = 12, episodesAired = 5).availableEpisodes)
        assertEquals(12, anime(AnimeStatus.RELEASED, episodes = 12, episodesAired = 0).availableEpisodes)
    }
}
