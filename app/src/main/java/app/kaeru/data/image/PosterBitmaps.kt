package app.kaeru.data.image

import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A large icon is about 64dp; asking for twice that leaves room for a dense screen. */
private const val LARGE_ICON_PX = 256

/**
 * One poster as a bitmap, which is the one thing a notification can be given a picture as.
 *
 * A seam of its own rather than a second method on [PosterFetcher]: that one answers «is it on
 * disk», for warming, and hands nothing back. This decodes, and a caller that only wanted the file
 * warmed should not pay for that.
 */
fun interface PosterBitmaps {
    /** The picture, or null when there is none to be had — a bad address, no network, a refusal. */
    suspend fun load(url: String): Bitmap?
}

/**
 * [PosterBitmaps] over the app's own image loader, so a poster the screens have already shown is
 * read back off the same disk cache rather than fetched again.
 *
 * Memory is left out for the same reason [CoilPosterFetcher] leaves it out: this is decoded for a
 * notification shade, and pushing it into the cache would evict something that is on a screen.
 */
@Singleton
class CoilPosterBitmaps @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val loader: ImageLoader,
) : PosterBitmaps {

    override suspend fun load(url: String): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(LARGE_ICON_PX)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
        // Anything but a success — a network that has gone, an address Shikimori has retired — is
        // a notification without a picture, which is a notification.
        return (loader.execute(request) as? SuccessResult)?.image?.toBitmap()
    }
}
