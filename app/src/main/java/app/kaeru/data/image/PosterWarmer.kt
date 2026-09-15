package app.kaeru.data.image

import android.content.Context
import app.kaeru.domain.download.DownloadRepository
import app.kaeru.domain.repository.LibraryRepository
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @param:ApplicationContext private val context: Context,
    private val downloads: DownloadRepository,
    private val library: LibraryRepository,
    private val loader: ImageLoader,
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
        warmed += animeId
        val request = ImageRequest.Builder(context)
            .data(poster)
            // Memory is for what is on screen. This poster is being fetched for a screen nobody is
            // looking at, and pushing it into memory would evict something that is.
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
        // Failures are silent by design: offline this will fail, and it will be retried on the next
        // start, which is well before the poster is needed.
        loader.execute(request)
    }
}
