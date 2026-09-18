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
    /** Never called with an empty list: a check with nothing to say says nothing. */
    suspend fun post(news: List<NewEpisode>)
}
