package app.kaeru.ui.mobile.details

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DetailsContentTest {
    private fun anime(
        episodes: Int = 28,
        aired: Int = 24,
        status: AnimeStatus = AnimeStatus.ONGOING,
        score: Double? = 9.1,
        year: Int? = 2023,
        studio: String? = "Madhouse",
    ) = Anime(
        id = 7,
        nameRu = "Фрирен",
        nameRomaji = "Frieren",
        posterUrl = null,
        screenshotUrls = emptyList(),
        status = status,
        episodes = episodes,
        episodesAired = aired,
        nextEpisodeAt = null,
        score = score,
        year = year,
        studio = studio,
        description = null,
    )

    private fun entry(watched: Int, watch: WatchState? = null) = LibraryEntry(
        anime(),
        UserRate(1, 7, ListStatus.WATCHING, watched, Instant.EPOCH),
        watch,
    )

    // --- which of the three screens is showing -------------------------------------------------

    @Test
    fun `an anime that is cached is shown even while a refresh is still running`() {
        val state = DetailsUiState(anime = anime(), refreshing = true)
        assertEquals(DetailsContent.Ready(state.anime!!), detailsContentState(state))
    }

    @Test
    fun `a failed refresh over an anime that is cached stays on the anime`() {
        val state = DetailsUiState(anime = anime(), refreshing = false, errorMessage = "Нет соединения")
        assertTrue(detailsContentState(state) is DetailsContent.Ready)
    }

    @Test
    fun `nothing cached and a load in flight is the skeleton`() {
        assertEquals(DetailsContent.Loading, detailsContentState(DetailsUiState(refreshing = true)))
    }

    @Test
    fun `nothing cached and a failed load explains itself`() {
        val state = DetailsUiState(refreshing = false, errorMessage = "Нет соединения. Проверьте интернет")
        assertEquals(
            DetailsContent.Error("Нет соединения. Проверьте интернет"),
            detailsContentState(state),
        )
    }

    @Test
    fun `nothing cached and a load that finished with nothing still says why the screen is empty`() {
        val content = detailsContentState(DetailsUiState(refreshing = false))
        assertTrue(content is DetailsContent.Error && content.message.isNotBlank())
    }

    // --- the chips -----------------------------------------------------------------------------

    @Test
    fun `an ongoing season shows what it announced and what has aired so far`() {
        assertEquals(
            listOf("2023", "28 серий", "вышло 24", "★ 9,1", "Madhouse", "Онгоинг"),
            detailsMeta(anime()),
        )
    }

    @Test
    fun `a finished season shows its length once`() {
        assertEquals(
            listOf("2023", "12 серий", "★ 9,1", "Madhouse", "Вышло"),
            detailsMeta(anime(episodes = 12, aired = 12, status = AnimeStatus.RELEASED)),
        )
    }

    @Test
    fun `an ongoing season nobody has announced the length of counts what is out`() {
        assertEquals(
            listOf("2023", "вышло 24 серии", "★ 9,1", "Madhouse", "Онгоинг"),
            detailsMeta(anime(episodes = 0, aired = 24)),
        )
    }

    @Test
    fun `an announcement with nothing known about it shows only what is known`() {
        assertEquals(
            listOf("Анонс"),
            detailsMeta(anime(episodes = 0, aired = 0, status = AnimeStatus.ANONS, score = null, year = null, studio = null)),
        )
    }

    @Test
    fun `a score is written the Russian way and an unrated anime has no score chip`() {
        assertTrue(detailsMeta(anime(score = 9.0)).contains("★ 9,0"))
        assertTrue(detailsMeta(anime(score = 0.0)).none { it.startsWith("★") })
    }

    @Test
    fun `facts stay separate chips and never join into one meta string`() {
        AnimeStatus.entries.forEach { status ->
            detailsMeta(anime(status = status)).forEach { chip ->
                assertTrue(chip, !chip.contains("·"))
            }
        }
    }

    // --- the labels ----------------------------------------------------------------------------

    @Test
    fun `every list status has a Russian label in sentence case`() {
        assertEquals("Смотрю", statusLabel(ListStatus.WATCHING))
        assertEquals("В планах", statusLabel(ListStatus.PLANNED))
        assertEquals("Завершено", statusLabel(ListStatus.COMPLETED))
        assertEquals("Отложено", statusLabel(ListStatus.ON_HOLD))
        assertEquals("Брошено", statusLabel(ListStatus.DROPPED))
        assertEquals("Пересматриваю", statusLabel(ListStatus.REWATCHING))
    }

    @Test
    fun `the dub pill names the studio once the list is loaded and stays honest before that`() {
        val anilibria = Translation(11, "AniLibria.TV", TranslationKind.VOICE, 12)
        assertEquals("Озвучка: AniLibria.TV", translationLabel(listOf(anilibria), currentId = 11))
        assertEquals("Озвучка", translationLabel(emptyList(), currentId = 11))
        assertEquals("Озвучка", translationLabel(listOf(anilibria), currentId = null))
        assertEquals("Озвучка", translationLabel(listOf(anilibria), currentId = 22))
    }

    @Test
    fun `the watch button offers the first episode of an anime nobody has started`() {
        assertEquals("Смотреть 1 серию", detailsActionLabel(null, 0.9f))
        assertEquals("Смотреть 1 серию", detailsActionLabel(entry(watched = 0), 0.9f))
    }

    @Test
    fun `the watch button names the episode it will continue`() {
        assertEquals("Продолжить 21 серию", detailsActionLabel(entry(watched = 20), 0.9f))
    }

    @Test
    fun `the watch button offers the position it remembers`() {
        val watch = WatchState(7, 21, 860_000, 1_400_000, 11, 1, Instant.EPOCH)
        assertEquals("Продолжить с 14:20", detailsActionLabel(entry(watched = 20, watch = watch), 0.9f))
    }
}
