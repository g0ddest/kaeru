package app.kaeru.domain.playback

import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind

/**
 * Which of a source's tracks a viewer most likely wants.
 *
 * One order serves both callers: the player takes its head ([pick]), the
 * selection sheet shows the whole list ([sort]). The rules, strongest first:
 *
 * 1. the track this anime was last played with (`WatchState.translationId`);
 * 2. the earliest studio in the viewer's own list whose name appears in the track title,
 *    case-insensitively — a list somebody typed by hand beats every guess below it, because a
 *    viewer who put AniLibria first means it;
 * 3. how many anime this viewer has ended up watching in that track, most first: the one piece of
 *    evidence about this viewer rather than about viewers in general;
 * 4. the same studio match against [DEFAULT_STUDIOS], the list the app ships with, which is what
 *    answers the very first anime, before there is any history to go on;
 * 5. a dub over subtitles, and among dubs the one carrying the most episodes,
 *    since a half-finished track strands the viewer mid-season;
 * 6. otherwise the order the source listed, which is its own popularity guess.
 *
 * The history arrives as a map from [TranslationUsage.of], read once per request: a comparator
 * that went to the database would run per comparison.
 */
object TranslationRanker {

    /**
     * Studios that dub most of what this app plays, best first — the opening guess for a viewer
     * with no history and no list of their own. Moved here from the preference store, which now
     * holds only what somebody actually chose: a default nobody typed is a ranking rule, not a
     * setting, and reading it back as one made the settings screen show a choice the viewer had
     * never made.
     */
    val DEFAULT_STUDIOS = listOf(
        "AniLibria", "AniDUB", "Crunchyroll", "Amazing Dubbing", "AniBaza",
        "AniMaunt", "JAM", "Dream Cast", "SHIZA Project",
    )

    /**
     * The track to play, or null when the source offers nothing. Always `sort(...).firstOrNull()`.
     *
     * [usage] has no default on purpose: a caller that has no history to offer has to say
     * `emptyMap()` and mean it, rather than dropping rule 3 by leaving an argument out.
     */
    fun pick(
        available: List<Translation>,
        preferred: List<String>,
        rememberedId: Int?,
        usage: Map<Int, Int>,
    ): Translation? = available.minWithOrNull(ranking(preferred, rememberedId, usage))

    /** The same order as [pick], for the selection sheet. Stable: tracks the rules cannot separate keep source order. */
    fun sort(
        available: List<Translation>,
        preferred: List<String>,
        rememberedId: Int?,
        usage: Map<Int, Int>,
    ): List<Translation> = available.sortedWith(ranking(preferred, rememberedId, usage))

    private fun ranking(preferred: List<String>, rememberedId: Int?, usage: Map<Int, Int>): Comparator<Translation> =
        compareBy<Translation> { if (rememberedId != null && it.id == rememberedId) 0 else 1 }
            .thenBy { translation -> studioRank(translation, preferred) }
            // A track nobody has watched counts as zero, so it sits behind every track that has
            // been watched at all and ahead of nothing.
            .thenByDescending { usage[it.id] ?: 0 }
            .thenBy { translation -> studioRank(translation, DEFAULT_STUDIOS) }
            .thenBy { if (it.type == TranslationKind.VOICE) 0 else 1 }
            // Unknown counts sort as zero, so a track the source could not count never
            // outranks one it could, but still beats nothing.
            .thenByDescending { if (it.type == TranslationKind.VOICE) it.episodesCount ?: 0 else 0 }

    private fun studioRank(translation: Translation, studios: List<String>): Int {
        val index = studios.indexOfFirst { studio ->
            studio.isNotBlank() && translation.title.contains(studio, ignoreCase = true)
        }
        return if (index >= 0) index else studios.size
    }
}
