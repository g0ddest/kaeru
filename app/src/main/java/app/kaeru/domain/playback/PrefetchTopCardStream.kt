package app.kaeru.domain.playback

/**
 * Resolves the episode the home screen is offering, before the viewer asks for it.
 *
 * The whole app is built around one press, and that press used to be followed by a page fetch, a
 * parse and a signing round trip — seconds of a spinner over a black screen. The card is on
 * screen for longer than that while the viewer reads it, so the work happens then instead and
 * [StreamPrefetchCache] holds the answer until they press.
 *
 * It is deliberately quiet. Nothing it does is visible: a failure is dropped, because the real
 * press will resolve again and report properly, and nothing is written down, because preparing an
 * episode is not the same as starting one.
 *
 * At most one Kodik round trip per card: an episode already prepared is left alone, so a home
 * screen that recomposes, refreshes or comes back from the back stack costs nothing.
 */
class PrefetchTopCardStream(
    private val resolve: ResolveEpisodeStream,
    private val cache: StreamPrefetchCache,
) {
    suspend operator fun invoke(animeId: Int, episode: Int) {
        if (cache.holds(animeId, episode)) return
        resolve(animeId, episode, persist = false).onSuccess { cache.put(it) }
    }
}
