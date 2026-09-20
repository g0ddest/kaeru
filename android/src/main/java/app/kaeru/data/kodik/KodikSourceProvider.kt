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
import app.kaeru.shared.ApiException
import app.kaeru.shared.data.kodik.KodikClient
import app.kaeru.shared.data.kodik.KodikError
import app.kaeru.shared.data.kodik.KodikTranslationOption
import app.kaeru.shared.data.kodik.TranslationType
import app.kaeru.shared.data.network.NetworkException
import kotlinx.coroutines.CancellationException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kodik as an [EpisodeSourceProvider].
 *
 * The chain, the catalogue it keeps for six hours, the episode lists it remembers per track and
 * the token it scrapes all live in the shared module's [KodikClient]; this is the Android edge of
 * it. Two things happen here and nowhere else: the source's own types become the app's — a
 * quality ladder, a resolve timestamp, a track with the season it was opened on — and the
 * source's failures become `domain.error`, because `ui.*` knows nothing else.
 */
@Singleton
class KodikSourceProvider @Inject constructor(
    private val kodik: KodikClient,
    private val clock: Clock,
) : EpisodeSourceProvider {

    override suspend fun translations(shikimoriId: Int): Result<List<Translation>> =
        attempt(shikimoriId, episode = null) { kodik.translations(shikimoriId).map { it.toDomain() } }

    override suspend fun listedEpisodes(shikimoriId: Int, translationId: Int): Set<Int>? =
        kodik.listedEpisodes(shikimoriId, translationId)

    override suspend fun forget(shikimoriId: Int) = kodik.forget(shikimoriId)

    override suspend fun resolve(
        shikimoriId: Int,
        episode: Int,
        translation: Translation?,
    ): Result<EpisodeStream> = attempt(shikimoriId, episode) {
        // The page never says which season it lists, so a caller that remembers one
        // (WatchState.kodikSeason) outranks the default of 1. No track named means the first.
        val stream = kodik.resolve(shikimoriId, translation?.id ?: 0, episode, translation?.season ?: 1)
        val urls = stream.urls.mapNotNull { (height, url) -> Quality.ofHeight(height)?.let { it to url } }.toMap()
        if (urls.isEmpty()) throw KodikError.ParserBroken("links")
        EpisodeStream(
            animeId = shikimoriId,
            episode = stream.episode,
            translation = stream.translation.toDomain().copy(season = stream.season),
            urls = urls,
            resolvedAt = clock.instant(),
        )
    }

    private suspend fun <T> attempt(shikimoriId: Int, episode: Int?, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error.toDomainFailure(shikimoriId, episode))
    }

    /** `ui.*` only knows `domain.error`, so no Kodik, Ktor or shared-module type may leave this class. */
    private fun Throwable.toDomainFailure(shikimoriId: Int, episode: Int?): Throwable = when (this) {
        is KodikError.NoToken -> SourceUnavailable(SourceUnavailableReason.NO_KEY, this)
        is KodikError.NotFound -> EpisodeNotAvailable(shikimoriId, episode, when (missing) {
            KodikError.Missing.TITLE -> EpisodeUnavailableReason.TITLE_NOT_ON_SOURCE
            KodikError.Missing.EPISODE -> EpisodeUnavailableReason.NOT_IN_TRANSLATION
        })
        is KodikError.ParserBroken -> SourceFormatChanged(step, this)
        is NetworkException -> NetworkUnavailable(this)
        // Kodik answered and turned us away. Not HttpError: its copy names Shikimori, and nothing
        // here talks to Shikimori — and a 403 from the player host is not the connection being down.
        is ApiException -> SourceUnavailable(SourceUnavailableReason.REJECTED, this)
        // Nothing reaches the UI unclassified: an unmapped exception would otherwise land on the
        // generic "что-то пошло не так" and hide which source failed.
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
}
