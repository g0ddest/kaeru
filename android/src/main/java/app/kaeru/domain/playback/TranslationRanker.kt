package app.kaeru.domain.playback

import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import app.kaeru.shared.domain.playback.TrackKind
import app.kaeru.shared.domain.playback.TranslationCandidate
import app.kaeru.shared.domain.playback.TranslationRanker as SharedRanker

/**
 * Which of a source's tracks a viewer most likely wants — the shared ranking
 * ([app.kaeru.shared.domain.playback.TranslationRanker], where the rules and their tests live),
 * applied to this app's own [Translation].
 *
 * The shared rules answer in positions, so every track comes back exactly as it went in, season
 * and all. The history arrives as a map from [TranslationUsage.of], read once per request.
 */
object TranslationRanker {

    /** The studios the app ships with, as the opening guess for a viewer with no history. */
    val DEFAULT_STUDIOS: List<String> = SharedRanker.DEFAULT_STUDIOS

    /**
     * The track to play, or null when the source offers nothing. Always `sort(...).firstOrNull()`.
     *
     * [usage] has no default on purpose: a caller that has no history to offer has to say
     * `emptyMap()` and mean it, rather than dropping a rule by leaving an argument out.
     */
    fun pick(
        available: List<Translation>,
        preferred: List<String>,
        rememberedId: Int?,
        usage: Map<Int, Int>,
    ): Translation? = SharedRanker.order(available.candidates(), preferred, rememberedId, usage).firstOrNull()?.let(available::get)

    /** The same order as [pick], for the selection sheet. Stable: tracks the rules cannot separate keep source order. */
    fun sort(
        available: List<Translation>,
        preferred: List<String>,
        rememberedId: Int?,
        usage: Map<Int, Int>,
    ): List<Translation> = SharedRanker.order(available.candidates(), preferred, rememberedId, usage).map(available::get)

    /** Where a stand-in for [chosen] is looked for: the same order, without [chosen]. */
    fun substitutes(
        available: List<Translation>,
        chosen: Translation,
        preferred: List<String>,
        rememberedId: Int?,
        usage: Map<Int, Int>,
    ): List<Translation> =
        SharedRanker.substitutionOrder(available.candidates(), chosen.id, preferred, rememberedId, usage).map(available::get)

    private fun List<Translation>.candidates(): List<TranslationCandidate> = map { track ->
        TranslationCandidate(
            id = track.id,
            title = track.title,
            kind = if (track.type == TranslationKind.VOICE) TrackKind.VOICE else TrackKind.SUBTITLES,
            episodesCount = track.episodesCount,
        )
    }
}
