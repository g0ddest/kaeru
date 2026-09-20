package app.kaeru.data.library

import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.shared.data.shikimori.AnimeDto
import app.kaeru.shared.data.shikimori.ImageDto
import app.kaeru.shared.data.shikimori.ScreenshotDto
import app.kaeru.shared.data.shikimori.ShikimoriClient
import app.kaeru.shared.data.shikimori.UserDto
import app.kaeru.shared.data.shikimori.UserRateDto

/**
 * Shikimori as the repositories see it, in memory.
 *
 * Every call is recorded under a name a test can hook with [beforeCall] — to fail one, or to
 * hold it while something else happens. The list is recorded once per status, the way the real
 * client asks for it, so a test can fail the `completed` page and no other.
 */
class FakeShikimoriApi : ShikimoriApi {
    val rates = mutableMapOf<String, MutableList<UserRateDto>>()
    val animes = mutableMapOf<Int, AnimeDto>()
    val details = mutableMapOf<Int, AnimeDto>()
    val screenshots = mutableMapOf<Int, List<ScreenshotDto>>()
    val calls = mutableListOf<String>()
    val animeBatches = mutableListOf<List<Int>>()
    val updates = mutableListOf<Update>()
    val creates = mutableListOf<Create>()
    var beforeCall: suspend (String) -> Unit = {}
    var nextId = 1000L
    var userId = 42L
    val requestedUserIds = mutableListOf<Long>()

    /** The tokens `whoami` was asked about explicitly; null for the session's. */
    val identityBearers = mutableListOf<String?>()

    data class Update(val id: Long, val status: String?, val episodes: Int?)
    data class Create(val userId: Long, val animeId: Int, val status: String)

    fun short(id: Int, status: String = "released", episodes: Int = 12, aired: Int = episodes) =
        AnimeDto(id, "Name $id", "Имя $id", ImageDto("/o$id.jpg", "/p$id.jpg"), "7.0", status, episodes, aired, "2026-01-01")

    fun rate(id: Long, animeId: Int, status: String, episodes: Int) =
        UserRateDto(id, animeId, status, episodes, "2026-09-01T00:00:00.000+03:00")

    private suspend fun record(call: String) {
        calls += call
        beforeCall(call)
    }

    override suspend fun whoami(accessToken: String?): UserDto {
        identityBearers += accessToken
        record("whoami")
        return UserDto(userId, "user-$userId")
    }

    override suspend fun libraryRates(userId: Long): List<UserRateDto> {
        require(userId == this.userId)
        val all = mutableListOf<UserRateDto>()
        for (status in ShikimoriClient.STATUSES) {
            requestedUserIds += userId
            record("rates:$status")
            all += rates[status].orEmpty()
        }
        return all
    }

    override suspend fun animesByIds(ids: List<Int>): List<AnimeDto> {
        record("animes:${ids.joinToString(",")}")
        animeBatches += ids
        return ids.mapNotNull(animes::get)
    }

    override suspend fun anime(id: Int): AnimeDto {
        record("anime:$id")
        return details.getValue(id)
    }

    override suspend fun screenshots(id: Int): List<ScreenshotDto> {
        record("screenshots:$id")
        return screenshots[id].orEmpty()
    }

    /** Catalogue rows, keyed by the filter that asked for them: `ongoing` or a season name. */
    val catalogue = mutableMapOf<String, List<AnimeDto>>()
    val catalogueCalls = mutableListOf<String>()

    override suspend fun catalogue(status: String?, season: String?): List<AnimeDto> {
        val key = season ?: status.orEmpty()
        record("catalogue:$key")
        catalogueCalls += key
        return catalogue[key].orEmpty().take(20)
    }

    override suspend fun search(query: String): List<AnimeDto> {
        record("search:$query")
        return animes.values.filter { it.name.contains(query, true) }.take(30)
    }

    /** GraphQL poster answers keyed by anime id. */
    val posters = mutableMapOf<Int, String>()
    val posterQueries = mutableListOf<List<Int>>()

    override suspend fun posters(ids: List<Int>): Map<Int, String> {
        posterQueries += ids
        return ids.mapNotNull { id -> posters[id]?.let { id to it } }.toMap()
    }

    override suspend fun createUserRate(userId: Long, animeId: Int, status: String): UserRateDto {
        record("create")
        creates += Create(userId, animeId, status)
        require(userId == this.userId)
        return rate(nextId++, animeId, status, 0)
            .also { rates.getOrPut(it.status) { mutableListOf() }.add(it) }
    }

    override suspend fun updateUserRate(id: Long, status: String?, episodes: Int?): UserRateDto {
        record("update:$id")
        updates += Update(id, status, episodes)
        val existing = rates.values.flatten().single { it.id == id }
        val updated = existing.copy(status = status ?: existing.status, episodes = episodes ?: existing.episodes)
        rates.values.forEach { it.removeAll { rate -> rate.id == id } }
        rates.getOrPut(updated.status) { mutableListOf() }.add(updated)
        return updated
    }
}
