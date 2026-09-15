package app.kaeru.data.image

import app.kaeru.domain.download.DownloadRepository
import app.kaeru.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts the poster of every downloaded title on disk, so an offline screen has artwork on it.
 *
 * The screens that draw posters do so through Coil, which fetches them from the network on demand
 * — which is exactly what is not available when these downloads are being watched. A poster is a
 * few tens of kilobytes against an episode's few hundred megabytes, so fetching it alongside the
 * download costs nothing and is the difference between «Загрузки» being a list of titles and being
 * a list of grey rectangles.
 *
 * It watches the downloads rather than hooking the enqueue, and that is on purpose: the same rule
 * then covers every way a download can appear — the sheet, the player, a queue the engine resumed
 * after a restart — and a poster the cache has since evicted is fetched again the next time the
 * app starts.
 */
@Singleton
class PosterWarmer @Inject constructor(
    private val downloads: DownloadRepository,
    private val library: LibraryRepository,
    private val posters: PosterFetcher,
) {

    /** Titles whose poster has been asked for this session, so one is fetched once. */
    private val warmed = mutableSetOf<Int>()

    fun start(scope: CoroutineScope) {
        scope.launch {
            downloads.observeAll()
                .map { rows -> rows.map { it.animeId }.toSet() }
                .distinctUntilChanged()
                // Confined to this one coroutine, which is what makes `warmed` safe without a lock.
                .collect { ids -> ids.forEach { warm(it) } }
        }
    }

    private suspend fun warm(animeId: Int) {
        if (animeId in warmed) return
        // A title Room has not heard of yet has no poster to fetch. It is not marked as done, so
        // the next change to the downloads tries again — by which time the «Загрузки» screen will
        // have asked Shikimori for it.
        val poster = library.observeAnimeDetails(animeId).first()?.posterUrl?.takeIf { it.isNotBlank() } ?: return
        // Only a poster actually on disk counts as done. Marking it before the fetch meant the one
        // case this exists for — the network going while a queue was being set up — was also the
        // one case it never retried: the title was already ticked off for the session.
        //
        // Failures stay silent otherwise. The next change to the downloads, or the next start, is
        // another go, and both come well before the poster is needed.
        if (posters.fetch(poster)) warmed += animeId
    }
}

/**
 * Putting one image on disk, as the one thing this needs of Coil.
 *
 * A seam rather than the loader itself, because «marked as done only when the fetch worked» is the
 * whole of the logic here and an `ImageLoader` is a dozen members a test would have to stand in
 * for. [CoilPosterFetcher] is the real one; a test hands in a function.
 */
fun interface PosterFetcher {
    /** True when the image is now in the disk cache. */
    suspend fun fetch(url: String): Boolean
}
