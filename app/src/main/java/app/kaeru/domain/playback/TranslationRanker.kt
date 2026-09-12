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
 * 2. the earliest studio in the user's preference list whose name appears in the
 *    track title, case-insensitively — preference order beats episode counts,
 *    because a viewer who put AniLibria first means it;
 * 3. a dub over subtitles, and among dubs the one carrying the most episodes,
 *    since a half-finished track strands the viewer mid-season;
 * 4. otherwise the order the source listed, which is its own popularity guess.
 */
object TranslationRanker {

    /** The track to play, or null when the source offers nothing. Always `sort(...).firstOrNull()`. */
    fun pick(available: List<Translation>, preferred: List<String>, rememberedId: Int?): Translation? =
        available.minWithOrNull(ranking(preferred, rememberedId))

    /** The same order as [pick], for the selection sheet. Stable: tracks the rules cannot separate keep source order. */
    fun sort(available: List<Translation>, preferred: List<String>, rememberedId: Int?): List<Translation> =
        available.sortedWith(ranking(preferred, rememberedId))

    private fun ranking(preferred: List<String>, rememberedId: Int?): Comparator<Translation> =
        compareBy<Translation> { if (rememberedId != null && it.id == rememberedId) 0 else 1 }
            .thenBy { translation -> preferredRank(translation, preferred) }
            .thenBy { if (it.type == TranslationKind.VOICE) 0 else 1 }
            // Unknown counts sort as zero, so a track the source could not count never
            // outranks one it could, but still beats nothing.
            .thenByDescending { if (it.type == TranslationKind.VOICE) it.episodesCount ?: 0 else 0 }

    private fun preferredRank(translation: Translation, preferred: List<String>): Int {
        val index = preferred.indexOfFirst { studio ->
            studio.isNotBlank() && translation.title.contains(studio, ignoreCase = true)
        }
        return if (index >= 0) index else preferred.size
    }
}
