package app.kaeru.player

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource

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

    fun mediaSource(item: MediaItem, headers: StreamHeaders): MediaSource {
        val http: DataSource.Factory = DefaultHttpDataSource.Factory()
            .setUserAgent(headers.userAgent)
            .setDefaultRequestProperties(headers.requestProperties)
            .setAllowCrossProtocolRedirects(true)
        val uri = item.localConfiguration?.uri
        return if (uri != null && Util.inferContentType(uri) == C.CONTENT_TYPE_HLS) {
            HlsMediaSource.Factory(http).createMediaSource(item)
        } else {
            DefaultMediaSourceFactory(http).createMediaSource(item)
        }
    }
}
