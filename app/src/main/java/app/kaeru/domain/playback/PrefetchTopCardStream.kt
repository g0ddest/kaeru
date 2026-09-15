package app.kaeru.domain.playback

import app.kaeru.domain.download.DownloadRepository
import app.kaeru.domain.repository.WatchStateRepository
import kotlinx.coroutines.flow.first

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
    private val watchStates: WatchStateRepository,
    private val downloads: DownloadRepository,
) {
    suspend operator fun invoke(animeId: Int, episode: Int) {
        if (cache.holds(animeId, episode)) return
        // Already on the device: the press plays it from there without resolving anything, so a
        // prepared link is a Kodik round trip nobody will take — and with no network it is a
        // failure quietly logged about an episode that is about to play perfectly well.
        if (downloads.completed(animeId, episode) != null) return
        // An anime with no remembered voice can never claim what is prepared for it: the take is
        // keyed on the voice the press is about to ask for, and preparing deliberately writes no
        // memory to answer with. Resolving anyway would be a Kodik call spent on links nobody can
        // take, followed by a second one on the press. A card the viewer is continuing always has
        // the row; a title they have never started does not, and that is the one this skips.
        if (watchStates.observe(animeId).first()?.translationId == null) return
        resolve(animeId, episode, persist = false).onSuccess { cache.put(it) }
    }
}
