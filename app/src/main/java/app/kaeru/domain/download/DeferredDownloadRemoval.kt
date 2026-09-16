package app.kaeru.domain.download

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
 * A process that dies before playback moves on leaves an episode behind, so the promise is written
 * down where a restart can find it ([DeferredRemovals]) and [sweep] keeps it on the next start —
 * by which time nothing is playing anything. Only what was actually promised: an episode the
 * viewer downloaded knowing they had already watched it is not this setting's business.
 */
class DeferredDownloadRemoval(
    private val downloads: DownloadRepository,
    private val settings: SettingsStore,
    private val promises: DeferredRemovals,
) {

    private val lock = Mutex()

    /** What the player is reading right now, or null while nothing is. */
    private var playing: DownloadedEpisode? = null

    /** Watched, downloaded, and waiting for playback to leave it. */
    private val waiting = mutableSetOf<DownloadedEpisode>()

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
        val watched = DownloadedEpisode(animeId, episode)
        // Written down before anything else. A process that dies between here and the removal is
        // the whole reason [sweep] exists, and the only thing that can tell it which episodes this
        // app promised to delete is a note it made at the moment it promised.
        promises.record(watched)
        val deferred = lock.withLock {
            if (playing == watched) {
                waiting += watched
                true
            } else {
                false
            }
        }
        if (!deferred) keep(watched)
    }

    /** Gives the space back and tears up the note that said to. */
    private suspend fun keep(promise: DownloadedEpisode) {
        downloads.remove(promise.animeId, promise.episode)
        promises.forget(promise)
    }

    /**
     * What the player is reading now, or nulls when it has stopped.
     *
     * Called on every target change, so the episode it moves *off* is released here. An episode
     * waiting on a title the viewer has left goes too — playback is not on it either.
     */
    suspend fun nowPlaying(animeId: Int?, episode: Int?) {
        val current = if (animeId != null && episode != null) DownloadedEpisode(animeId, episode) else null
        val release = lock.withLock {
            playing = current
            val free = waiting.filterNot { it == current }
            waiting -= free.toSet()
            free
        }
        release.forEach { keep(it) }
    }

    /**
     * Everything the app owed from a previous run.
     *
     * The promises themselves, not a set worked out again from Shikimori's count. The two look
     * alike and are not: an episode the viewer downloaded *because* they had already seen it is
     * counted too, and sweeping by the count deleted a rewatch the app had offered to keep, on
     * every launch, with nothing on screen to explain it. Nothing is playing at start-up, so
     * there is nothing to defer to.
     *
     * A setting turned off since the promise was made cancels it rather than postponing it: the
     * viewer has decided they want these episodes, and a note left lying about would delete them
     * the day they changed their mind back.
     */
    suspend fun sweep() {
        val owed = promises.pending()
        if (owed.isEmpty()) return
        if (!settings.downloadPolicy.first().deleteWatched) {
            promises.forgetAll()
            return
        }
        owed.forEach { keep(it) }
    }

    /** The sweep, once, on a scope that outlives every screen. */
    fun start(scope: CoroutineScope) {
        scope.launch { sweep() }
    }
}
