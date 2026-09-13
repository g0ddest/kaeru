package app.kaeru.player

import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.error.SourceUnavailable
import app.kaeru.domain.error.SourceUnavailableReason
import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.Quality
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.domain.source.EpisodeSourceProvider
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant

/**
 * A Kodik that never leaves the process: two tracks, twelve episodes, and every lever a test
 * needs to make it refuse — a missing episode, a rejection, an outage.
 */
class FakeEpisodeSource : EpisodeSourceProvider {
    val anilibria = Translation(11, "AniLibria.TV", TranslationKind.VOICE, episodesCount = 12)
    val studioBanda = Translation(22, "Студийная банда", TranslationKind.VOICE, episodesCount = 12)
    var resolveFailure: Throwable? = null
    var lastAired = 12

    /** Episodes the source will not serve, standing in for a Kodik that is up but unhappy. */
    var rejects: Set<Int> = emptySet()

    /** Every episode asked for, in order. */
    val resolves = mutableListOf<Int>()

    /** Runs the moment a resolve starts, so a test can look at what is already on disk. */
    var onResolve: ((Int) -> Unit)? = null

    /** While set, every resolve parks here — a Kodik round trip caught in the act. */
    var gate: CompletableDeferred<Unit>? = null

    /** How many times the catalogue was asked for its tracks, so a lazy load can be shown to be lazy. */
    var translationCalls = 0
        private set

    /** While set, the catalogue refuses to list anything — an outage between the app and Kodik. */
    var translationsFailure: Throwable? = null

    /** While set, listing the catalogue parks here, so a test can look at the screen mid-flight. */
    var translationsGate: CompletableDeferred<Unit>? = null

    override suspend fun translations(shikimoriId: Int): Result<List<Translation>> {
        translationCalls += 1
        translationsGate?.await()
        translationsFailure?.let { return Result.failure(it) }
        return Result.success(listOf(anilibria, studioBanda))
    }

    override suspend fun resolve(
        shikimoriId: Int,
        episode: Int,
        translation: Translation?,
    ): Result<EpisodeStream> {
        resolves += episode
        onResolve?.invoke(episode)
        gate?.await()
        if (episode in rejects) {
            return Result.failure(SourceUnavailable(SourceUnavailableReason.REJECTED))
        }
        resolveFailure?.let { return Result.failure(it) }
        if (episode > lastAired) return Result.failure(EpisodeNotAvailable(shikimoriId, episode))
        val track = translation ?: anilibria
        return Result.success(
            EpisodeStream(
                animeId = shikimoriId,
                episode = episode,
                translation = track,
                urls = listOf(Quality.P360, Quality.P480, Quality.P720).associateWith {
                    "https://cdn/$shikimoriId/$episode/${track.id}/${it.height}"
                },
                resolvedAt = Instant.parse("2026-09-13T10:00:00Z"),
            ),
        )
    }
}
