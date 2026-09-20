package app.kaeru.domain.playback

import app.kaeru.domain.model.WatchState

/**
 * How often this viewer actually settles on each track.
 *
 * The evidence is already on disk: every anime remembers the track it was last played with, so
 * counting those memories says which studio this particular viewer keeps coming back to — which
 * is a better opening guess for an anime they have never watched than any list shipped with the
 * app. Pure and cheap on purpose: the caller reads the rows once per request and hands the map to
 * [TranslationRanker], instead of asking the database inside a comparator.
 */
object TranslationUsage {

    /** From how many anime a track has to carry before the chooser calls it a habit rather than a coincidence. */
    const val OFTEN_CHOSEN_FROM = 2

    /**
     * Track id → how many anime are remembered against it.
     *
     * Counted per anime rather than per row, so a list that happens to repeat one anime cannot
     * inflate its track. Anime with nothing remembered say nothing about anybody's taste and are
     * left out entirely, rather than counted under a zero key.
     */
    fun of(states: List<WatchState>): Map<Int, Int> = states
        .distinctBy { it.animeId }
        .mapNotNull { it.translationId }
        .groupingBy { it }
        .eachCount()

    /** Whether [id] is one of the tracks this viewer reaches for, by the count [of] produced. */
    fun oftenChosen(usage: Map<Int, Int>, id: Int): Boolean = (usage[id] ?: 0) >= OFTEN_CHOSEN_FROM
}
