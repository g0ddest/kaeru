package app.kaeru.data.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.request.CachePolicy
import okio.Path.Companion.toOkioPath

/**
 * The one image loader the app uses, with its disk cache spelled out.
 *
 * Coil would give us one of its own, and until downloads existed that was fine. It is not any
 * more: a poster that lives only in memory is a grey rectangle the next morning, and a screen of
 * downloaded episodes with no artwork on it is the offline case failing at the last step. So the
 * cache is declared here — a directory of our own, a size we chose, and a policy that says
 * «enabled» in writing rather than by default.
 *
 * It goes in the app's cache directory rather than beside the downloads, and that is deliberate.
 * An episode is something the viewer asked for and the system must not take back; a poster is
 * something the app can fetch again, so a phone running out of space should be free to drop it
 * rather than the episode it illustrates.
 *
 * 256 MB is about a thousand posters at the size this app asks for them — more titles than a list
 * holds — and Coil evicts the least recently used within that rather than growing past it.
 */
object KaeruImages {

    const val DIRECTORY = "posters"

    const val MAX_BYTES = 256L * 1024 * 1024

    fun loader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve(DIRECTORY).toOkioPath())
                .maxSizeBytes(MAX_BYTES)
                .build()
        }
        .diskCachePolicy(CachePolicy.ENABLED)
        .build()
}
