package app.kaeru.ui.common.details

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.playback.RankedTranslation
import app.kaeru.ui.common.details.episodeCells
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private val now: Instant = Instant.parse("2026-09-13T20:00:00Z")

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
    // `statusLabel` moved to `ui.common.design.Format`; its test went with it, to `FormatTest`.

    @Test
    fun `the dub pill names the studio once the list is loaded and stays honest before that`() {
        val anilibria = RankedTranslation(Translation(11, "AniLibria.TV", TranslationKind.VOICE, 12), oftenChosen = false)
        assertEquals("Озвучка: AniLibria.TV", translationLabel(listOf(anilibria), currentId = 11))
        assertEquals("Озвучка", translationLabel(emptyList(), currentId = 11))
        assertEquals("Озвучка", translationLabel(listOf(anilibria), currentId = null))
        assertEquals("Озвучка", translationLabel(listOf(anilibria), currentId = 22))
    }

    @Test
    fun `the dub pill names the remembered studio before the list is asked for`() {
        assertEquals(
            "Озвучка: Студийная банда",
            translationLabel(emptyList(), currentId = 22, rememberedTitle = "Студийная банда"),
        )
    }

    @Test
    fun `a loaded list outranks the remembered name, which may be out of date`() {
        val anilibria = RankedTranslation(Translation(11, "AniLibria.TV", TranslationKind.VOICE, 12), oftenChosen = false)

        assertEquals(
            "Озвучка: AniLibria.TV",
            translationLabel(listOf(anilibria), currentId = 11, rememberedTitle = "Что-то другое"),
        )
    }

    @Test
    fun `a remembered name with no dub behind it is not shown`() {
        assertEquals("Озвучка", translationLabel(emptyList(), currentId = null, rememberedTitle = "Студийная банда"))
        assertEquals("Озвучка", translationLabel(emptyList(), currentId = 22, rememberedTitle = "  "))
    }

    @Test
    fun `the watch button offers the first episode of an anime nobody has started`() {
        val released = anime(episodes = 12, aired = 12, status = AnimeStatus.RELEASED)
        assertEquals("Смотреть 1 серию", detailsAction(released, null, 0.9f, now).label)
        assertEquals("Смотреть 1 серию", detailsAction(anime(), entry(watched = 0), 0.9f, now).label)
    }

    @Test
    fun `an announcement outside the list offers nothing to press rather than episode one`() {
        val anons = anime(episodes = 0, aired = 0, status = AnimeStatus.ANONS)
        val action = detailsAction(anons, null, 0.9f, now)
        assertEquals("Ещё не вышло", action.label)
        assertFalse(action.enabled)
        assertNull(action.episode)
    }

    @Test
    fun `the watch button names the episode it will continue`() {
        val action = detailsAction(anime(), entry(watched = 20), 0.9f, now)
        assertEquals("Продолжить 21 серию", action.label)
        assertEquals(21, action.episode)
    }

    @Test
    fun `the watch button offers the position it remembers`() {
        val watch = WatchState(7, 21, 860_000, 1_400_000, 11, 1, Instant.EPOCH)
        assertEquals("Продолжить с 14:20", detailsAction(anime(), entry(watched = 20, watch = watch), 0.9f, now).label)
    }

    @Test
    fun `the watch button will not offer an episode the season has not reached`() {
        // Twenty-four of twenty-eight are out and the viewer has all twenty-four behind them.
        val action = detailsAction(anime(), entry(watched = 24), 0.9f, now)
        assertEquals("Ждём 25 серию", action.label)
        assertFalse(action.enabled)
        assertNull(action.episode)
    }

    // --- the count under the grid ----------------------------------------------------------

    @Test
    fun `the count under the grid never claims more watched than the season holds`() {
        assertEquals("20 из 28", watchedLine(20, 28))
        assertEquals("30 из 30", watchedLine(30, 28))
        assertEquals("20 из ?", watchedLine(20, 0))
    }

    @Test
    fun `the count and the grid agree when more aired than the season announced`() {
        // Twelve announced, sixteen actually out: the grid draws sixteen tiles, so the line has to
        // say «из 16». Reading the announced total instead would print «14 из 12» over them.
        val extended = anime(episodes = 12, aired = 16)
        val cells = episodeCells(extended, entry(watched = 14).rate, null, 0.9f)
        assertEquals(16, cells.size)
        assertEquals("14 из 16", watchedLine(14, cells.size))
    }
}
