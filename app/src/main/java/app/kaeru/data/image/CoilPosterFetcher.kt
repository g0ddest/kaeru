package app.kaeru.data.image

import android.content.Context
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PosterFetcher] over the app's own image loader, so a warmed poster lands in the very cache the
 * screens read from.
 *
 * Memory is deliberately left out of it: this is fetched for a screen nobody is looking at, and
 * pushing it into memory would evict something that is on one.
 */
@Singleton
class CoilPosterFetcher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val loader: ImageLoader,
) : PosterFetcher {

    override suspend fun fetch(url: String): Boolean {
        val request = ImageRequest.Builder(context)
            .data(url)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
        return loader.execute(request) is SuccessResult
    }
}
