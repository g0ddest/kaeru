package app.kaeru.data.download

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import app.kaeru.domain.download.DownloadKey
import app.kaeru.domain.model.Translation
import app.kaeru.domain.model.TranslationKind
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What a download carries besides its id.
 *
 * media3 stores one opaque byte array per download and hands it back unchanged, which is the only
 * place this app can keep facts that outlive the process. Two things need it:
 *
 * - the notification, which has to name the title while the app itself may not be running;
 * - the link refresher, which has to ask the source for the *same* track again when a signature
 *   expires, and a track is more than its id — the resolve needs the season it is on.
 *
 * The four id fields are duplicated from [DownloadKey] deliberately. They are what makes the blob
 * readable on its own, and they cost nothing next to the rest.
 */
@kotlinx.serialization.Serializable
data class DownloadPayload(
    val animeId: Int,
    val episode: Int,
    val translationId: Int,
    val quality: Int,
    val title: String? = null,
    val translationTitle: String? = null,
    val season: Int = 1,
) {
    fun encode(): ByteArray = codec.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    /**
     * The track to ask the source for again, as far as this blob knows it.
     *
     * The kind is not stored: Kodik resolves by id and season, and a wrong guess here would only
     * reach a field the resolve does not read.
     */
    fun translation(): Translation = Translation(
        id = translationId,
        title = translationTitle.orEmpty(),
        type = TranslationKind.VOICE,
        episodesCount = null,
        season = season,
    )

    companion object {
        private val codec = Json { ignoreUnknownKeys = true }

        fun of(key: DownloadKey, title: String?, translation: Translation): DownloadPayload = DownloadPayload(
            animeId = key.animeId,
            episode = key.episode,
            translationId = key.translationId,
            quality = key.quality.height,
            title = title,
            translationTitle = translation.title,
            season = translation.season,
        )

        /** Null for a blob this app did not write, or one an older build wrote differently. */
        fun decode(bytes: ByteArray?): DownloadPayload? {
            if (bytes == null || bytes.isEmpty()) return null
            return try {
                codec.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
            } catch (malformed: SerializationException) {
                Log.w("DownloadPayload", "Unreadable download data", malformed)
                null
            }
        }
    }
}

/** The blob media3 is holding for this download, or null when there is nothing readable in it. */
@UnstableApi
fun Download.payload(): DownloadPayload? = DownloadPayload.decode(request.data)
