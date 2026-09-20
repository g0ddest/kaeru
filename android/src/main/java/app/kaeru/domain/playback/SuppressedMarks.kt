package app.kaeru.domain.playback

import java.util.concurrent.ConcurrentHashMap

/**
 * Episodes the player is not to count as watched by itself, because the viewer has just said
 * otherwise.
 *
 * One case, and it is reachable rather than theoretical. Playback outlives the player screen in two
 * ways: a cast session keeps running when the screen closes, and picture-in-picture keeps the local
 * engine alive behind whatever the viewer opens next. So an episode can be playing, below the
 * watched threshold, while its own title screen is in front of the viewer — and un-marking it
 * there, then letting the last ten per cent play out, had the app quietly put the mark straight
 * back minutes later with nothing on screen to say so.
 *
 * Only the *automatic* mark is suppressed. A mark the viewer asks for by name is not this set's
 * business, and neither is a later, deliberate re-watch: opening a target clears the set, so the
 * suppression lasts exactly as long as the playback the viewer contradicted.
 *
 * Shared between a use case and the controller, and touched from both the main dispatcher and
 * whatever a view model's coroutine is on, so the set is a concurrent one.
 */
class SuppressedMarks {

    private val episodes = ConcurrentHashMap.newKeySet<Episode>()

    /** This episode has just been un-marked; whatever is playing it must not count it again. */
    fun suppress(animeId: Int, episode: Int) {
        episodes += Episode(animeId, episode)
    }

    fun isSuppressed(animeId: Int, episode: Int): Boolean = Episode(animeId, episode) in episodes

    /** The viewer put the mark back themselves, so there is nothing left to suppress. */
    fun release(animeId: Int, episode: Int) {
        episodes -= Episode(animeId, episode)
    }

    /**
     * A new target is playing.
     *
     * Everything here was said about the playback that has just ended, including about this very
     * episode if it is the one being opened again — a viewer who chooses to watch it a second time
     * is not contradicting themselves, they are watching it.
     */
    fun clear() = episodes.clear()

    private data class Episode(val animeId: Int, val episode: Int)
}
