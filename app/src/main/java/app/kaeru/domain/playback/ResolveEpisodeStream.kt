package app.kaeru.domain.playback

import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.WatchState
import app.kaeru.domain.repository.WatchStateRepository
import app.kaeru.domain.source.EpisodeSourceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock

/**
 * Turns "play episode N of this anime" into signed URLs, deciding on the way which
 * track to play and remembering that decision for next time.
 *
 * The memory is `WatchState`: [WatchState.translationId] holds the last track and
 * [WatchState.kodikSeason] the Kodik season this anime was mapped to. Both ride on the
 * same single row that carries the playback position, so remembering a track and
 * remembering a position cannot disagree.
 */
class ResolveEpisodeStream(
    private val source: EpisodeSourceProvider,
    private val watchStates: WatchStateRepository,
    private val prefs: PlaybackPreferences,
    private val clock: Clock,
) {
    /**
     * @param translationOverride a track the viewer picked by hand. It is taken as given —
     *   including its season — and becomes the new memory, so no catalogue call is needed.
     */
    suspend operator fun invoke(
        animeId: Int,
        episode: Int,
        translationOverride: Translation? = null,
    ): Result<EpisodeStream> {
        val remembered = watchStates.observe(animeId).first()
        val chosen = if (translationOverride != null) {
            translationOverride
        } else {
            val available = source.translations(animeId).getOrElse { return Result.failure(it) }
            TranslationRanker.pick(
                available,
                prefs.preferredTranslations.first(),
                remembered?.translationId,
                usage(),
            )?.withSeasonOf(remembered)
        }

        // A source with nothing listed is still asked: only it can say whether this is an
        // unknown anime, an episode that has not aired, or a page that stopped parsing.
        val stream = source.resolve(animeId, episode, chosen).getOrElse { return Result.failure(it) }
        remember(stream, remembered)
        return Result.success(stream)
    }

    /**
     * The tracks on offer, ordered the way the selection sheet should show them, each carrying
     * whether this viewer keeps choosing it.
     *
     * Nothing is marked once this anime remembers a track of its own: the sheet already marks that
     * one as chosen, and a habit is only worth pointing out where there is no answer yet.
     */
    suspend fun translations(animeId: Int): Result<List<RankedTranslation>> {
        val remembered = watchStates.observe(animeId).first()
        val available = source.translations(animeId).getOrElse { return Result.failure(it) }
        val seasoned = available.map { it.withSeasonOf(remembered) }
        val usage = usage()
        val rememberedId = remembered?.translationId
        val sorted = TranslationRanker.sort(seasoned, prefs.preferredTranslations.first(), rememberedId, usage)
        return Result.success(
            sorted.map { track ->
                RankedTranslation(
                    translation = track,
                    oftenChosen = rememberedId == null && TranslationUsage.oftenChosen(usage, track.id),
                )
            },
        )
    }

    /**
     * How often each track has been chosen, across every anime this device has played.
     *
     * Read once per request and handed to the ranker as a map: the rows are a few dozen at most,
     * and the alternative — a lookup inside the comparator — would hit the database once per
     * comparison and once per recomposition of whatever showed the result.
     */
    private suspend fun usage(): Map<Int, Int> = TranslationUsage.of(watchStates.observeAll().first())

    /** The season is a property of the anime's mapping onto Kodik, not of one track. */
    private fun Translation.withSeasonOf(remembered: WatchState?): Translation =
        remembered?.kodikSeason?.let { copy(season = it) } ?: this

    /**
     * Writes back what actually played. A position only survives within its own episode:
     * starting another one rewinds to the beginning.
     *
     * Best effort by design — the stream is already playable, and a lost memory costs the
     * viewer one re-pick, so a logout or a disk failure here must not fail playback.
     */
    private suspend fun remember(stream: EpisodeStream, previous: WatchState?) {
        val sameEpisode = previous?.episode == stream.episode
        try {
            watchStates.save(
                WatchState(
                    animeId = stream.animeId,
                    episode = stream.episode,
                    positionMs = if (sameEpisode) previous.positionMs else 0,
                    durationMs = if (sameEpisode) previous.durationMs else 0,
                    translationId = stream.translation.id,
                    kodikSeason = stream.translation.season,
                    updatedAt = clock.instant(),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Nothing to tell the viewer: playback continues, the choice is simply not remembered.
        }
    }
}
