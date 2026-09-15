package app.kaeru.domain.playback

import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate

/**
 * Which episode «продолжить» starts, and where inside it.
 *
 * [positionMs] is zero for an episode that is being offered from the top, which is also how a
 * caller tells the two apart: a target with a position is one this device is in the middle of, and
 * only that case earns «Продолжить с 14:20» instead of «Продолжить 8 серию».
 */
data class ContinueTarget(
    val episode: Int,
    val positionMs: Long,
    /**
     * There is nothing left of this show ahead of the viewer, and [episode] is it starting over.
     *
     * Set in exactly one case: a show whose announced run has all aired and whose every episode is
     * behind the viewer. Two things read it. The watch button says «Пересмотреть» rather than
     * «Смотреть 1 серию», because the second would read as a show nobody had opened; and the home
     * feed leaves the title out of «Дальше по списку», because a finished show is not something to
     * watch next — it is something to go back to, which the title screen offers.
     *
     * Not set for a viewer who has chosen «Пересматриваю» and reset their counter. They have a
     * show ahead of them, one they are working through in order, and it belongs in the feed with
     * the rest — the flag is about a show that has run out, not about a viewer who has seen it.
     */
    val rewatch: Boolean = false,
) {
    companion object {

        /** Where a show starts, which is also where a rewatch starts. */
        private const val FIRST_EPISODE = 1

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
         * exist.
         *
         * A rewatcher does not get the walk at all. Their finished rows are from the last time
         * round and say nothing about where this time round has reached, so the counter they reset
         * is the whole answer: «Пересматриваю» with the count back at zero starts at the first
         * episode. The position inside the episode they are actually watching still wins, because
         * that is a resume and resumes are decided above.
         *
         * The answer is deliberately *not* clamped to [aired]: naming an episode that has not come
         * out is how the watch button knows to say «9 серия выйдет завтра» rather than offering
         * the eighth again. There is one exception, and it is the end of a show rather than a
         * clamp: when every [announced] episode has aired and the walk has run past the last of
         * them, the whole show is behind the viewer and the offer becomes the first episode with
         * [rewatch] set. A show that can be played must never leave its viewer with a button they
         * cannot press, and the honest thing to offer somebody who has seen all of it is the
         * beginning — not the finale they watched last, and not a wait for an episode that will
         * never come.
         *
         * A season still airing is not a season that has run out, however far ahead the count on
         * Shikimori has got: eight episodes of an announced twelve is «Ждём 9 серию».
         *
         * @param rate the viewer's list entry, or null when the anime is in no list at all.
         * @param aired how many episodes exist to play right now.
         * @param announced how long the season was said to run, or 0 when nobody has said — which
         *   is Shikimori's answer for most ongoing shows. Nothing is clamped without it: a season
         *   whose length is unknown has not run out, it has only run out of aired episodes, and
         *   the next one is what the viewer is waiting for.
         * @param finishedAiring whether the catalogue says this show is over. It is the only way to
         *   tell a finished show whose length nobody recorded from one that is still airing, since
         *   both arrive here as an [announced] of zero — and the difference is «Пересмотреть»
         *   against «Ждём 9 серию». The default is the safe half of that: a show nobody has called
         *   finished is waited for, never restarted, which is the answer this had for every caller
         *   before the flag existed.
         */
        fun of(
            rate: UserRate?,
            aired: Int,
            announced: Int,
            progress: List<EpisodeProgress>,
            watchedThreshold: Float,
            finishedAiring: Boolean = false,
        ): ContinueTarget {
            val counted = rate?.episodes ?: 0
            val rewatching = rate?.status == ListStatus.REWATCHING
            val started = progress.filter { it.started }
            val resume = started
                .filter { it.episode in (counted + 1)..aired && it.unfinished(watchedThreshold) }
                .maxByOrNull { it.episode }
            if (resume != null) return ContinueTarget(resume.episode, resume.positionMs)

            val finishedHere = if (rewatching) {
                emptySet()
            } else {
                started.filterNot { it.unfinished(watchedThreshold) }.mapTo(mutableSetOf()) { it.episode }
            }
            var next = counted + 1
            while (next in finishedHere) next++
            // A show has run out either way it can be known to have. Every episode it announced is
            // out — eight of twelve is a season still airing, whatever the count on Shikimori has
            // reached, and the ninth is what its viewer is waiting for. Or the catalogue simply
            // says it is over, which is the only thing to go on for a finished show whose length
            // nobody recorded: `episodes` is zero there, and without this such a title offered
            // «Ждём N серию», unpressable, for ever.
            val runEnded = (announced > 0 && aired >= announced) || (finishedAiring && aired > 0)
            if (runEnded && next > maxOf(announced, aired)) return ContinueTarget(FIRST_EPISODE, 0, rewatch = true)
            return ContinueTarget(next, 0)
        }
    }
}
