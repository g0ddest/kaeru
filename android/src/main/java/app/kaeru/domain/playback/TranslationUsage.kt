package app.kaeru.domain.playback

import app.kaeru.domain.model.WatchState
import app.kaeru.shared.domain.playback.RememberedTrack
import app.kaeru.shared.domain.playback.TranslationUsage as SharedUsage

/**
 * How often this viewer actually settles on each track, counted from the `watch_state` rows by the
 * shared rule ([app.kaeru.shared.domain.playback.TranslationUsage]). The caller reads the rows once
 * per request and hands the map to [TranslationRanker], instead of asking the database inside a
 * comparator.
 */
object TranslationUsage {

    /** Track id → how many anime are remembered against it. */
    fun of(states: List<WatchState>): Map<Int, Int> =
        SharedUsage.of(states.map { RememberedTrack(it.animeId, it.translationId) })

    /** Whether [id] is one of the tracks this viewer reaches for, by the count [of] produced. */
    fun oftenChosen(usage: Map<Int, Int>, id: Int): Boolean = SharedUsage.oftenChosen(usage, id)
}
