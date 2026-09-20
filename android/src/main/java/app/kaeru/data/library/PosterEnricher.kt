package app.kaeru.data.library

import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.domain.model.Anime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gives a list of titles the posters Shikimori's REST API will not.
 *
 * REST `image` is a legacy field: for anything added after the poster migration it answers with
 * `missing_original.jpg`, so a row of new titles draws a row of grey placeholders. The real
 * artwork comes from GraphQL, and asking for it — in batches, tolerating a failed batch,
 * preferring the full-size `originalUrl` — is the shared module's `posters`. What is left here is
 * putting the answer onto this app's [Anime].
 *
 * Shared by every list that comes off the catalogue — the library sync, search, and the discovery
 * rows — so all of them show the same artwork for the same title.
 */
@Singleton
class PosterEnricher @Inject constructor(private val api: ShikimoriApi) {
    suspend fun enrich(animes: List<Anime>): List<Anime> {
        if (animes.isEmpty()) return animes
        val posters = api.posters(animes.map { it.id })
        return animes.map { anime -> posters[anime.id]?.let { anime.copy(posterUrl = it) } ?: anime }
    }
}
