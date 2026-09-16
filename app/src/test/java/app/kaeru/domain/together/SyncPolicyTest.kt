package app.kaeru.domain.together

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drift is `local − (remote + offset)`: positive means this side is ahead of where the friend's
 * clock says they are. Every case below names the drift it is putting in, so the boundary being
 * tested is readable without arithmetic.
 */
class SyncPolicyTest {
    private val remote = 60_000L

    private fun at(
        drift: Long,
        remotePlaying: Boolean = true,
        localPlaying: Boolean = true,
        offsetMs: Long = 0,
        correcting: Boolean = false,
    ): SyncAction = SyncPolicy.decide(
        localMs = remote + offsetMs + drift,
        remoteMs = remote,
        remotePlaying = remotePlaying,
        localPlaying = localPlaying,
        offsetMs = offsetMs,
        correcting = correcting,
    )

    @Test
    fun `half a second apart is together enough to leave alone`() {
        assertEquals(SyncAction.None, at(drift = 0))
        assertEquals(SyncAction.None, at(drift = 499))
        assertEquals(SyncAction.None, at(drift = -499))
    }

    @Test
    fun `from half a second to two, the one that is ahead slows down`() {
        assertEquals(SyncAction.Rate(SyncPolicy.SLOW), at(drift = 500))
        assertEquals(SyncAction.Rate(SyncPolicy.SLOW), at(drift = 1_999))
        assertEquals(SyncAction.Rate(SyncPolicy.FAST), at(drift = -500))
        assertEquals(SyncAction.Rate(SyncPolicy.FAST), at(drift = -1_999))
    }

    @Test
    fun `from two seconds to ten, the gap is closed by seeking to where the friend is`() {
        assertEquals(SyncAction.SeekTo(remote), at(drift = 2_000))
        assertEquals(SyncAction.SeekTo(remote), at(drift = -2_000))
        assertEquals(SyncAction.SeekTo(remote), at(drift = 10_000))
        assertEquals(SyncAction.SeekTo(remote), at(drift = -10_000))
    }

    @Test
    fun `past ten seconds the jump is large enough that the viewer is told why`() {
        assertEquals(SyncAction.SeekAndNotify(remote), at(drift = 10_001))
        assertEquals(SyncAction.SeekAndNotify(remote), at(drift = -10_001))
        assertEquals(SyncAction.SeekAndNotify(remote), at(drift = 600_000))
    }

    @Test
    fun `the clock offset is part of where the friend is, not a correction on top of it`() {
        // Two seconds of drift measured raw, and none at all once the friend's clock is accounted for.
        assertEquals(SyncAction.None, at(drift = 0, offsetMs = 2_000))
        assertEquals(SyncAction.None, at(drift = 0, offsetMs = -2_000))
        assertEquals(SyncAction.SeekTo(remote + 2_000), at(drift = 3_000, offsetMs = 2_000))
        assertEquals(SyncAction.SeekTo(remote - 2_000), at(drift = -3_000, offsetMs = -2_000))
    }

    @Test
    fun `a friend who is not playing is not a reason to do anything to this side`() {
        // Pausing is what Pause is for. State only ever feeds drift, and drift needs two runners.
        assertEquals(SyncAction.None, at(drift = 30_000, remotePlaying = false))
        assertEquals(SyncAction.None, at(drift = 30_000, localPlaying = false))
        assertEquals(SyncAction.None, at(drift = 30_000, remotePlaying = false, localPlaying = false))
    }

    @Test
    fun `a correction runs until the gap is under a fifth of a second, then hands back normal speed`() {
        assertEquals(SyncAction.Rate(SyncPolicy.SLOW), at(drift = 499, correcting = true))
        assertEquals(SyncAction.Rate(SyncPolicy.FAST), at(drift = -499, correcting = true))
        assertEquals(SyncAction.Rate(SyncPolicy.SLOW), at(drift = 200, correcting = true))
        assertEquals(SyncAction.Rate(SyncPolicy.NORMAL), at(drift = 199, correcting = true))
        assertEquals(SyncAction.Rate(SyncPolicy.NORMAL), at(drift = -199, correcting = true))
        assertEquals(SyncAction.Rate(SyncPolicy.NORMAL), at(drift = 0, correcting = true))
    }

    @Test
    fun `a correction is lifted the moment either side stops playing`() {
        assertEquals(SyncAction.Rate(SyncPolicy.NORMAL), at(drift = 1_000, remotePlaying = false, correcting = true))
        assertEquals(SyncAction.Rate(SyncPolicy.NORMAL), at(drift = 1_000, localPlaying = false, correcting = true))
    }

    @Test
    fun `a seek past the correcting band is a seek, whether or not a correction was running`() {
        assertEquals(SyncAction.SeekTo(remote), at(drift = 5_000, correcting = true))
        assertEquals(SyncAction.SeekAndNotify(remote), at(drift = -20_000, correcting = true))
    }
}
