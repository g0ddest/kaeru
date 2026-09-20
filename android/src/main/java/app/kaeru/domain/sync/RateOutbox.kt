package app.kaeru.domain.sync

import java.time.Instant

/** Which half of a user rate a queued write carries. */
enum class RateOpKind { STATUS, EPISODES }

/**
 * One Shikimori write the viewer made while the network was gone.
 *
 * [value] is the wire form of whichever half [kind] names — a `ListStatus.apiValue` for
 * [RateOpKind.STATUS], a decimal episode count for [RateOpKind.EPISODES] — because the queue
 * outlives the process that filled it and a string is the one shape both halves survive in.
 * [id] is the order the viewer acted in, and replaying in any other order would hand Shikimori
 * an older intent last.
 */
data class RateOp(
    val id: Long,
    val animeId: Int,
    val kind: RateOpKind,
    val value: String,
    val createdAt: Instant,
)

/** Which queued writes still have to reach Shikimori: the newest per (anime, kind), in the order they were made. */
data class OutboxReplayPlan(val toSend: List<RateOp>, val superseded: Set<Long>) {
    companion object {
        fun of(ops: List<RateOp>): OutboxReplayPlan {
            val newest = ops.groupBy { it.animeId to it.kind }.mapValues { (_, same) -> same.maxBy { it.id }.id }
            val send = ops.filter { newest[it.animeId to it.kind] == it.id }.sortedBy { it.id }
            return OutboxReplayPlan(send, ops.map { it.id }.toSet() - send.map { it.id }.toSet())
        }
    }
}
