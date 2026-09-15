package app.kaeru.player

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import app.kaeru.data.download.DownloadCache

/** What the notification, the lock screen and a car head unit show while this stream plays. */
data class StreamMetadata(val title: String, val subtitle: String?, val artworkUrl: String?)

/**
 * Turns a resolved link into something Media3 will play.
 *
 * Kodik serves one signed manifest per height rather than an adaptive master playlist, and
 * both the manifest and every segment are refused unless they carry the browser headers the
 * player page used — so the headers belong to the data source, not to a single request.
 */
@UnstableApi
object MediaItemFactory {

    fun mediaItem(url: String, metadata: StreamMetadata?, mimeType: String? = null): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (mimeType != null) builder.setMimeType(mimeType)
        if (metadata != null) {
            builder.setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(metadata.title)
                    .setSubtitle(metadata.subtitle)
                    .setArtist(metadata.subtitle)
                    .setArtworkUri(metadata.artworkUrl?.toUri())
                    .setMediaType(MediaMetadata.MEDIA_TYPE_TV_SHOW)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
        }
        return builder.build()
    }

    /**
     * The same episode, addressed to a Chromecast. The type has to be spelled out: a receiver
     * is handed a URL and a content type, never a guess from the extension, and media3's
     * converter refuses an item without one.
     */
    fun castMediaItem(url: String, metadata: StreamMetadata?): MediaItem =
        mediaItem(url, metadata, MimeTypes.APPLICATION_M3U8)

    /**
     * @param cache the one [Cache] the download engine writes to. Reading through it always,
     *   rather than only for a downloaded episode, is what makes an episode on the device play
     *   without a network — and what lets an episode watched online carry on through a tunnel
     *   for as far as it was buffered.
     */
    fun mediaSource(item: MediaItem, headers: StreamHeaders, cache: Cache): MediaSource {
        val source: DataSource.Factory = dataSourceFactory(headers, cache)
        val uri = item.localConfiguration?.uri
        return if (uri != null && Util.inferContentType(uri) == C.CONTENT_TYPE_HLS) {
            HlsMediaSource.Factory(source).createMediaSource(item)
        } else {
            DefaultMediaSourceFactory(source).createMediaSource(item)
        }
    }

    /**
     * Every byte this phone plays, read through the download cache first and fetched from Kodik
     * only when it is not there.
     *
     * Built by [DownloadCache.cacheFactory], the same call the downloader is assembled from, so
     * the two cannot drift apart on the one thing they have to agree about: what a cached segment
     * is called. Keyed by the whole URL — which is what a `CacheDataSource` does by default — a
     * re-signed Kodik link would be a file nobody had ever downloaded.
     *
     * `FLAG_IGNORE_CACHE_ON_ERROR` is the safety net over a cache that is not a source of truth:
     * a corrupt span or a full disk drops the read through to the network instead of failing the
     * episode. Offline that fallback fails too, which is correct — there is nothing to play.
     */
    fun dataSourceFactory(headers: StreamHeaders, cache: Cache): CacheDataSource.Factory {
        val http = DownloadCache.httpFactory(headers.userAgent, headers.requestProperties)
        return DownloadCache.cacheFactory(cache, http).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
