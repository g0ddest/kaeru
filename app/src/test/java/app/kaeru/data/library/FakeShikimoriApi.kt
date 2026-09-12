package app.kaeru.data.library

import app.kaeru.data.shikimori.AnimeDetailsDto
import app.kaeru.data.shikimori.AnimeShortDto
import app.kaeru.data.shikimori.ImageDto
import app.kaeru.data.shikimori.ScreenshotDto
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.UserDto
import app.kaeru.data.shikimori.UserRateDto
import app.kaeru.data.shikimori.UserRateRequest

class FakeShikimoriApi : ShikimoriApi {
    val rates = mutableMapOf<String, MutableList<UserRateDto>>()
    val animes = mutableMapOf<Int, AnimeShortDto>()
    val details = mutableMapOf<Int, AnimeDetailsDto>()
    val screenshots = mutableMapOf<Int, List<ScreenshotDto>>()
    val calls = mutableListOf<String>()
    val ratePages = mutableListOf<Pair<String, Int>>()
    val animeBatches = mutableListOf<List<Int>>()
    val updates = mutableListOf<Pair<Long, UserRateRequest>>()
    val creates = mutableListOf<UserRateRequest>()
    var beforeCall: suspend (String) -> Unit = {}
    var nextId = 1000L

    fun short(id: Int, status: String = "released", episodes: Int = 12, aired: Int = episodes) =
        AnimeShortDto(id, "Name $id", "Имя $id", ImageDto("/o$id.jpg", "/p$id.jpg"), "7.0", status, episodes, aired, "2026-01-01")

    fun rate(id: Long, animeId: Int, status: String, episodes: Int) =
        UserRateDto(id, animeId, status, episodes, "2026-09-01T00:00:00.000+03:00")

    private suspend fun record(call: String) {
        calls += call
        beforeCall(call)
    }

    override suspend fun whoami(): UserDto {
        record("whoami")
        return UserDto(42, "vitaliy")
    }

    override suspend fun userRates(userId: Long, status: String, page: Int, limit: Int): List<UserRateDto> {
        require(userId == 42L && page >= 1 && limit in 1..1000)
        record("rates:$status")
        ratePages += status to page
        return rates[status].orEmpty().drop((page - 1) * limit).take(limit)
    }

    override suspend fun animesByIds(ids: String, limit: Int): List<AnimeShortDto> {
        val batch = ids.split(",").map(String::toInt)
        require(limit in 1..50 && batch.size <= limit)
        record("animes:$ids")
        animeBatches += batch
        return batch.mapNotNull(animes::get)
    }

    override suspend fun anime(id: Int): AnimeDetailsDto {
        record("anime:$id")
        return details.getValue(id)
    }

    override suspend fun screenshots(id: Int): List<ScreenshotDto> {
        record("screenshots:$id")
        return screenshots[id].orEmpty()
    }

    override suspend fun search(query: String, limit: Int): List<AnimeShortDto> {
        record("search:$query")
        return animes.values.filter { it.name.contains(query, true) }.take(limit)
    }

    override suspend fun createUserRate(body: UserRateRequest): UserRateDto {
        record("create")
        creates += body
        val payload = body.userRate
        require(payload.userId == 42L && payload.targetType == "Anime")
        return rate(nextId++, requireNotNull(payload.targetId), payload.status ?: "planned", payload.episodes ?: 0)
            .also { rates.getOrPut(it.status) { mutableListOf() }.add(it) }
    }

    override suspend fun updateUserRate(id: Long, body: UserRateRequest): UserRateDto {
        record("update:$id")
        updates += id to body
        val existing = rates.values.flatten().single { it.id == id }
        val updated = existing.copy(
            status = body.userRate.status ?: existing.status,
            episodes = body.userRate.episodes ?: existing.episodes,
        )
        rates.values.forEach { it.removeAll { rate -> rate.id == id } }
        rates.getOrPut(updated.status) { mutableListOf() }.add(updated)
        return updated
    }
}
