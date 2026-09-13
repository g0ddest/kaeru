package app.kaeru.domain.playback

import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Twenty-four minutes, the length of an ordinary episode. */
private const val EPISODE_MS = 1_440_000L

class ContinueTargetTest {
    private val threshold = 0.9f

    private fun rate(episodes: Int) = UserRate(1L, 100, ListStatus.WATCHING, episodes, Instant.EPOCH)

    private fun progress(episode: Int, positionMs: Long, durationMs: Long = EPISODE_MS) =
        EpisodeProgress(100, episode, positionMs, durationMs, Instant.EPOCH)

    @Test
    fun `a mis-tap on an earlier episode does not move the pointer off the one being watched`() {
        // The bug this whole table exists for: forty minutes into the seventh, a tap lands on the
        // sixth, and ten seconds of the sixth must not become «Продолжить 6 серию».
        val target = ContinueTarget.of(
            rate(6),
            aired = 10,
            progress = listOf(progress(7, 2_400_000, 2_880_000), progress(6, 10_000)),
            watchedThreshold = threshold,
        )

        assertEquals(ContinueTarget(7, 2_400_000), target)
    }

    @Test
    fun `an episode watched to the end hands over to the next one, from the beginning`() {
        val target = ContinueTarget.of(
            rate(6),
            aired = 10,
            progress = listOf(progress(7, 1_400_000)),
            watchedThreshold = threshold,
        )

        assertEquals(ContinueTarget(8, 0), target)
    }

    @Test
    fun `with nothing started the next episode after Shikimori's count is offered`() {
        assertEquals(
            ContinueTarget(7, 0),
            ContinueTarget.of(rate(6), aired = 10, progress = emptyList(), watchedThreshold = threshold),
        )
    }

    @Test
    fun `an anime in no list at all starts at the first episode`() {
        assertEquals(
            ContinueTarget(1, 0),
            ContinueTarget.of(null, aired = 12, progress = emptyList(), watchedThreshold = threshold),
        )
    }

    @Test
    fun `an announcement with nothing aired has nothing to resume`() {
        assertEquals(
            ContinueTarget(1, 0),
            ContinueTarget.of(rate(0), aired = 0, progress = listOf(progress(1, 600_000)), watchedThreshold = threshold),
        )
    }

    @Test
    fun `an episode that has not aired is never resumed from`() {
        // The catalogue says eight are out; a position in the ninth is not an offer to play it.
        val target = ContinueTarget.of(
            rate(8),
            aired = 8,
            progress = listOf(progress(9, 600_000)),
            watchedThreshold = threshold,
        )

        assertEquals(0L, target.positionMs)
        assertEquals(9, target.episode)
    }

    @Test
    fun `the latest unfinished episode wins over an earlier one`() {
        val target = ContinueTarget.of(
            rate(3),
            aired = 12,
            progress = listOf(progress(4, 600_000), progress(9, 300_000), progress(6, 900_000)),
            watchedThreshold = threshold,
        )

        assertEquals(ContinueTarget(9, 300_000), target)
    }

    @Test
    fun `a minute in counts as started however long the episode runs`() {
        // Two hours: a fiftieth of it is nearly two and a half minutes, so the share alone would
        // call the first minute a mis-tap. A minute of a film is plainly watching it.
        val film = 7_200_000L
        assertEquals(
            ContinueTarget(5, 60_000),
            ContinueTarget.of(rate(4), 12, listOf(progress(5, 60_000, film)), threshold),
        )
        assertEquals(
            ContinueTarget(5, 0),
            ContinueTarget.of(rate(4), 12, listOf(progress(5, 59_999, film)), threshold),
        )
    }

    @Test
    fun `a short episode qualifies on the share rather than the minute`() {
        // Three minutes long: a fiftieth of it is under four seconds, and a minute would be a
        // third of the run — far too late to call it "started".
        val short = 180_000L
        assertEquals(
            ContinueTarget(5, 3_600),
            ContinueTarget.of(rate(4), 12, listOf(progress(5, 3_600, short)), threshold),
        )
        assertEquals(
            ContinueTarget(5, 0),
            ContinueTarget.of(rate(4), 12, listOf(progress(5, 3_000, short)), threshold),
        )
    }

    @Test
    fun `the watched threshold is the boundary, and reaching it is finishing`() {
        val exactly = (EPISODE_MS * 0.9).toLong()
        assertEquals(
            ContinueTarget(6, 0),
            ContinueTarget.of(rate(4), 12, listOf(progress(5, exactly)), threshold),
        )
        assertEquals(
            ContinueTarget(5, exactly - 1_000),
            ContinueTarget.of(rate(4), 12, listOf(progress(5, exactly - 1_000)), threshold),
        )
    }

    @Test
    fun `an episode finished locally moves the pointer on before Shikimori has heard about it`() {
        val target = ContinueTarget.of(
            rate(4),
            aired = 12,
            progress = listOf(progress(5, 1_400_000), progress(6, 1_400_000)),
            watchedThreshold = threshold,
        )

        assertEquals(ContinueTarget(7, 0), target)
    }

    @Test
    fun `a position in an episode Shikimori already counted is spent`() {
        // Going back over an episode that is behind the viewer is not a place to resume: the count
        // on the server is what says where they are, and it has passed this one.
        val target = ContinueTarget.of(
            rate(8),
            aired = 12,
            progress = listOf(progress(6, 700_000)),
            watchedThreshold = threshold,
        )

        assertEquals(ContinueTarget(9, 0), target)
    }

    @Test
    fun `an episode with no known duration still resumes once a minute is behind it`() {
        // Duration arrives from the player a moment after playback starts; a sample taken before
        // that must not be thrown away.
        assertEquals(
            ContinueTarget(5, 120_000),
            ContinueTarget.of(rate(4), 12, listOf(progress(5, 120_000, durationMs = 0)), threshold),
        )
    }
}
