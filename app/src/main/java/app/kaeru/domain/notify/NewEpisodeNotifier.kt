package app.kaeru.domain.notify

/**
 * Whatever tells the viewer that an episode is out.
 *
 * An interface here, and the only Android in the whole feature on the other side of it: deciding
 * what is news is a decision about a list and a table, and a test of that decision should not need
 * a notification manager, a channel or a bitmap.
 *
 * Nothing here returns or throws. A notification that could not be posted — permission revoked
 * between the check and the call, a phone with them turned off — costs the viewer one episode
 * announcement, and is not a reason to run the whole check again in fifteen minutes.
 */
interface NewEpisodeNotifier {
    /**
     * Whether anything posted now would actually reach the viewer.
     *
     * Asked before the check spends a single row of its memory, not only before it publishes. News
     * is consumed by being written down — a pair written down is never offered again — so a check
     * that wrote while the platform was dropping everything would swallow the very episodes the
     * viewer turned the setting on for, permanently, and granting the permission afterwards would
     * not bring them back. See [CheckNewEpisodes].
     */
    fun canPost(): Boolean

    /** Never called with an empty list: a check with nothing to say says nothing. */
    suspend fun post(news: List<NewEpisode>)

    /**
     * Takes back whatever was said about one title, because the viewer has now acted on it.
     *
     * Not the same as a tap: pressing a button on a notification leaves it standing, so the card
     * about an episode that is already playing would sit in the shade until it was swiped away.
     * A title nothing was ever said about is not an error — this is called from a screen that
     * cannot know whether the card is still there.
     */
    suspend fun clear(animeId: Int)
}
