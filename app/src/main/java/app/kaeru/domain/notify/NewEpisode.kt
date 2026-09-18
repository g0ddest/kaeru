package app.kaeru.domain.notify

/**
 * One episode worth telling somebody about, with everything a notification needs to name it.
 *
 * Flattened out of [app.kaeru.domain.model.LibraryEntry] on purpose: what is published is a title,
 * a poster and a number, and handing the whole entry across would let the layer that draws the
 * notification start making decisions of its own about which episode it is really about.
 */
data class NewEpisode(
    val animeId: Int,
    val title: String,
    val posterUrl: String?,
    val episode: Int,
)
