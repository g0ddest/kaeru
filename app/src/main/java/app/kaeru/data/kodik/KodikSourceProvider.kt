package app.kaeru.data.kodik

import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.error.EpisodeUnavailableReason
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.error.SourceFormatChanged
import app.kaeru.domain.error.SourceUnavailable
import app.kaeru.domain.error.SourceUnavailableReason
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
 *
 * Every translation's own page lists the episodes of the season it was opened on, and that list
 * is kept beside the catalogue for as long as the catalogue is: it is what says, without another
 * request, which tracks cannot have the episode a viewer is about to be offered.
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

    /** Per anime, per translation id: the episode numbers that track's page listed. Lives and dies with [cache]. */
    private val listed = mutableMapOf<Int, MutableMap<Int, Set<Int>>>()

    override suspend fun translations(shikimoriId: Int): Result<List<Translation>> =
        attempt(episode = null) { catalogue(shikimoriId).translations }

    override suspend fun listedEpisodes(shikimoriId: Int, translationId: Int): Set<Int>? =
        cacheLock.withLock { cached(shikimoriId)?.let { listed[shikimoriId]?.get(translationId) } }

    override suspend fun forget(shikimoriId: Int) {
        cacheLock.withLock { drop(shikimoriId) }
    }

    override suspend fun resolve(
        shikimoriId: Int,
        episode: Int,
        translation: Translation?,
    ): Result<EpisodeStream> = attempt(episode) {
        val catalogue = catalogue(shikimoriId)
        val index = when (translation) {
            null -> catalogue.translations.indices.firstOrNull()
            else -> catalogue.translations.indexOfFirst { it.id == translation.id }.takeIf { it >= 0 }
        } ?: throw KodikError.NotFound(shikimoriId, EpisodeUnavailableReason.NOT_IN_TRANSLATION)
        val option = catalogue.page.translations[index]
        // The page never says which season it lists, so a caller that remembers
        // one (WatchState.kodikSeason) outranks the default of 1.
        val season = translation?.season ?: catalogue.translations[index].season
        val chosen = catalogue.translations[index].copy(season = season)
        // A movie has no season or episode to select, and Kodik serves it from /video.
        val isSerial = catalogue.page.currentType == SERIAL_TYPE

        // Every translation is a separate Kodik entry with its own episode list
        // and its own freshly signed parameters, so the page is always refetched.
        val page = extractor.loadPageForMedia(
            page = catalogue.page,
            mediaId = option.mediaId,
            mediaHash = option.mediaHash,
            type = catalogue.page.currentType,
            season = season.takeIf { isSerial },
            episode = episode.takeIf { isSerial },
        )
        // Written down whether or not the episode is there: a page that was fetched to find out
        // has answered for the whole season, and the next question about this track is free.
        if (page.episodes.isNotEmpty()) {
            cacheLock.withLock {
                listed.getOrPut(shikimoriId) { mutableMapOf() }[chosen.id] = page.episodes.map { it.number }.toSet()
            }
        }
        val wanted = page.episodes.firstOrNull { it.number == episode }
        if (wanted == null && page.episodes.isNotEmpty()) {
            throw KodikError.NotFound(shikimoriId, EpisodeUnavailableReason.NOT_IN_TRANSLATION)
        }

        // A page with no episode list is a movie: its only video is the one it already shows.
        val links = extractor.resolveLinks(
            page = page,
            mediaId = wanted?.mediaId ?: page.currentId,
            mediaHash = wanted?.mediaHash ?: page.currentHash,
            type = if (wanted != null) SERIAL_TYPE else page.currentType,
        )
        val urls = links.mapNotNull { (height, url) -> Quality.ofHeight(height)?.let { it to url } }.toMap()
        if (urls.isEmpty()) throw KodikError.ParserBroken("links")

        EpisodeStream(
            animeId = shikimoriId,
            // A movie is its own single episode however the caller numbered it.
            episode = if (page.episodes.isEmpty()) 1 else episode,
            translation = chosen,
            urls = urls,
            resolvedAt = clock.instant(),
        )
    }

    private suspend fun catalogue(shikimoriId: Int): Catalogue {
        cacheLock.withLock { cached(shikimoriId) }?.let { return it }
        val answer = getPlayer(shikimoriId)
        val link = answer.link
            ?.takeIf { answer.found && it.isNotBlank() }
            ?: throw KodikError.NotFound(shikimoriId, EpisodeUnavailableReason.TITLE_NOT_ON_SOURCE)
        val page = extractor.loadPage(link).withSoleTrack()
        val fresh = Catalogue(page, page.translations.map { it.toDomain() }, clock.instant())
        cacheLock.withLock {
            // What the old catalogue's tracks listed was read against media ids the new page may
            // no longer carry, so the lists go with the catalogue they were read under.
            drop(shikimoriId)
            cache[shikimoriId] = fresh
        }
        return fresh
    }

    /**
     * A film with a single voice, given the one entry its page never drew.
     *
     * Kodik renders no translations box when there is nothing to choose between, so such a page
     * parses with an empty track list — and an empty list meant no index to resolve, so every film
     * of that kind came back as «серия недоступна». The page still names the voice it is showing
     * in its own script, so the entry is made from the page rather than invented: the page's own
     * media id and hash, the id and title it gives itself, one episode because a film is one.
     *
     * Written into the page, not only into [Catalogue.translations], so the two halves stay
     * positionally aligned — which is the whole contract `resolve` indexes them by.
     *
     * A serial can never reach this: [KodikHtmlParser] refuses a serial page with no box.
     */
    private fun KodikPlayerPage.withSoleTrack(): KodikPlayerPage {
        if (translations.isNotEmpty()) return this
        val sole = KodikTranslationOption(
            // The page's own translation id, which is a real Kodik id and so cannot collide with
            // one from a chooser. Only a page that names none falls back, and it falls back to a
            // negative number derived from the media id: stable for this film and impossible to
            // mistake for a track anything else remembers.
            id = currentTranslationId ?: -(currentId.toIntOrNull() ?: 1),
            title = currentTranslationTitle ?: SOLE_TRACK_TITLE,
            type = TranslationType.VOICE,
            episodesCount = 1,
            mediaId = currentId,
            mediaHash = currentHash,
        )
        return copy(translations = listOf(sole))
    }

    /** The catalogue still worth answering from, or null having dropped one that has aged out. Under [cacheLock]. */
    private fun cached(shikimoriId: Int): Catalogue? {
        val held = cache[shikimoriId] ?: return null
        val age = Duration.between(held.at, clock.instant())
        if (!age.isNegative && age < CACHE_TTL) return held
        drop(shikimoriId)
        return null
    }

    /** Everything remembered about one anime. Under [cacheLock]. */
    private fun drop(shikimoriId: Int) {
        cache.remove(shikimoriId)
        listed.remove(shikimoriId)
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
        is KodikError.NoToken -> SourceUnavailable(SourceUnavailableReason.NO_KEY, this)
        is KodikError.NotFound -> EpisodeNotAvailable(shikimoriId, episode, reason)
        is KodikError.ParserBroken -> SourceFormatChanged(step, this)
        is KodikError.Network -> NetworkUnavailable(this)
        is KodikError.Rejected -> SourceUnavailable(SourceUnavailableReason.REJECTED, this)
        is SerializationException -> SourceFormatChanged("get-player", this)
        // Not HttpError: its copy names Shikimori, and nothing here talks to Shikimori.
        is HttpException -> SourceUnavailable(SourceUnavailableReason.REJECTED, this)
        is IOException -> NetworkUnavailable(this)
        // Nothing reaches the UI unclassified: an unmapped exception would otherwise
        // land on the generic "что-то пошло не так" and hide which source failed.
        else -> SourceUnavailable(SourceUnavailableReason.REJECTED, this)
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

        /** For a film whose page names no studio: what the viewer is hearing, without a claim about who made it. */
        const val SOLE_TRACK_TITLE = "Единственная озвучка"

        const val HTTP_UNAUTHORIZED = 401
        const val SERIAL_TYPE = "seria"
    }
}
