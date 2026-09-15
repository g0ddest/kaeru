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

    // --- availableEpisodes -------------------------------------------------------------------

    @Test
    fun `an ongoing show has only what has aired available, whatever the season promises`() {
        assertEquals(7, anime(AnimeStatus.ONGOING, episodes = 24, episodesAired = 7).availableEpisodes)
        assertEquals(0, anime(AnimeStatus.ONGOING, episodes = 24, episodesAired = 0).availableEpisodes)
    }

    @Test
    fun `an announcement has nothing available, whatever length it promises`() {
        assertEquals(0, anime(AnimeStatus.ANONS, episodes = 12, episodesAired = 0).availableEpisodes)
        assertEquals(0, anime(AnimeStatus.ANONS, episodes = 0, episodesAired = 0).availableEpisodes)
        // Unconditional: even if the catalogue inconsistently reports something aired, an
        // announcement has not started, so nothing is available to press play on.
        assertEquals(0, anime(AnimeStatus.ANONS, episodes = 12, episodesAired = 5).availableEpisodes)
    }

    @Test
    fun `a released show counts its whole announced run as available`() {
        assertEquals(12, anime(AnimeStatus.RELEASED, episodes = 12, episodesAired = 0).availableEpisodes)
    }

    @Test
    fun `a released show with an unknown total falls back to what aired`() {
        assertEquals(24, anime(AnimeStatus.RELEASED, episodes = 0, episodesAired = 24).availableEpisodes)
    }
}
