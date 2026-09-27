package app.kaeru.shared.domain.playback

/** Whether a track is spoken or read. */
enum class TrackKind { VOICE, SUBTITLES }

/**
 * What the ranking needs to know about one track a source offers: nothing more.
 *
 * Each platform keeps its own track type and hands the ranker this much of it; the answers come
 * back as positions in the list it was given ([TranslationRanker.order]), so a caller never has
 * to map a candidate back onto the track it came from.
 */
data class TranslationCandidate(
    val id: Int,
    val title: String,
    val kind: TrackKind,
    /** How many episodes the source says this track carries; null when it could not count. */
    val episodesCount: Int?,
)

/**
 * Which of a source's tracks a viewer most likely wants.
 *
 * One order serves every caller: the player takes its head ([pick]), the selection sheet shows the
 * whole list ([sort]), and a stand-in for a track that lacks an episode is looked for down the same
 * list ([substitutionOrder]). The rules, strongest first:
 *
 * 1. the track this anime was last played with;
 * 2. the earliest studio in the viewer's own list whose name appears in the track title,
 *    case-insensitively — a list somebody typed by hand beats every guess below it;
 * 3. how many anime this viewer has ended up watching in that track, most first ([TranslationUsage]);
 * 4. the same studio match against [DEFAULT_STUDIOS], the list the app ships with, which is what
 *    answers the very first anime, before there is any history to go on;
 * 5. a dub over subtitles, and among dubs the one carrying the most episodes,
 *    since a half-finished track strands the viewer mid-season;
 * 6. otherwise the order the source listed, which is its own popularity guess.
 */
object TranslationRanker {

    /** Studios that dub most of what the app plays, best first: the opening guess for a viewer with no history. */
    val DEFAULT_STUDIOS: List<String> = listOf(
        "AniLibria", "AniDUB", "Crunchyroll", "Amazing Dubbing", "AniBaza",
        "AniMaunt", "JAM", "Dream Cast", "SHIZA Project",
    )

    /**
     * The ranking itself. [usage] has no default on purpose: a caller with no history says
     * `emptyMap()` and means it, rather than dropping rule 3 by leaving an argument out.
     */
    fun comparator(preferred: List<String>, rememberedId: Int?, usage: Map<Int, Int>): Comparator<TranslationCandidate> =
        compareBy<TranslationCandidate> { if (rememberedId != null && it.id == rememberedId) 0 else 1 }
            .thenBy { studioRank(it.title, preferred) }
            // A track nobody has watched counts as zero: behind every track watched at all.
            .thenByDescending { usage[it.id] ?: 0 }
            .thenBy { studioRank(it.title, DEFAULT_STUDIOS) }
            .thenBy { if (it.kind == TrackKind.VOICE) 0 else 1 }
            // Unknown counts sort as zero, so a track the source could not count never outranks one it could.
            .thenByDescending { if (it.kind == TrackKind.VOICE) it.episodesCount ?: 0 else 0 }

    /**
     * Positions in [tracks], best first. Stable: tracks the rules cannot separate keep source order.
     * Positions rather than tracks, so each platform maps them straight back onto its own list.
     */
    fun order(tracks: List<TranslationCandidate>, preferred: List<String>, rememberedId: Int?, usage: Map<Int, Int>): List<Int> {
        val ranking = comparator(preferred, rememberedId, usage)
        return tracks.indices.sortedWith { a, b -> ranking.compare(tracks[a], tracks[b]) }
    }

    /** The same order as [order], as the tracks themselves. */
    fun sort(
        tracks: List<TranslationCandidate>,
        preferred: List<String>,
        rememberedId: Int?,
        usage: Map<Int, Int>,
    ): List<TranslationCandidate> = order(tracks, preferred, rememberedId, usage).map { tracks[it] }

    /** The track to play, or null when the source offers nothing. Always the head of [sort]. */
    fun pick(
        tracks: List<TranslationCandidate>,
        preferred: List<String>,
        rememberedId: Int?,
        usage: Map<Int, Int>,
    ): TranslationCandidate? = order(tracks, preferred, rememberedId, usage).firstOrNull()?.let { tracks[it] }

    /**
     * Where to look for a stand-in when the track [chosenId] lacks an episode: positions in [tracks]
     * in the ranking's order with the chosen track taken out, so the stand-in is the one the viewer
     * would most likely have picked by hand. The caller still skips any whose [lacksEpisode] is true.
     */
    fun substitutionOrder(
        tracks: List<TranslationCandidate>,
        chosenId: Int,
        preferred: List<String>,
        rememberedId: Int?,
        usage: Map<Int, Int>,
    ): List<Int> = order(tracks, preferred, rememberedId, usage).filter { tracks[it].id != chosenId }

    /**
     * Whether a track carries [episode], as far as anything already read can say; null when nothing can.
     *
     * The track's own list of episodes, once read, is the answer. Failing that, the count the
     * catalogue gives — but only against the first season's numbering: the catalogue never says
     * which season it counted, and a title mapped to a later season numbers past that count.
     */
    fun carriesEpisode(episode: Int, listedEpisodes: Set<Int>?, season: Int, episodesCount: Int?): Boolean? {
        if (listedEpisodes != null) return episode in listedEpisodes
        if (season != 1) return null
        return episodesCount?.let { episode <= it }
    }

    /** «В озвучке нет серии N»: known not to carry it. An unknown is not a lack. */
    fun lacksEpisode(episode: Int, listedEpisodes: Set<Int>?, season: Int, episodesCount: Int?): Boolean =
        carriesEpisode(episode, listedEpisodes, season, episodesCount) == false

    /** What the player says when another track stood in for the one asked for. */
    fun substitutionNotice(askedFor: String, episode: Int, playing: String): String =
        "В озвучке $askedFor серии $episode нет — включена $playing"

    private fun studioRank(title: String, studios: List<String>): Int {
        val index = studios.indexOfFirst { studio -> studio.isNotBlank() && title.contains(studio, ignoreCase = true) }
        return if (index >= 0) index else studios.size
    }
}
