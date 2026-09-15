package app.kaeru.domain.download

import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.Quality
import kotlinx.coroutines.flow.Flow

/**
 * Everything the app can ask of the download engine.
 *
 * Downloads belong to the device rather than to the account: they survive a sign-out, because
 * an episode already on the phone is not a fact about who is signed in.
 */
interface DownloadRepository {

    /** Every download the engine knows about, in every state, newest state first to change. */
    fun observeAll(): Flow<List<EpisodeDownload>>

    /** The same, narrowed to one title, in episode order. */
    fun observe(animeId: Int): Flow<List<EpisodeDownload>>

    /**
     * The finished download for this episode, or null.
     *
     * This is what lets the player skip the source resolve entirely, so it answers about
     * [DownloadState.COMPLETED] and nothing else: a download half on the device is not something
     * to play from.
     */
    suspend fun completed(animeId: Int, episode: Int): EpisodeDownload?

    /**
     * The same finished download, as something to play: the link it was fetched with, at the one
     * height it was fetched at, in the track it was fetched in. Null when this episode is not
     * fully on the device.
     *
     * The link is the expired one the downloader was given, and that is deliberate — the player
     * reads through the same cache under the same key, where the signature is not part of the
     * name. Nothing is asked of the source, which is the point: offline there is nothing to ask.
     */
    suspend fun completedStream(animeId: Int, episode: Int): EpisodeStream?

    /**
     * Resolves a link for this episode and hands it to the engine.
     *
     * @param quality a height the viewer picked for this one download; null takes the height from
     *   the policy, and a policy with no height takes the best the source offers. A height the
     *   source does not have falls back to the nearest one below it.
     * @return failure with `DownloadLimitReached` when the policy refuses it, or whatever the
     *   resolve failed with. Success means the request reached the engine, not that the episode
     *   is on the device.
     */
    suspend fun enqueue(animeId: Int, episode: Int, quality: Quality? = null): Result<Unit>

    /** Removes this episode's download and the bytes it took, whatever state it was in. */
    suspend fun remove(animeId: Int, episode: Int)

    /** The same for every episode of one title. */
    suspend fun removeAll(animeId: Int)

    /** And for everything on the device. */
    suspend fun removeAll()

    /** Bytes on the device across every download, as the storage line and the limit check read it. */
    val usedBytes: Flow<Long>
}
