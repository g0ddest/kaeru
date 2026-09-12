package app.kaeru.domain.model

/**
 * One track a video source offers for an anime: a dub studio or a subtitle group.
 *
 * [id] is the source's own translation id, stable across episodes, which is what
 * a remembered choice is stored by.
 */
data class Translation(
    val id: Int,
    val title: String,
    val type: TranslationKind,
    val episodesCount: Int?,
    val season: Int = 1,
)

enum class TranslationKind { VOICE, SUBTITLES }
