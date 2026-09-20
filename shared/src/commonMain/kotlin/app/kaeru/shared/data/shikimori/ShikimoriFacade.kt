package app.kaeru.shared.data.shikimori

import app.kaeru.shared.data.network.wireJson
import app.kaeru.shared.domain.*
import kotlinx.serialization.encodeToString

/**
 * Shikimori the way Swift asks for it: whole operations answered in the wire models, on top of
 * the endpoint client. Android does not come through here — it has a Room cache to merge into
 * and asks the endpoints itself.
 */
internal class ShikimoriFacade(private val client: ShikimoriClient) {

    suspend fun search(query: String): List<Anime> = client.search(query).map(::anime).withRealPosters()

    suspend fun discover(): List<Anime> = client.catalogue(status = "ongoing").map(::anime).distinctBy { it.id }.withRealPosters()

    suspend fun seasonal(year: Int, season: String): List<Anime> {
        require(year > 0 && season in listOf("winter", "spring", "summer", "autumn")) { "Invalid anime season." }
        return client.catalogue(season = "${season}_$year").map(::anime).distinctBy { it.id }.withRealPosters()
    }

    suspend fun details(animeId: Int): Anime {
        require(animeId > 0) { "Anime id must be positive." }
        return listOf(anime(client.anime(animeId))).withRealPosters().single()
    }

    suspend fun account(token: String): Account {
        require(token.isNotBlank()) { "Authentication required (HTTP 401)." }
        val user = client.whoami(token)
        check(user.id > 0) { "Invalid account response." }
        return Account(user.id, user.nickname, shikimoriUrl(user.avatar).orEmpty())
    }

    suspend fun library(userId: Long, token: String): List<LibraryItem> {
        require(userId > 0) { "User id must be positive." }
        require(token.isNotBlank()) { "Authentication required (HTTP 401)." }
        val rates = client.libraryRates(userId, token)
        val cards = mutableMapOf<Int, Anime>()
        rates.forEach { rate -> rate.embedded?.let { card -> cards[card.id] = anime(card) } }
        val missing = rates.map(::targetId).distinct().filter { it !in cards }
        client.animesByIds(missing).map(::anime).forEach { cards[it.id] = it }
        val enriched = cards.values.toList().withRealPosters().associateBy { it.id }
        return rates.distinctBy { it.id }.map { rate ->
            val card = enriched[targetId(rate)] ?: throw Exception("Anime details are missing from the library response.")
            rate(rate, card)
        }
    }

    suspend fun setRate(animeId: Int, userId: Long, rateId: Long, status: String, episodes: Int, token: String): LibraryItem {
        require(userId > 0 && rateId >= 0 && episodes >= 0) { "Invalid library update." }
        require(status in ShikimoriClient.STATUSES) { "Unknown library status." }
        require(token.isNotBlank()) { "Authentication required (HTTP 401)." }
        // Fetch before the mutation so a failed card fetch cannot hide a successful write.
        val card = details(animeId)
        val written = if (rateId == 0L) client.createUserRate(userId, animeId, status, episodes, token)
            else client.updateUserRate(rateId, status, episodes, token)
        return rate(written, card)
    }

    /** The token answer as Swift stores it: the endpoint's own snake_case keys. */
    suspend fun token(grant: String, value: String): String =
        wireJson.encodeToString(client.token(grant, value))

    /** GraphQL has current artwork; a failed batch keeps its REST posters without losing the catalogue. */
    private suspend fun List<Anime>.withRealPosters(): List<Anime> {
        if (isEmpty()) return this
        val posters = client.posters(map { it.id })
        return map { card -> posters[card.id]?.let { card.copy(poster = it) } ?: card }
    }

    private fun anime(dto: AnimeDto): Anime {
        check(dto.id > 0) { "Invalid anime response." }
        return Anime(
            id = dto.id,
            title = dto.russian.orEmpty().ifBlank { dto.name },
            originalTitle = dto.name,
            poster = dto.restPoster.orEmpty(),
            description = dto.description.orEmpty(),
            episodes = dto.episodes.coerceAtLeast(0),
            episodesAired = dto.episodesAired.coerceAtLeast(0),
            status = dto.status, score = dto.score.orEmpty(),
            year = dto.airedOn.orEmpty().take(4), nextEpisodeAt = dto.nextEpisodeAt.orEmpty(),
            kind = dto.kind?.takeIf { it.isNotBlank() },
            studios = dto.studios.map { it.name }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() },
        )
    }

    private fun targetId(dto: UserRateDto): Int = dto.animeId.takeIf { it > 0 } ?: throw Exception("Library rate has no anime id.")

    private fun rate(dto: UserRateDto, card: Anime): LibraryItem {
        check(dto.id > 0) { "Invalid library rate response." }
        return LibraryItem(
            dto.id, card, dto.status, dto.episodes.coerceAtLeast(0),
            updatedAt = dto.updatedAt?.takeIf { it.isNotBlank() },
        )
    }
}
