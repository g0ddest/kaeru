package app.kaeru.data.library

import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.isMissingPoster
import app.kaeru.data.shikimori.postersQuery
import app.kaeru.domain.model.Anime
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** Shikimori's own ceiling for one `animes` GraphQL query, and for one `ids=` REST batch. */
private const val BATCH = 50

/**
 * Gives a list of titles the posters Shikimori's REST API will not.
 *
 * REST `image` is a legacy field: for anything added after the poster migration it answers with
 * `missing_original.jpg`, so a row of new titles draws a row of grey placeholders. GraphQL carries
 * the real poster, so it is asked in batches of fifty and preferred.
 *
 * Posters are cosmetic, and this runs on the way to a screen that has already loaded: a GraphQL
 * call that fails keeps whatever REST returned rather than failing the whole row. That is why the
 * catch is inside the batch loop — one bad batch costs one batch of posters. Cancellation is not
 * a failure of the batch and is rethrown, so a caller that walks away stops the whole enrichment
 * instead of quietly finishing the remaining batches.
 *
 * Shared by every list that comes off the catalogue — the library sync, search, and the discovery
 * rows — so all of them show the same artwork for the same title.
 */
@Singleton
class PosterEnricher @Inject constructor(private val api: ShikimoriApi) {
    suspend fun enrich(animes: List<Anime>): List<Anime> {
        if (animes.isEmpty()) return animes
        val posters = animes.map { it.id }.chunked(BATCH).flatMap { batch ->
            try {
                api.graphql(postersQuery(batch)).data?.animes.orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                emptyList()
            }
        }.mapNotNull { dto ->
            val url = dto.poster?.mainUrl ?: dto.poster?.originalUrl ?: return@mapNotNull null
            dto.id.toIntOrNull()?.let { it to url }
        }.toMap()
        return animes.map { anime ->
            val real = posters[anime.id]
            if (real != null && (isMissingPoster(anime.posterUrl) || real != anime.posterUrl)) {
                anime.copy(posterUrl = real)
            } else {
                anime
            }
        }
    }
}
