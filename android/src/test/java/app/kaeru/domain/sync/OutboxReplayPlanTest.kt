package app.kaeru.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class OutboxReplayPlanTest {
    private fun op(id: Long, anime: Int, kind: RateOpKind, value: String) =
        RateOp(id, anime, kind, value, Instant.EPOCH.plusSeconds(id))

    @Test
    fun `ops are replayed in id order and the last value per anime and kind wins`() {
        val plan = OutboxReplayPlan.of(listOf(
            op(1, 10, RateOpKind.EPISODES, "5"),
            op(2, 10, RateOpKind.EPISODES, "6"),
            op(3, 11, RateOpKind.STATUS, "completed"),
        ))
        assertEquals(listOf(op(2, 10, RateOpKind.EPISODES, "6"), op(3, 11, RateOpKind.STATUS, "completed")), plan.toSend)
        assertEquals(setOf(1L), plan.superseded)
    }

    @Test
    fun `a status change and an episode count for the same anime are both kept, in order`() {
        val plan = OutboxReplayPlan.of(listOf(op(1, 10, RateOpKind.STATUS, "watching"), op(2, 10, RateOpKind.EPISODES, "3")))
        assertEquals(listOf(1L, 2L), plan.toSend.map { it.id })
    }
}
