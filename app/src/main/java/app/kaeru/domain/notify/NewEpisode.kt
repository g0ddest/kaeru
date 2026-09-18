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
    /**
     * The episode to start: the first one the viewer has not watched.
     *
     * Equal to [aired] for somebody who is up to date, and behind it for somebody who is not. It
     * is what «Смотреть» opens, because starting the tenth episode of a show they abandoned at the
     * fifth is not the offer anybody wants.
     */
    val episode: Int,
    /**
     * The episode that actually came out, which is what the news is about.
     *
     * Kept apart from [episode] because saying «Вышла 6 серия» about something that aired a
     * fortnight ago reads as the app being confused. What aired is the sentence; what to play is
     * the button, and the two are only the same number when the viewer is caught up.
     */
    val aired: Int,
)
