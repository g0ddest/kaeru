package app.kaeru.domain.playback

import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.UserRate

/**
 * Which episode «продолжить» starts, and where inside it.
 *
 * [positionMs] is zero for an episode that is being offered from the top, which is also how a
 * caller tells the two apart: a target with a position is one this device is in the middle of, and
 * only that case earns «Продолжить с 14:20» instead of «Продолжить 8 серию».
 */
data class ContinueTarget(val episode: Int, val positionMs: Long) {
    companion object {
        /**
         * The one rule for "where was I", read off the per-episode positions.
         *
         * The episode being watched is the latest one that has been genuinely started and is not
         * finished. "Genuinely" is the whole point: a tap that lands on the wrong tile leaves ten
         * seconds behind, and ten seconds is not a place anybody wants to be taken back to — so
         * anything under [EpisodeProgress.started] is treated as never opened, and the forty
         * minutes in the next episode along keep the pointer.
         *
         * Two bounds keep the answer honest. Nothing past [aired] is ever resumed, because there
         * is nothing to play; and nothing at or below the count on Shikimori is either, because
         * that count is what says which episodes are behind the viewer, and a position inside one
         * of those is spent.
         *
         * With nothing to resume the answer is the episode after the furthest the viewer has got.
         * That is Shikimori's count, walked forward one episode at a time over any episode
         * finished on this device that the server has not heard about yet — one at a time, and
         * never across a gap. The gap is the point: a viewer who taps the finale out of the grid
         * and watches it whole has not watched the four episodes before it, and jumping the
         * pointer to the end would leave them with a button that names an episode that does not
         * exist. The same walk is what puts a rewatcher back at the beginning rather than at the
         * end, because their count is zero and the first episode is where the walk starts.
         *
         * The answer is deliberately *not* clamped to [aired]: naming an episode that has not come
         * out is how the watch button knows to say «9 серия выйдет завтра» rather than offering
         * the eighth again. It is clamped at the end of the show — when the walk runs past
         * everything there is and this device has finished episodes to show for it, the offer
         * becomes the last episode there is, from the top. A show the viewer can play must never
         * leave them with a button they cannot press.
         *
         * @param rate the viewer's list entry, or null when the anime is in no list at all.
         * @param aired how many episodes exist to play right now.
         * @param announced how long the season was said to run, or 0 when nobody has said. Only
         *   the end of the show is read off it, so an ongoing season whose next episode is still
         *   to come is never mistaken for one that has run out.
         */
        fun of(
            rate: UserRate?,
            aired: Int,
            announced: Int,
            progress: List<EpisodeProgress>,
            watchedThreshold: Float,
        ): ContinueTarget {
            val counted = rate?.episodes ?: 0
            val started = progress.filter { it.started }
            val resume = started
                .filter { it.episode in (counted + 1)..aired && it.unfinished(watchedThreshold) }
                .maxByOrNull { it.episode }
            if (resume != null) return ContinueTarget(resume.episode, resume.positionMs)

            val finishedHere = started.filterNot { it.unfinished(watchedThreshold) }
                .mapTo(mutableSetOf()) { it.episode }
            var next = counted + 1
            while (next in finishedHere) next++
            val lastEpisode = maxOf(announced, aired)
            if (aired > 0 && finishedHere.isNotEmpty() && next > lastEpisode) return ContinueTarget(aired, 0)
            return ContinueTarget(next, 0)
        }
    }
}
