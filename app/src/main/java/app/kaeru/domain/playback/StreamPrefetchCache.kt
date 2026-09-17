package app.kaeru.domain.playback

import java.time.Clock
import java.time.Duration

/**
 * One resolved episode, held between the home screen showing it and the viewer pressing play.
 *
 * Resolving an episode is a page fetch, a parse and a signing round trip — seconds, on the very
 * press the whole app is built around. The card at the top of the home screen is the one the
 * viewer is most likely to press, so it is resolved while they are still reading it, and the
 * links are kept here until then.
 *
 * One entry, because there is one such card. A second prefetch replaces the first rather than
 * growing a cache nothing would ever evict.
 *
 * Taking an entry removes it. The links are signed, short-lived and appear to be bound to the IP
 * that asked for them, so a stream that has already failed once must not be handed back a second
 * time — the silent re-resolve after a playback failure has to reach Kodik.
 *
 * Touched from the home screen's coroutine and from the playback controller's main thread, hence
 * the synchronisation; the work inside it is three comparisons.
 */
class StreamPrefetchCache(private val clock: Clock) {

    private class Entry(val resolution: Resolution, val storedAt: java.time.Instant) {
        val stream get() = resolution.stream

        /** The voice the press will ask for: the one that stood in is what played, not what was wanted. */
        val askedFor: Int get() = resolution.insteadOf?.id ?: stream.translation.id
    }

    private var entry: Entry? = null

    /** Keeps [resolution], replacing whatever was here. */
    @Synchronized
    fun put(resolution: Resolution) {
        entry = Entry(resolution, clock.instant())
    }

    /**
     * The stream for this episode in this voice, if it is the one held and still fresh, removing
     * it on the way out.
     *
     * [translationId] is what the caller is about to resolve *with* — the voice this anime
     * remembers, or the one the viewer picked by hand. A null means nothing is remembered, and
     * nothing prefetched can be trusted to match a choice that has not been made yet. A stream
     * that stood in for that voice matches too: the press asks for the same voice the prefetch
     * did, and would arrive at the same stand-in by the same road.
     */
    @Synchronized
    fun take(animeId: Int, episode: Int, translationId: Int?): Resolution? {
        val held = fresh() ?: return null
        if (translationId == null) return null
        if (held.stream.animeId != animeId || held.stream.episode != episode) return null
        if (held.askedFor != translationId) {
            // The voice changed under the links. They are of no use to anyone now, so they go
            // rather than sit out their half hour taking up the one slot there is.
            entry = null
            return null
        }
        entry = null
        return held.resolution
    }

    /** Whether this episode is already held, so a second prefetch of it can be skipped. */
    @Synchronized
    fun holds(animeId: Int, episode: Int): Boolean {
        val held = fresh() ?: return false
        return held.stream.animeId == animeId && held.stream.episode == episode
    }

    private fun fresh(): Entry? {
        val held = entry ?: return null
        if (Duration.between(held.storedAt, clock.instant()) >= TTL) {
            entry = null
            return null
        }
        return held
    }

    companion object {
        /**
         * How long a resolved link is worth keeping. Kodik's links live for hours; half an hour
         * is well inside that and past the point where a viewer who opened the app and wandered
         * off is still the same viewer pressing play.
         */
        val TTL: Duration = Duration.ofMinutes(30)
    }
}
