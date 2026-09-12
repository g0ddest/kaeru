package app.kaeru.data.kodik

import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.error.SourceFormatChanged
import app.kaeru.domain.error.SourceUnavailable
import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.source.EpisodeSourceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kodik as an [EpisodeSourceProvider].
 *
 * The chain is: `get-player` for a shikimori id gives a player page link, the
 * page gives the translation list and the signing parameters, and `/ftor` on a
 * translation's own page gives the signed HLS links of one episode.
 *
 * The translation list is cached for six hours per anime because it costs two
 * requests and barely changes. The signed links are never cached: they expire
 * in hours and look bound to the IP that asked.
 */
@Singleton
class KodikSourceProvider @Inject constructor(
    private val api: KodikApi,
    private val tokenProvider: KodikTokenProvider,
    private val extractor: KodikLinkExtractor,
    private val clock: Clock,
) : EpisodeSourceProvider {

    /** A player page plus the tracks parsed off it, positionally aligned with [KodikPlayerPage.translations]. */
    private data class Catalogue(
        val page: KodikPlayerPage,
        val translations: List<Translation>,
        val at: Instant,
    )

    private val cacheLock = Mutex()
    private val cache = mutableMapOf<Int, Catalogue>()

    override suspend fun translations(shikimoriId: Int): Result<List<Translation>> =
        attempt(episode = null) { catalogue(shikimoriId).translations }

    override suspend fun resolve(
        shikimoriId: Int,
        episode: Int,
        translation: Translation?,
    ): Result<EpisodeStream> = attempt(episode) {
        val catalogue = catalogue(shikimoriId)
        val index = when (translation) {
            null -> catalogue.translations.indices.firstOrNull()
            else -> catalogue.translations.indexOfFirst { it.id == translation.id }.takeIf { it >= 0 }
        } ?: throw KodikError.NotFound(shikimoriId)
        val option = catalogue.page.translations[index]
        // The page never says which season it lists, so a caller that remembers
        // one (WatchState.kodikSeason) outranks the default of 1.
        val season = translation?.season ?: catalogue.translations[index].season
        val chosen = catalogue.translations[index].copy(season = season)

        // Every translation is a separate Kodik entry with its own episode list
        // and its own freshly signed parameters, so the page is always refetched.
        val page = extractor.loadPageForMedia(
            page = catalogue.page,
            mediaId = option.mediaId,
            mediaHash = option.mediaHash,
            type = catalogue.page.currentType,
            season = season,
            episode = episode,
        )
        val wanted = page.episodes.firstOrNull { it.number == episode }
        if (wanted == null && page.episodes.isNotEmpty()) throw KodikError.NotFound(shikimoriId)

        // A page with no episode list is a movie: its only video is the one it already shows.
        val links = extractor.resolveLinks(
            page = page,
            mediaId = wanted?.mediaId ?: page.currentId,
            mediaHash = wanted?.mediaHash ?: page.currentHash,
            type = if (wanted != null) "seria" else page.currentType,
        )
        val urls = links.mapNotNull { (height, url) -> Quality.ofHeight(height)?.let { it to url } }.toMap()
        if (urls.isEmpty()) throw KodikError.ParserBroken("links")

        EpisodeStream(
            animeId = shikimoriId,
            episode = episode,
            translation = chosen,
            urls = urls,
            resolvedAt = clock.instant(),
        )
    }

    private suspend fun catalogue(shikimoriId: Int): Catalogue {
        cached(shikimoriId)?.let { return it }
        val answer = getPlayer(shikimoriId)
        val link = answer.link
            ?.takeIf { answer.found && it.isNotBlank() }
            ?: throw KodikError.NotFound(shikimoriId)
        val page = extractor.loadPage(link)
        val fresh = Catalogue(page, page.translations.map { it.toDomain() }, clock.instant())
        cacheLock.withLock { cache[shikimoriId] = fresh }
        return fresh
    }

    private suspend fun cached(shikimoriId: Int): Catalogue? = cacheLock.withLock {
        cache[shikimoriId]?.takeIf {
            val age = Duration.between(it.at, clock.instant())
            !age.isNegative && age < CACHE_TTL
        }
    }

    /**
     * Kodik rejects a stale public token with `{"error": "Отсутствует или неверный токен"}`,
     * under HTTP 401 or under 200. Either way: re-extract the token and try exactly once more.
     */
    private suspend fun getPlayer(shikimoriId: Int): KodikGetPlayerDto {
        val first = try {
            api.getPlayer(tokenProvider.token(), shikimoriId)
        } catch (e: HttpException) {
            if (e.code() != HTTP_UNAUTHORIZED) throw e
            null
        }
        if (first != null && !first.tokenRejected) return first

        return try {
            api.getPlayer(tokenProvider.token(forceRefresh = true), shikimoriId)
                .also { if (it.tokenRejected) throw KodikError.NoToken() }
        } catch (e: HttpException) {
            if (e.code() == HTTP_UNAUTHORIZED) throw KodikError.NoToken(e) else throw e
        }
    }

    private suspend fun <T> attempt(episode: Int?, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error.toDomainFailure(episode))
    }

    /** `ui.*` only knows `domain.error`, so no Kodik, OkHttp or Retrofit type may leave this class. */
    private fun Throwable.toDomainFailure(episode: Int?): Throwable = when (this) {
        is KodikError.NoToken -> SourceUnavailable(this)
        is KodikError.NotFound -> EpisodeNotAvailable(shikimoriId, episode)
        is KodikError.ParserBroken -> SourceFormatChanged(step, this)
        is KodikError.Network -> NetworkUnavailable(this)
        is SerializationException -> SourceFormatChanged("get-player", this)
        // Not HttpError: its copy names Shikimori, and nothing here talks to Shikimori.
        is HttpException -> SourceUnavailable(this)
        is IOException -> NetworkUnavailable(this)
        else -> this
    }

    private fun KodikTranslationOption.toDomain() = Translation(
        id = id,
        title = title,
        type = when (type) {
            TranslationType.VOICE -> TranslationKind.VOICE
            TranslationType.SUBTITLES -> TranslationKind.SUBTITLES
        },
        episodesCount = episodesCount,
    )

    private companion object {
        val CACHE_TTL: Duration = Duration.ofHours(6)
        const val HTTP_UNAUTHORIZED = 401
    }
}
