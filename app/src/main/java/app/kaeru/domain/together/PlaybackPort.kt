package app.kaeru.domain.together

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The player as a shared session needs to see it: an episode, a position and whether it is moving.
 *
 * Nothing about surfaces, manifests or quality rungs — a session has no business with any of it,
 * and what it does have business with is the same four facts on both phones.
 */
data class PortState(
    val positionMs: Long = 0,
    val playing: Boolean = false,
    /** The picture has stopped for want of data. Never a reason to stop the friend's picture. */
    val buffering: Boolean = false,
    val animeId: Int? = null,
    val episode: Int? = null,
    val translationId: Int? = null,
)

/**
 * Something the person holding this phone did, on its way to their friend.
 *
 * Only ever what a viewer chose: a press, a scrub, an episode they picked and the one autoplay
 * ran into for them. What arrives from the friend and is applied through [PlaybackPort] is
 * deliberately absent, because a session that forwarded those would have two phones telling each
 * other to seek to the position they are both already at, for ever.
 */
sealed interface LocalAction {
    data class Play(val positionMs: Long) : LocalAction

    data class Pause(val positionMs: Long) : LocalAction

    data class Seek(val positionMs: Long) : LocalAction

    /** An episode, and the voice it is playing in once that is actually known. */
    data class Episode(val animeId: Int, val episode: Int, val translationId: Int?) : LocalAction
}

/**
 * What a shared viewing is allowed to do to the picture.
 *
 * The session that drives it is `data.together.TogetherSession` — it owns a scope, the transports
 * and a log, none of which belong on this side of the seam.
 *
 * Every call here is the friend's doing rather than this viewer's, so none of them may come back
 * out of [localActions]. That is the whole of the no-echo rule, and it lives on this side of the
 * seam because only the player knows which of its callers was a person.
 */
interface PlaybackPort {
    val state: StateFlow<PortState>

    /** Actions this viewer took. Nothing this interface's own methods caused ever appears here. */
    val localActions: Flow<LocalAction>

    suspend fun play()

    suspend fun pause()

    suspend fun seekTo(positionMs: Long)

    /** Slightly slow or slightly fast, to close a gap of a second or two without a visible jump. */
    suspend fun setRate(factor: Float)

    /**
     * Opens what the friend is watching: the same episode, in the same voice if this device has
     * it, from [positionMs]. A voice this device cannot get is not an error — the player picks
     * its own and [state] says which, which is how the session knows to mention it.
     */
    suspend fun openEpisode(animeId: Int, episode: Int, translationId: Int?, positionMs: Long)
}
