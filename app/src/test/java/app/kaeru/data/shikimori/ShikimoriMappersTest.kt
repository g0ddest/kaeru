package app.kaeru.data.shikimori

import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.ListStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ShikimoriMappersTest {
    @Test
    fun `short dto maps poster to absolute url and status`() {
        val dto = AnimeShortDto(
            id = 1,
            name = "Name",
            russian = "Имя",
            image = ImageDto("/system/animes/original/1.jpg", "/system/animes/preview/1.jpg"),
            score = "8.1",
            status = "ongoing",
            episodes = 0,
            episodesAired = 3,
            airedOn = "2026-07-05",
        )

        val anime = dto.toDomain()

        assertEquals("https://shikimori.io/system/animes/original/1.jpg", anime.posterUrl)
        assertEquals(AnimeStatus.ONGOING, anime.status)
        assertEquals(2026, anime.year)
        assertEquals(8.1, anime.score!!, 0.001)
    }

    @Test
    fun `details dto keeps screenshots studio and next episode`() {
        val dto = AnimeDetailsDto(
            id = 1,
            name = "Name",
            russian = "",
            image = ImageDto("/o.jpg", "/p.jpg"),
            score = "0.0",
            status = "anons",
            episodes = 12,
            episodesAired = 0,
            airedOn = null,
            description = "text",
            nextEpisodeAt = "2026-09-14T17:00:00.000+03:00",
            studios = listOf(StudioDto("MAPPA")),
            screenshots = listOf(ScreenshotDto("/s1.jpg", "/s1p.jpg")),
        )

        val anime = dto.toDomain()

        assertEquals(Instant.parse("2026-09-14T14:00:00Z"), anime.nextEpisodeAt)
        assertEquals("MAPPA", anime.studio)
        assertEquals(listOf("https://shikimori.io/s1.jpg"), anime.screenshotUrls)
        assertEquals(null, anime.score)
        assertEquals(AnimeStatus.ANONS, anime.status)
    }

    @Test
    fun `user rate dto maps status and time`() {
        val rate = UserRateDto(
            id = 5,
            targetId = 9,
            status = "on_hold",
            episodes = 3,
            updatedAt = "2026-09-11T21:30:00.000+03:00",
        ).toDomain()

        assertEquals(ListStatus.ON_HOLD, rate.status)
        assertEquals(Instant.parse("2026-09-11T18:30:00Z"), rate.updatedAt)
    }

    @Test
    fun `unknown remote statuses use safe domain fallbacks`() {
        val anime = AnimeShortDto(id = 1, status = "unexpected").toDomain()
        val rate = UserRateDto(
            id = 2,
            targetId = 1,
            status = "unexpected",
            updatedAt = "invalid",
        ).toDomain()

        assertEquals(AnimeStatus.RELEASED, anime.status)
        assertEquals(ListStatus.PLANNED, rate.status)
        assertEquals(Instant.EPOCH, rate.updatedAt)
    }

    @Test
    fun `absolute urls are preserved and relative urls are rooted`() {
        val absolutePoster = AnimeShortDto(
            id = 1,
            image = ImageDto("https://cdn.example/poster.jpg"),
        ).toDomain()
        val relativePoster = AnimeShortDto(
            id = 2,
            image = ImageDto("images/poster.jpg"),
        ).toDomain()

        assertEquals("https://cdn.example/poster.jpg", absolutePoster.posterUrl)
        assertEquals("https://shikimori.io/images/poster.jpg", relativePoster.posterUrl)
    }

    @Test
    fun `malformed optional values map to null`() {
        val anime = AnimeDetailsDto(
            id = 1,
            score = "not-a-score",
            airedOn = "unknown",
            nextEpisodeAt = "not-a-time",
        ).toDomain()

        assertNull(anime.score)
        assertNull(anime.year)
        assertNull(anime.nextEpisodeAt)
    }
}
