package app.kaeru.domain.playback

import app.kaeru.domain.error.EpisodeNotAvailable
import app.kaeru.domain.error.EpisodeUnavailableReason
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
 *
 * A track that does not carry the episode is not the end of the road. Studios release at their
 * own pace, and a viewer whose dub is a week behind still has the episode in three others; so,
 * where the caller allows it, the other tracks are asked in the order the chooser would list
 * them, and the first that has it plays — marked as standing in, so the memory stays on the voice
 * the viewer actually chose. Only when every track has been asked, or ruled out by what its own
 * page lists, does the answer become «ни в одной озвучке».
 */
class ResolveEpisodeStream(
    private val source: EpisodeSourceProvider,
    private val watchStates: WatchStateRepository,
    private val prefs: PlaybackPreferences,
    private val clock: Clock,
    private val prefetch: StreamPrefetchCache,
) {
    /**
     * @param translationOverride a track named by the caller. With [substitute] false it is taken
     *   as given — including its season — and becomes the new memory, so no catalogue call is
     *   needed; with [substitute] true it is only where the search starts.
     * @param persist whether what is resolved becomes this anime's memory. False for a resolve
     *   done ahead of time, on the chance the viewer presses play: preparing an episode must not
     *   move the row that says where they actually are.
     * @param substitute whether another track may stand in when the one asked for lacks the
     *   episode. True for every open the viewer did not pin a voice on — the watch button, the
     *   episode list, autoplay, a retry — and false for a voice somebody chose: a pick from the
     *   chooser, a friend's voice arriving over a shared viewing, a download being re-signed. A
     *   chosen voice that cannot play is an honest failure; a chosen voice quietly replaced is a
     *   lie. Defaults to «no voice was named», which is the plain case.
     */
    suspend operator fun invoke(
        animeId: Int,
        episode: Int,
        translationOverride: Translation? = null,
        persist: Boolean = true,
        substitute: Boolean = translationOverride == null,
    ): Result<Resolution> {
        val rows = watchStates.observeAll().first()
        val remembered = rows.rowFor(animeId)
        // Looked for here rather than in the controller, because only here is it known which
        // voice is about to be asked for: an anime whose remembered voice has changed since the
        // links were prepared must not be handed the ones prepared for the old one. Nor may a
        // stand-in prepared ahead answer a caller that pinned the voice it stood in for.
        prefetch.take(animeId, episode, translationOverride?.id ?: remembered?.translationId)
            ?.takeIf { it.insteadOf == null || substitute }
            ?.let { ready ->
                if (persist) remember(ready, remembered)
                return Result.success(ready)
            }
        val usage = TranslationUsage.of(rows)
        val preferred = prefs.preferredTranslations.first()
        var listed: List<Translation>? = null
        val chosen = if (translationOverride != null) {
            translationOverride
        } else {
            val available = source.translations(animeId).getOrElse { return Result.failure(it) }
            listed = available
            TranslationRanker.pick(available, preferred, remembered?.translationId, usage)?.withSeasonOf(remembered)
        }

        // A source with nothing listed is still asked: only it can say whether this is an
        // unknown anime, an episode that has not aired, or a page that stopped parsing.
        val resolution = source.resolve(animeId, episode, chosen).fold(
            onSuccess = { Resolution(it) },
            onFailure = { failure ->
                if (!substitute || chosen == null || !failure.lacksEpisodeInTrack()) return Result.failure(failure)
                // A carried voice arrives without the catalogue; it is only read once it is needed.
                val others = listed ?: source.translations(animeId).getOrElse { return Result.failure(it) }
                standIn(animeId, episode, chosen, others, preferred, remembered, usage)
                    .getOrElse { return Result.failure(it) }
            },
        )
        if (persist) remember(resolution, remembered)
        return Result.success(resolution)
    }

    /**
     * The tracks on offer, ordered the way the selection sheet should show them, each carrying
     * whether this viewer keeps choosing it.
     *
     * Nothing is marked once this anime remembers a track of its own: the sheet already marks that
     * one as chosen, and a habit is only worth pointing out where there is no answer yet.
     *
     * @param playing the track the player is using right now, if any. A film whose Kodik page
     *   carries no translations box lists nothing at all, and a chooser that opens on an empty
     *   list is the app denying what the viewer can plainly hear; the track that is playing is
     *   the honest answer to «which voice is this», so it stands in for the list it is missing
     *   from. It is never added to a list the source did answer with.
     * @param episode the episode the chooser is open over, so each track can say whether it has
     *   it — out of what is already known, never by asking. Null for a chooser about the anime
     *   rather than one episode of it, where the question does not arise.
     */
    suspend fun translations(
        animeId: Int,
        playing: Translation? = null,
        episode: Int? = null,
    ): Result<List<RankedTranslation>> {
        val rows = watchStates.observeAll().first()
        val remembered = rows.rowFor(animeId)
        val listed = source.translations(animeId).getOrElse { return Result.failure(it) }
        val available = listed.ifEmpty { listOfNotNull(playing) }
        val seasoned = available.map { it.withSeasonOf(remembered) }
        val usage = TranslationUsage.of(rows)
        val rememberedId = remembered?.translationId
        val sorted = TranslationRanker.sort(seasoned, prefs.preferredTranslations.first(), rememberedId, usage)
        return Result.success(
            sorted.map { track ->
                RankedTranslation(
                    translation = track,
                    oftenChosen = rememberedId == null && TranslationUsage.oftenChosen(usage, track.id),
                    hasEpisode = episode?.let { track.knownToHave(animeId, it) },
                )
            },
        )
    }

    /**
     * Drops what the source has cached about this anime, so the next resolve asks it afresh.
     *
     * For «Повторить» over an episode nobody had: a studio that released it since is invisible
     * to a catalogue read six hours ago, and to the lists read under that catalogue.
     */
    suspend fun forgetCatalogue(animeId: Int) = source.forget(animeId)

    /**
     * The other tracks, asked in the chooser's order until one has the episode.
     *
     * The order is the ranking with [chosen] taken out — remembered voice, the viewer's studios,
     * the ones they keep choosing, the studios the app ships with, the source's own order — so
     * the stand-in is the one the viewer would most likely have picked by hand. A track that
     * cannot have the episode is not asked: its own page, once read, has already answered, and
     * for the first season the count on the catalogue page is as good. Anything but «not in this
     * track» stops the walk where it is: a source that has stopped answering is that failure,
     * not a missing episode, and «Повторить» is its answer.
     */
    private suspend fun standIn(
        animeId: Int,
        episode: Int,
        chosen: Translation,
        available: List<Translation>,
        preferred: List<String>,
        remembered: WatchState?,
        usage: Map<Int, Int>,
    ): Result<Resolution> {
        val candidates = TranslationRanker.sort(available, preferred, remembered?.translationId, usage)
            .filter { it.id != chosen.id }
            .map { it.withSeasonOf(remembered) }
        for (candidate in candidates) {
            if (candidate.knownToHave(animeId, episode) == false) continue
            source.resolve(animeId, episode, candidate).fold(
                onSuccess = { return Result.success(Resolution(it, insteadOf = chosen)) },
                onFailure = { if (!it.lacksEpisodeInTrack()) return Result.failure(it) },
            )
        }
        return Result.failure(EpisodeNotAvailable(animeId, episode, EpisodeUnavailableReason.NOT_IN_ANY_TRANSLATION))
    }

    /**
     * Whether this track carries the episode, as far as anything already read can say; null when
     * nothing can.
     *
     * The track's own page, once read, is the answer. Failing that, the count the catalogue page
     * gives — but only against the first season's numbering: the page never says which season it
     * counted, and a title mapped to a later season numbers its episodes past that count.
     */
    private suspend fun Translation.knownToHave(animeId: Int, episode: Int): Boolean? {
        source.listedEpisodes(animeId, id)?.let { return episode in it }
        if (season != 1) return null
        return episodesCount?.let { episode <= it }
    }

    /** The track asked for is there; only the episode is not. The one failure another track can answer. */
    private fun Throwable.lacksEpisodeInTrack(): Boolean =
        this is EpisodeNotAvailable && reason == EpisodeUnavailableReason.NOT_IN_TRANSLATION

    /**
     * What this anime remembers, out of the one snapshot both questions are answered from.
     *
     * Every call here reads the whole table once — a few dozen tiny rows, one per anime ever
     * started — rather than asking twice: two reads would register two Room observers, and could
     * answer from either side of a write that landed between them. The map goes to the ranker
     * built, never looked up inside the comparator, which would run per comparison.
     *
     * The anime being ranked votes in its own usage count, deliberately. Its remembered track gets
     * one vote toward the habit that is about to put it first anyway — rule 1 has already decided,
     * and the habit chip is suppressed outright whenever a track is remembered — so the vote can
     * never show up on screen, and leaving it in keeps [TranslationUsage.of] a plain count of the
     * table rather than a count with an exception in it.
     */
    private fun List<WatchState>.rowFor(animeId: Int): WatchState? = firstOrNull { it.animeId == animeId }

    /** The season is a property of the anime's mapping onto Kodik, not of one track. */
    private fun Translation.withSeasonOf(remembered: WatchState?): Translation =
        remembered?.kodikSeason?.let { copy(season = it) } ?: this

    /**
     * Writes back what actually played. A position only survives within its own episode:
     * starting another one rewinds to the beginning.
     *
     * A voice that stood in is not a choice, so it does not become the memory: the row keeps the
     * voice it had, and the next episode is asked for in that voice first. Only an anime with no
     * voice remembered takes the stand-in — there is nothing to overwrite, and the ranking's guess
     * it replaced was no more the viewer's choice than it is.
     *
     * Best effort by design — the stream is already playable, and a lost memory costs the
     * viewer one re-pick, so a logout or a disk failure here must not fail playback.
     */
    private suspend fun remember(resolution: Resolution, previous: WatchState?) {
        val stream = resolution.stream
        val sameEpisode = previous?.episode == stream.episode
        val kept = previous?.takeIf { resolution.insteadOf != null && it.translationId != null }
        try {
            watchStates.save(
                WatchState(
                    animeId = stream.animeId,
                    episode = stream.episode,
                    positionMs = if (sameEpisode) previous.positionMs else 0,
                    durationMs = if (sameEpisode) previous.durationMs else 0,
                    translationId = kept?.translationId ?: stream.translation.id,
                    translationTitle = if (kept != null) kept.translationTitle else stream.translation.title,
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
