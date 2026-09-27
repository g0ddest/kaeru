package app.kaeru.shared.domain.playback

/** The track one anime remembers playing in; null when it remembers none. */
data class RememberedTrack(val animeId: Int, val translationId: Int?)

/**
 * How often this viewer actually settles on each track.
 *
 * Every anime remembers the track it was last played with, so counting those memories says which
 * studio this viewer keeps coming back to — a better opening guess for a new anime than any list
 * shipped with the app. Read once per request and handed to [TranslationRanker] as a map.
 */
object TranslationUsage {

    /** From how many anime a track has to carry before the chooser calls it a habit rather than a coincidence. */
    const val OFTEN_CHOSEN_FROM: Int = 2

    /**
     * Track id → how many anime are remembered against it.
     *
     * Counted per anime (the first row of each), so a list that repeats one anime cannot inflate
     * its track. Anime with nothing remembered are left out entirely.
     */
    fun of(remembered: List<RememberedTrack>): Map<Int, Int> = remembered
        .distinctBy { it.animeId }
        .mapNotNull { it.translationId }
        .groupingBy { it }
        .eachCount()

    /** Whether [id] is one of the tracks this viewer reaches for, by the count [of] produced. */
    fun oftenChosen(usage: Map<Int, Int>, id: Int): Boolean = (usage[id] ?: 0) >= OFTEN_CHOSEN_FROM
}
