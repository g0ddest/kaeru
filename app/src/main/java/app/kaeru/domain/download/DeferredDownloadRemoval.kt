package app.kaeru.domain.download

import app.kaeru.domain.repository.LibraryRepository
import app.kaeru.domain.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * «Удалять просмотренные», done at a moment when deleting is safe.
 *
 * The setting sounds simple and is not, because of when the mark that triggers it happens: the
 * player raises it at the watched threshold, nine tenths of the way through, while the episode is
 * still playing — and playing, for a downloaded episode, means reading the very file this would
 * delete. The segments go, the next read misses the cache and falls through to a Kodik address
 * whose signature died weeks ago, and offline — the whole reason the episode was downloaded — there
 * is nothing behind that address at all. The viewer loses the last ten per cent of an episode they
 * downloaded on purpose, with no way to get it back until they have a network again.
 *
 * So the deletion is deferred rather than skipped: the episode is remembered, and it goes the
 * moment playback moves off it — the next episode, or the player closing. Nothing is lost by
 * waiting, since the space is only wanted for the next download.
 *
 * A process that dies before playback moves on leaves an episode behind. [sweep] is the answer to
 * that: on the next start, anything already counted as watched is gone before it can matter, and by
 * then nothing is playing it.
 */
class DeferredDownloadRemoval(
    private val downloads: DownloadRepository,
    private val library: LibraryRepository,
    private val settings: SettingsStore,
) {

    private val lock = Mutex()

    /** What the player is reading right now, or null while nothing is. */
    private var playing: Episode? = null

    /** Watched, downloaded, and waiting for playback to leave it. */
    private val waiting = mutableSetOf<Episode>()

    /**
     * An episode has just been counted as watched.
     *
     * Does nothing unless the viewer asked for this, and nothing for a download that has not
     * finished: a queue set up for tonight is not something a mark should quietly cancel, and half
     * an episode would be deleted a minute later anyway, having spent the data for nothing.
     */
    suspend fun onWatched(animeId: Int, episode: Int) {
        if (!settings.downloadPolicy.first().deleteWatched) return
        if (downloads.completed(animeId, episode) == null) return
        val watched = Episode(animeId, episode)
        val deferred = lock.withLock {
            if (playing == watched) {
                waiting += watched
                true
            } else {
                false
            }
        }
        if (!deferred) downloads.remove(animeId, episode)
    }

    /**
     * What the player is reading now, or nulls when it has stopped.
     *
     * Called on every target change, so the episode it moves *off* is released here. An episode
     * waiting on a title the viewer has left goes too — playback is not on it either.
     */
    suspend fun nowPlaying(animeId: Int?, episode: Int?) {
        val current = if (animeId != null && episode != null) Episode(animeId, episode) else null
        val release = lock.withLock {
            playing = current
            val free = waiting.filterNot { it == current }
            waiting -= free.toSet()
            free
        }
        release.forEach { downloads.remove(it.animeId, it.episode) }
    }

    /**
     * Everything the app owed from a previous run.
     *
     * Shikimori's count is the record that survived, so it is what this reads: an episode already
     * counted, still on the device, with the setting on, is one this app meant to delete and did
     * not. Nothing is playing at start-up, so there is nothing to defer to.
     */
    suspend fun sweep() {
        if (!settings.downloadPolicy.first().deleteWatched) return
        val counted = library.observeLibrary().first()
            .associate { it.anime.id to it.rate.episodes }
        downloads.observeAll().first()
            .filter { it.state == DownloadState.COMPLETED && it.episode <= (counted[it.animeId] ?: 0) }
            .forEach { downloads.remove(it.animeId, it.episode) }
    }

    /** The sweep, once, on a scope that outlives every screen. */
    fun start(scope: CoroutineScope) {
        scope.launch { sweep() }
    }

    private data class Episode(val animeId: Int, val episode: Int)
}
